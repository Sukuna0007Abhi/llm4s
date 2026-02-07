package org.llm4s.trace

import org.slf4j.LoggerFactory
import scala.util.{ Try, Success, Failure }

/**
 * Abstraction for sending trace events to Langfuse in batches.
 *
 * Implementations handle the HTTP communication with the Langfuse API,
 * including authentication, error handling, and logging.
 *
 * @see [[DefaultLangfuseBatchSender]] for the standard implementation
 */
trait LangfuseBatchSender {

  /**
   * Sends a batch of trace events to Langfuse.
   *
   * @param events Sequence of JSON objects representing trace events
   * @param config HTTP API configuration including URL and credentials
   */
  def sendBatch(events: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit
}

/**
 * Configuration for calling the Langfuse HTTP API.
 *
 * @param langfuseUrl Base URL of the Langfuse API endpoint
 * @param publicKey Langfuse public key for authentication
 * @param secretKey Langfuse secret key for authentication
 */
case class LangfuseHttpApiCaller(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String
)

/**
 * Default implementation of [[LangfuseBatchSender]] using HTTP requests.
 *
 * Sends trace events to Langfuse using basic authentication with
 * the provided public and secret keys. Handles both successful
 * responses (200-299) and partial success responses (207).
 *
 * Logs warnings if credentials are not configured and errors
 * if the HTTP request fails.
 */
class DefaultLangfuseBatchSender extends LangfuseBatchSender {
  private val logger = LoggerFactory.getLogger(getClass)

  override def sendBatch(events: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit = {
    if (config.publicKey.isEmpty || config.secretKey.isEmpty) {
      logger.warn("[Langfuse] Public or secret key not set in environment. Skipping export.")
      logger.warn(s"[Langfuse] Expected environment variables: LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY")
      logger.warn(s"[Langfuse] Current URL: ${config.langfuseUrl}")
      return
    }

    logger.debug(s"[Langfuse] Sending batch to URL: ${config.langfuseUrl}")
    logger.debug(s"[Langfuse] Using public key: ${config.publicKey.take(10)}...")
    logger.debug(s"[Langfuse] Events in batch: ${events.length}")

    val batchPayload = ujson.Obj("batch" -> ujson.Arr(events: _*))

    Try {
      val response = requests.post(
        config.langfuseUrl,
        data = batchPayload.render(),
        headers = Map(
          "Content-Type" -> "application/json",
          "User-Agent"   -> "llm4s-scala/1.0.0"
        ),
        auth = (config.publicKey, config.secretKey),
        readTimeout = 30000,
        connectTimeout = 30000
      )

      if (response.statusCode == 207 || (response.statusCode >= 200 && response.statusCode < 300)) {
        logger.info(s"[Langfuse] Batch export successful: ${response.statusCode}")
        if (response.statusCode == 207) {
          logger.info(s"[Langfuse] Partial success response: ${response.text()}")
        }
      } else {
        logger.error(s"[Langfuse] Batch export failed: ${response.statusCode}")
        logger.error(s"[Langfuse] Response body: ${org.llm4s.util.Redaction.truncateForLog(response.text())}")
        logger.error(s"[Langfuse] Request URL: ${config.langfuseUrl}")
        logger.error(s"[Langfuse] Request payload size: ${batchPayload.render().length} bytes")
      }
    } match {
      case Failure(e) =>
        logger.error(s"[Langfuse] Batch export failed with exception: ${e.getMessage}", e)
        logger.error(s"[Langfuse] Request URL: ${config.langfuseUrl}")
      case Success(_) =>
    }
  }
}
