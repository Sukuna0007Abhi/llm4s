package org.llm4s.imageprocessing.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.nio.charset.StandardCharsets
import scala.util.Try

import ch.qos.logback.classic.{ Logger => LBLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

class VisionClientsSpec extends AnyFunSuite with Matchers {

  @scala.annotation.nowarn
  private def withTestServer(
    port: Int = 0,
    delayMs: Long = 0L,
    status: Int = 500,
    body: String = "error"
  )(f: Int => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext(
      "/chat/completions",
      new HttpHandler {
        override def handle(t: HttpExchange): Unit = {
          if (delayMs > 0) Thread.sleep(delayMs)
          val resp = body.getBytes(StandardCharsets.UTF_8)
          t.getResponseHeaders.add("Content-Type", "application/json")
          t.sendResponseHeaders(status, resp.length)
          val os = t.getResponseBody
          os.write(resp)
          os.close()
        }
      }
    )
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    try f(server.getAddress.getPort)
    finally
      server.stop(0)
  }

  test("OpenAIVisionClient: non-200 -> Failure and log body is truncated") {
    val longBody = "E" * 5000
    withTestServer(0, status = 500, body = longBody) { port =>
      val cfg = org.llm4s.imageprocessing.config.OpenAIVisionConfig(
        apiKey = "x",
        baseUrl = s"http://localhost:$port",
        requestTimeoutSeconds = 1,
        connectTimeoutSeconds = 1
      )
      val client = new org.llm4s.imageprocessing.provider.OpenAIVisionClient(cfg)

      val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LBLogger]
      val appender   = new ListAppender[ILoggingEvent]()
      appender.start()
      rootLogger.addAppender(appender)

      val method = client.getClass.getDeclaredMethod(
        "callOpenAIVisionAPI",
        classOf[String],
        classOf[String],
        classOf[org.llm4s.imageprocessing.MediaType]
      )
      method.setAccessible(true)

      val reflectResult = Try(method.invoke(client, "AAA", "prompt", org.llm4s.imageprocessing.MediaType.Jpeg))
      reflectResult.isSuccess shouldBe true

      val returned = reflectResult.get.asInstanceOf[scala.util.Try[String]]
      // the API wrapper should return a failed Try for non-200 responses
      returned.isFailure shouldBe true

      // the error body must not be logged in full — the log should contain a truncated marker
      val logged =
        appender.list.toArray.map(_.asInstanceOf[ch.qos.logback.classic.spi.ILoggingEvent].getFormattedMessage)
      logged.exists(_.contains("(truncated, original length:")) shouldBe true
    }
  }

}
