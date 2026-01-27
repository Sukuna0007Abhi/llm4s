package org.llm4s.metrics

import io.prometheus.client.CollectorRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Unit tests for PrometheusMetrics.
 *
 * Tests metric recording functionality through the MetricsCollector interface.
 */
class PrometheusMetricsSpec extends AnyWordSpec with Matchers {

  "PrometheusMetrics.create" should {
    "create an instance without HTTP server" in {
      val metrics = PrometheusMetrics.create()
      metrics should not be null
    }

    "have a working registry" in {
      val metrics = PrometheusMetrics.create()
      metrics.registry should not be null
    }
  }

  "PrometheusEndpoint" should {
    "start HTTP server on specified port" in {
      val registry = new CollectorRegistry()
      val result = PrometheusEndpoint.start(port = 0, registry) // OS assigns port

      result should be a Symbol("right")

      result.foreach { endpoint =>
        endpoint.port should be > 0 // Verify OS assigned a port
        endpoint.stop()
      }
    }

    "fail if port is already in use" in {
      val registry = new CollectorRegistry()
      val endpoint1 = PrometheusEndpoint.start(port = 0, registry).toOption.get
      val actualPort = endpoint1.port

      try {
        val result2 = PrometheusEndpoint.start(port = actualPort, registry)
        result2 should be a Symbol("left")
      } finally {
        endpoint1.stop()
      }
    }
  }

  "MetricsCollector.observeRequest" should {
    "increment requests counter with success status" in {
      val metrics = PrometheusMetrics.create()

      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.5.seconds)

      val requestsTotal = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "status"   -> "success"
      ))

      requestsTotal shouldBe 1.0
    }

    "record latency in histogram" in {
      val metrics = PrometheusMetrics.create()

      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.5.seconds)

      val histogramCount = getHistogramCount(metrics.registry, "llm4s_request_duration_seconds", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      histogramCount shouldBe 1.0
    }

    "record multiple requests" in {
      val metrics = PrometheusMetrics.create()

      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 2.seconds)
      metrics.observeRequest("anthropic", "claude-3-5-sonnet-latest", Outcome.Success, 1.5.seconds)

      val openaiRequests = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "status"   -> "success"
      ))

      val anthropicRequests = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "anthropic",
        "model"    -> "claude-3-5-sonnet-latest",
        "status"   -> "success"
      ))

      openaiRequests shouldBe 2.0
      anthropicRequests shouldBe 1.0
    }

    "record errors with error kind" in {
      val metrics = PrometheusMetrics.create()

      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.RateLimit), 500.millis)

      val requests = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "status"   -> "error_rate_limit"
      ))

      requests shouldBe 1.0
    }

    "handle multiple error types" in {
      val metrics = PrometheusMetrics.create()

      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.RateLimit), 500.millis)
      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.Authentication), 100.millis)
      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.RateLimit), 600.millis)

      val rateLimitErrors = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "status"   -> "error_rate_limit"
      ))

      val authErrors = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "status"   -> "error_authentication"
      ))

      rateLimitErrors shouldBe 2.0
      authErrors shouldBe 1.0
    }
  }

  "MetricsCollector.addTokens" should {
    "record input and output tokens separately" in {
      val metrics = PrometheusMetrics.create()

      metrics.addTokens("openai", "gpt-4o", 100L, 50L)

      val inputTokens = getMetricValue(metrics.registry, "llm4s_tokens_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "type"     -> "input"
      ))

      val outputTokens = getMetricValue(metrics.registry, "llm4s_tokens_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "type"     -> "output"
      ))

      inputTokens shouldBe 100.0
      outputTokens shouldBe 50.0
    }

    "accumulate tokens across multiple calls" in {
      val metrics = PrometheusMetrics.create()

      metrics.addTokens("openai", "gpt-4o", 100L, 50L)
      metrics.addTokens("openai", "gpt-4o", 200L, 75L)

      val inputTokens = getMetricValue(metrics.registry, "llm4s_tokens_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "type"     -> "input"
      ))

      val outputTokens = getMetricValue(metrics.registry, "llm4s_tokens_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "type"     -> "output"
      ))

      inputTokens shouldBe 300.0 // 100 + 200
      outputTokens shouldBe 125.0 // 50 + 75
    }
  }

  "PrometheusMetrics histogram buckets" should {
    "correctly distribute latencies" in {
      val metrics = PrometheusMetrics.create()

      // Record various latencies
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 50.millis)    // 0.05s - should be in le=0.1
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 500.millis)   // 0.5s - should be in le=0.5
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.5.seconds)  // 1.5s - should be in le=2.0
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 6.seconds)    // 6s - should be in le=10.0

      val le01 = getHistogramBucketValue(metrics.registry, "llm4s_request_duration_seconds", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ), "0.1")

      val le05 = getHistogramBucketValue(metrics.registry, "llm4s_request_duration_seconds", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ), "0.5")

      val le20 = getHistogramBucketValue(metrics.registry, "llm4s_request_duration_seconds", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ), "2.0")

      le01 shouldBe 1.0 // Only first request
      le05 shouldBe 2.0 // First two requests
      le20 shouldBe 3.0 // First three requests
    }
  }

  "MetricsCollector.recordCost" should {
    "record cost in USD" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordCost("openai", "gpt-4o", 0.05)

      val cost = getMetricValue(metrics.registry, "llm4s_cost_usd_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      cost shouldBe 0.05 +- 0.001
    }

    "accumulate costs" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordCost("openai", "gpt-4o", 0.05)
      metrics.recordCost("openai", "gpt-4o", 0.03)

      val cost = getMetricValue(metrics.registry, "llm4s_cost_usd_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      cost shouldBe 0.08 +- 0.001
    }
  }

  "MetricsCollector.noop" should {
    "not throw on any operation" in {
      val metrics = MetricsCollector.noop

      noException should be thrownBy {
        metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
        metrics.addTokens("openai", "gpt-4o", 100L, 50L)
        metrics.recordCost("openai", "gpt-4o", 0.05)
        metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.RateLimit), 500.millis)
      }
    }
  }

  // Helper methods for extracting metric values

  private def getMetricValue(registry: CollectorRegistry, name: String, labels: Map[String, String]): Double = {
    // Get all samples from all metric families
    val allSamples = registry.metricFamilySamples().asScala.flatMap(_.samples.asScala)
    
    // Find the sample that matches both the name and all labels
    allSamples
      .find { sample =>
        sample.name == name && labels.forall { case (labelName, labelValue) =>
          val labelNames = sample.labelNames.asScala
          val labelValues = sample.labelValues.asScala
          val index = labelNames.indexOf(labelName)
          index >= 0 && labelValues.lift(index).contains(labelValue)
        }
      }
      .map(_.value)
      .getOrElse(0.0)
  }

  private def getHistogramCount(registry: CollectorRegistry, name: String, labels: Map[String, String]): Double = {
    getMetricValue(registry, s"${name}_count", labels)
  }

  private def getHistogramBucketValue(
    registry: CollectorRegistry,
    name: String,
    labels: Map[String, String],
    le: String
  ): Double = {
    getMetricValue(registry, s"${name}_bucket", labels + ("le" -> le))
  }
}
