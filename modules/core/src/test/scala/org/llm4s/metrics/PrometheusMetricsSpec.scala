package org.llm4s.metrics

import io.prometheus.client.CollectorRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

/**
 * Unit tests for PrometheusMetrics.
 *
 * Tests metric recording functionality without starting HTTP server.
 */
class PrometheusMetricsSpec extends AnyWordSpec with Matchers {

  "PrometheusMetrics.create" should {
    "create an instance without HTTP server" in {
      val metrics = PrometheusMetrics.create()
      metrics.getEndpoint shouldBe None
    }

    "have a working registry" in {
      val metrics = PrometheusMetrics.create()
      metrics.registry should not be null
    }
  }

  "PrometheusMetrics.start" should {
    "start HTTP server on specified port" in {
      val result = PrometheusMetrics.start(port = 19090) // Use high port to avoid conflicts
      result should be a Symbol("right")

      result.foreach { metrics =>
        metrics.getEndpoint shouldBe Some("http://localhost:19090/metrics")
        metrics.stop()
      }
    }

    "fail if port is already in use" in {
      val metrics1 = PrometheusMetrics.start(port = 19091).toOption.get

      try {
        val result2 = PrometheusMetrics.start(port = 19091)
        result2 should be a Symbol("left")
      } finally {
        metrics1.stop()
      }
    }
  }

  "PrometheusMetrics.recordSuccess" should {
    "increment requests counter with success status" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordSuccess("openai", "gpt-4o", 1500L)

      val requestsTotal = getMetricValue(metrics.registry, "llm4s_requests_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o",
        "status"   -> "success"
      ))

      requestsTotal shouldBe 1.0
    }

    "record latency in histogram" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordSuccess("openai", "gpt-4o", 1500L) // 1.5 seconds

      val histogramCount = getHistogramCount(metrics.registry, "llm4s_request_duration_seconds", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      histogramCount shouldBe 1.0
    }

    "record multiple requests" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordSuccess("openai", "gpt-4o", 1000L)
      metrics.recordSuccess("openai", "gpt-4o", 2000L)
      metrics.recordSuccess("anthropic", "claude-3-5-sonnet-latest", 1500L)

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
  }

  "PrometheusMetrics.recordTokens" should {
    "record input and output tokens separately" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordTokens(100, 50, "openai", "gpt-4o")

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

      metrics.recordTokens(100, 50, "openai", "gpt-4o")
      metrics.recordTokens(200, 75, "openai", "gpt-4o")

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

  "PrometheusMetrics.recordCost" should {
    "record cost in USD" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordCost(0.05, "openai", "gpt-4o")

      val cost = getMetricValue(metrics.registry, "llm4s_cost_usd_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      cost shouldBe 0.05 +- 0.001
    }

    "accumulate costs" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordCost(0.05, "openai", "gpt-4o")
      metrics.recordCost(0.03, "openai", "gpt-4o")

      val cost = getMetricValue(metrics.registry, "llm4s_cost_usd_total", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      cost shouldBe 0.08 +- 0.001
    }
  }

  "PrometheusMetrics.recordError" should {
    "increment error counter" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordError("openai", "gpt-4o", "RateLimitError", Some(500L))

      val errors = getMetricValue(metrics.registry, "llm4s_errors_total", Map(
        "provider"   -> "openai",
        "error_type" -> "RateLimitError"
      ))

      errors shouldBe 1.0
    }

    "record latency even for errors" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordError("openai", "gpt-4o", "RateLimitError", Some(500L))

      val histogramCount = getHistogramCount(metrics.registry, "llm4s_request_duration_seconds", Map(
        "provider" -> "openai",
        "model"    -> "gpt-4o"
      ))

      histogramCount shouldBe 1.0
    }

    "handle multiple error types" in {
      val metrics = PrometheusMetrics.create()

      metrics.recordError("openai", "gpt-4o", "RateLimitError", Some(500L))
      metrics.recordError("openai", "gpt-4o", "AuthenticationError", Some(100L))
      metrics.recordError("openai", "gpt-4o", "RateLimitError", Some(600L))

      val rateLimitErrors = getMetricValue(metrics.registry, "llm4s_errors_total", Map(
        "provider"   -> "openai",
        "error_type" -> "RateLimitError"
      ))

      val authErrors = getMetricValue(metrics.registry, "llm4s_errors_total", Map(
        "provider"   -> "openai",
        "error_type" -> "AuthenticationError"
      ))

      rateLimitErrors shouldBe 2.0
      authErrors shouldBe 1.0
    }
  }

  "PrometheusMetrics.default" should {
    "provide a singleton instance" in {
      val instance1 = PrometheusMetrics.default
      val instance2 = PrometheusMetrics.default

      instance1 should be theSameInstanceAs instance2
    }

    "be usable for recording metrics" in {
      val metrics = PrometheusMetrics.default

      // Record some metrics
      metrics.recordSuccess("test", "model", 1000L)

      // Should not throw
      noException should be thrownBy {
        metrics.recordTokens(100, 50, "test", "model")
      }
    }
  }

  "PrometheusMetrics histogram buckets" should {
    "correctly distribute latencies" in {
      val metrics = PrometheusMetrics.create()

      // Record various latencies
      metrics.recordSuccess("openai", "gpt-4o", 50L)    // 0.05s - should be in le=0.1
      metrics.recordSuccess("openai", "gpt-4o", 500L)   // 0.5s - should be in le=0.5
      metrics.recordSuccess("openai", "gpt-4o", 1500L)  // 1.5s - should be in le=2.0
      metrics.recordSuccess("openai", "gpt-4o", 6000L)  // 6s - should be in le=10.0

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

  "PrometheusMetrics.stop" should {
    "not throw when no server is running" in {
      val metrics = PrometheusMetrics.create()
      noException should be thrownBy {
        metrics.stop()
      }
    }

    "stop the HTTP server" in {
      val metrics = PrometheusMetrics.start(port = 19092).toOption.get
      noException should be thrownBy {
        metrics.stop()
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
