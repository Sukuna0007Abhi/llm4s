package org.llm4s.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer
import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress

/**
 * HTTP endpoint for exposing Prometheus metrics.
 *
 * Wraps the Prometheus HTTPServer and provides lifecycle management.
 * Use this to expose metrics at /metrics for Prometheus scraping.
 *
 * Example:
 * {{{
 * val registry = new CollectorRegistry()
 * val endpointResult = PrometheusEndpoint.start(9090, registry)
 * 
 * endpointResult match {
 *   case Right(endpoint) =>
 *     println(s"Metrics at: \${endpoint.url}")
 *     // ... application runs ...
 *     endpoint.stop()
 *   case Left(error) =>
 *     println(s"Failed to start: \${error.message}")
 * }
 * }}}
 *
 * @param server Underlying HTTP server
 * @param port Port the server is listening on
 */
final class PrometheusEndpoint private (
  private val server: HTTPServer,
  val port: Int
) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Get the metrics endpoint URL.
   */
  def url: String = s"http://localhost:$port/metrics"

  /**
   * Stop the HTTP server.
   * Safe to call multiple times.
   */
  def stop(): Unit = {
    try {
      logger.info(s"Stopping Prometheus metrics endpoint on port $port")
      server.close()
    } catch {
      case e: Exception =>
        logger.warn(s"Error stopping Prometheus endpoint: ${e.getMessage}")
    }
  }
}

object PrometheusEndpoint {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Start Prometheus HTTP endpoint.
   *
   * Creates an HTTP server that exposes the metrics from the given registry
   * at the /metrics endpoint on the specified port.
   *
   * @param port Port to listen on (default: 9090)
   * @param registry Prometheus collector registry containing metrics
   * @return Right(endpoint) on success, Left(error) if port unavailable or other failure
   */
  def start(port: Int, registry: CollectorRegistry): Result[PrometheusEndpoint] = {
    try {
      val server = new HTTPServer(new InetSocketAddress(port), registry)
      val endpoint = new PrometheusEndpoint(server, port)
      
      logger.info(s"Prometheus metrics endpoint started: ${endpoint.url}")
      
      Right(endpoint)
    } catch {
      case e: java.net.BindException =>
        Left(ConfigurationError(
          s"Failed to start Prometheus endpoint on port $port: Port already in use. " +
          s"Make sure no other service is using this port."
        ))
      case e: Exception =>
        Left(ConfigurationError(
          s"Failed to start Prometheus endpoint on port $port: ${e.getMessage}"
        ))
    }
  }
}
