package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj, read }

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.Try

object VoyageAIEmbeddingProvider {

  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = new EmbeddingProvider {
    private val httpClient = HttpClient.newHttpClient()
    private val logger     = LoggerFactory.getLogger(getClass)

    override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
      val model = request.model.name
      val input = request.input
      val payload = Obj(
        "input" -> Arr.from(input),
        "model" -> model
      )

      val url = s"${cfg.baseUrl}/v1/embeddings"
      logger.debug(s"[VoyageAIEmbeddingProvider] POST $url model=$model inputs=${input.size}")

      val respEither: Either[EmbeddingError, HttpResponse[String]] =
        Try {
          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(url))
            .header("Authorization", s"Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofString(payload.render(), StandardCharsets.UTF_8))
            .build()

          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }.toEither.left
          .map(e =>
            EmbeddingError(code = Some("502"), message = s"HTTP request failed: ${e.getMessage}", provider = "voyage")
          )

      respEither.flatMap { response =>
        response.statusCode() match {
          case 200 =>
            Try {
              val json     = read(response.body())
              val vectors  = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
              val metadata = Map("provider" -> "voyage", "model" -> model, "count" -> input.size.toString)
              EmbeddingResponse(embeddings = vectors, metadata = metadata)
            }.toEither.left
              .map { ex =>
                logger.error(s"[VoyageAIEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = Some("502"), message = s"Parsing error: ${ex.getMessage}", provider = "voyage")
              }
          case status =>
            val errorMsg = response.body()
            logger.error(s"[VoyageAIEmbeddingProvider] HTTP error $status: $errorMsg")
            Left(EmbeddingError(code = Some(status.toString), message = errorMsg, provider = "voyage"))
        }
      }
    }
  }
}
