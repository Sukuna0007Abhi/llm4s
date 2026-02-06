package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig }
import org.llm4s.metrics.MockMetricsCollector
import org.scalatest.flatspec.AnyFlatSpec

class OpenAIClientSpec extends AnyFlatSpec {
  "OpenAIClient with OpenAI config" should "accept custom metrics collector" in {
    val mockMetrics = new MockMetricsCollector()
    val config      = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")

    // This creates a client instance with the mock metrics
    val client = new OpenAIClient(config, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0) // No requests made yet
  }

  it should "use noop metrics by default" in {
    val config = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client != null)
  }

  it should "return positive context window" in {
    val config = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client.getContextWindow() > 0)
  }

  it should "return positive reserve completion" in {
    val config = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client.getReserveCompletion() > 0)
  }

  it should "return custom reserve completion when configured" in {
    val config = OpenAIConfig
      .fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
      .copy(reserveCompletion = 1024)
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client.getReserveCompletion() == 1024)
  }

  "OpenAIClient with Azure config" should "accept custom metrics collector" in {
    val mockMetrics = new MockMetricsCollector()
    val config = AzureConfig.fromValues(
      "https://example.invalid",
      "test-key",
      "gpt-4",
      "V2024_06_01"
    )

    val client = new OpenAIClient(config, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0)
  }

  it should "use noop metrics by default" in {
    val config = AzureConfig.fromValues(
      "https://example.invalid",
      "test-key",
      "gpt-4",
      "V2024_06_01"
    )
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client != null)
  }

  it should "return positive context window" in {
    val config = AzureConfig.fromValues(
      "https://example.invalid",
      "test-key",
      "gpt-4",
      "V2024_06_01"
    )
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client.getContextWindow() > 0)
  }

  it should "return configured reserve completion" in {
    val config = AzureConfig
      .fromValues(
        "https://example.invalid",
        "test-key",
        "gpt-4",
        "V2024_06_01"
      )
      .copy(reserveCompletion = 512)
    val client = new OpenAIClient(config, org.llm4s.metrics.MetricsCollector.noop)

    assert(client.getReserveCompletion() == 512)
  }

  "OpenAIClient factory methods" should "create client for OpenAI config" in {
    val config      = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val mockMetrics = new MockMetricsCollector()

    val client = new OpenAIClient(config, mockMetrics)

    assert(client != null)
  }

  it should "create client for Azure config" in {
    val config = AzureConfig.fromValues(
      "https://example.invalid",
      "test-key",
      "gpt-4",
      "V2024_06_01"
    )
    val mockMetrics = new MockMetricsCollector()

    val client = new OpenAIClient(config, mockMetrics)

    assert(client != null)
  }

  "OpenAIClient.apply" should "create client successfully with OpenAI config" in {
    val config = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")

    val result = OpenAIClient(config)

    assert(result.isRight)
    result.foreach(client => assert(client != null))
  }

  it should "create client successfully with Azure config" in {
    val config = AzureConfig.fromValues(
      "https://example.invalid",
      "test-key",
      "gpt-4",
      "V2024_06_01"
    )

    val result = OpenAIClient(config)

    assert(result.isRight)
    result.foreach(client => assert(client != null))
  }

  it should "create client with custom metrics collector" in {
    val config      = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val mockMetrics = new MockMetricsCollector()

    val result = OpenAIClient(config, mockMetrics)

    assert(result.isRight)
    result.foreach { client =>
      assert(client != null)
      assert(mockMetrics.totalRequests == 0)
    }
  }
}
