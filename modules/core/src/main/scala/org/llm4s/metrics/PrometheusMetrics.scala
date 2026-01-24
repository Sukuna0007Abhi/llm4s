package org.llm4s.metrics

import io.prometheus.client.{ Counter, Histogram, CollectorRegistry }
import io.prometheus.client.exporter.HTTPServer
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress

/**
 * Prometheus metrics collector for LLM operations.
 *
 * Tracks request volumes, token usage, costs, errors, and latency across
 * different providers and models. Exposes metrics via HTTP endpoint for
 * Prometheus scraping.
 *
 * Example usage:
 * {{{
 * val metrics = PrometheusMetrics.start(port = 9090)
 * metrics.recordSuccess("openai", "gpt-4", durationMs = 1234)
 * metrics.recordTokens(usage, "openai", "gpt-4")
 * }}}
 *
 * @param registry Prometheus collector registry
 * @param serverOpt Optional HTTP server for metrics endpoint
 */
class PrometheusMetrics private (
  private[metrics] val registry: CollectorRegistry,
  private val serverOpt: Option[HTTPServer]
) {

  private val logger = LoggerFactory.getLogger(getClass)

  // Request counter with labels
  private val requestsTotal = Counter
    .build()
    .name("llm4s_requests_total")
    .help("Total number of LLM requests")
    .labelNames("provider", "model", "status")
    .register(registry)

  // Token counter
  private val tokensTotal = Counter
    .build()
    .name("llm4s_tokens_total")
    .help("Total tokens consumed")
    .labelNames("provider", "model", "type")
    .register(registry)

  // Cost counter (in USD)
  private val costUsdTotal = Counter
    .build()
    .name("llm4s_cost_usd_total")
    .help("Total estimated cost in USD")
    .labelNames("provider", "model")
    .register(registry)

  // Error counter
  private val errorsTotal = Counter
    .build()
    .name("llm4s_errors_total")
    .help("Total number of errors")
    .labelNames("provider", "error_type")
    .register(registry)

  // Request duration histogram
  private val requestDuration = Histogram
    .build()
    .name("llm4s_request_duration_seconds")
    .help("Request duration in seconds")
    .labelNames("provider", "model")
    .buckets(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0)
    .register(registry)

  /**
   * Record a successful request.
   *
   * @param provider Provider name (e.g., "openai", "anthropic")
   * @param model Model name (e.g., "gpt-4")
   * @param durationMs Request duration in milliseconds
   */
  def recordSuccess(provider: String, model: String, durationMs: Long): Unit = {
    requestsTotal.labels(provider, model, "success").inc()
    requestDuration.labels(provider, model).observe(durationMs / 1000.0)
  }

  /**
   * Record token usage.
   *
   * @param inputTokens Number of input tokens
   * @param outputTokens Number of output tokens
   * @param provider Provider name
   * @param model Model name
   */
  def recordTokens(
    inputTokens: Int,
    outputTokens: Int,
    provider: String,
    model: String
  ): Unit = {
    tokensTotal.labels(provider, model, "input").inc(inputTokens.toDouble)
    tokensTotal.labels(provider, model, "output").inc(outputTokens.toDouble)
  }

  /**
   * Record estimated cost.
   *
   * @param costUsd Cost in USD
   * @param provider Provider name
   * @param model Model name
   */
  def recordCost(costUsd: Double, provider: String, model: String): Unit = {
    costUsdTotal.labels(provider, model).inc(costUsd)
  }

  /**
   * Record an error.
   *
   * @param provider Provider name
   * @param model Model name
   * @param errorType Error type (e.g., "rate_limit", "timeout")
   * @param durationMs Optional duration before error
   */
  def recordError(
    provider: String,
    model: String,
    errorType: String,
    durationMs: Option[Long] = None
  ): Unit = {
    requestsTotal.labels(provider, model, "error").inc()
    errorsTotal.labels(provider, errorType).inc()
    durationMs.foreach { ms =>
      requestDuration.labels(provider, model).observe(ms / 1000.0)
    }
  }

  /**
   * Get the metrics endpoint URL.
   */
  def getEndpoint: Option[String] = serverOpt.map { server =>
    s"http://localhost:${server.getPort}/metrics"
  }

  /**
   * Stop the HTTP server.
   */
  def stop(): Unit = {
    serverOpt.foreach { server =>
      logger.info("Stopping Prometheus metrics server")
      server.close()
    }
  }
}

object PrometheusMetrics {

  /**
   * Create and start a PrometheusMetrics instance with HTTP server.
   *
   * @param port Port for HTTP metrics endpoint (default: 9090)
   * @return Result containing PrometheusMetrics instance
   */
  def start(port: Int = 9090): Result[PrometheusMetrics] = {
    try {
      val registry = new CollectorRegistry()
      val server   = new HTTPServer(new InetSocketAddress(port), registry)
      val metrics  = new PrometheusMetrics(registry, Some(server))

      org.slf4j.LoggerFactory
        .getLogger(getClass)
        .info(s"Prometheus metrics server started on port $port")

      Right(metrics)
    } catch {
      case e: Exception =>
        Left(
          org.llm4s.error.ConfigurationError(
            message = s"Failed to start Prometheus server on port $port: ${e.getMessage}"
          )
        )
    }
  }

  /**
   * Create a PrometheusMetrics instance without HTTP server.
   * Useful for testing or when exposing metrics through existing HTTP server.
   *
   * @return PrometheusMetrics instance
   */
  def create(): PrometheusMetrics = {
    val registry = new CollectorRegistry()
    new PrometheusMetrics(registry, None)
  }

  /**
   * Get the default global PrometheusMetrics instance.
   * Creates one if it doesn't exist yet.
   */
  @volatile private var globalInstance: Option[PrometheusMetrics] = None

  def default: PrometheusMetrics = {
    globalInstance.getOrElse {
      synchronized {
        globalInstance.getOrElse {
          val metrics = create()
          globalInstance = Some(metrics)
          metrics
        }
      }
    }
  }
}
