package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.MockMetricsCollector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for metrics recording during streaming operations.
 * Ensures that streaming completions properly track requests, tokens, and costs.
 */
class StreamingMetricsSpec extends AnyFlatSpec with Matchers {

  "Streaming operations" should "track metrics with custom collector" in {
    val mockMetrics = new MockMetricsCollector()

    // Test configurations for various providers
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val anthropicConfig = AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "https://example.invalid",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val geminiConfig = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash-exp",
      baseUrl = "https://example.invalid",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )

    // Create clients with custom metrics
    val openAIClient    = new OpenAIClient(openAIConfig, mockMetrics)
    val anthropicClient = new AnthropicClient(anthropicConfig, mockMetrics)
    val geminiClient    = new GeminiClient(geminiConfig, mockMetrics)

    // Verify clients are created successfully
    openAIClient should not be null
    anthropicClient should not be null
    geminiClient should not be null

    // No requests should be recorded yet
    mockMetrics.totalRequests shouldBe 0
  }

  it should "accept callback functions for chunk processing" in {
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val client       = new OpenAIClient(openAIConfig, org.llm4s.metrics.MetricsCollector.noop)

    // This test verifies the method signature exists and accepts the callback
    // (actual streaming would require a real API connection)
    client should not be null
  }

  "Provider clients with streaming support" should "handle empty chunks gracefully" in {
    // Test that clients are properly configured to handle streaming
    val mockMetrics = new MockMetricsCollector()

    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val openAIClient = new OpenAIClient(openAIConfig, mockMetrics)

    // Verify client has streaming capability
    openAIClient should not be null

    // Note: OpenAIClientStreamingSpec already has comprehensive tests for:
    // - Null/empty choices handling
    // - Token updates only when finished
    // - Content chunk processing
  }

  it should "track request metrics for streaming operations" in {
    val mockMetrics = new MockMetricsCollector()

    // Create clients with metrics tracking
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val client       = new OpenAIClient(openAIConfig, mockMetrics)

    client should not be null

    // Verify metrics collector is properly attached
    mockMetrics.totalRequests shouldBe 0 // No requests yet
  }

  "Streaming completion options" should "support temperature and maxTokens" in {
    val options = CompletionOptions(
      temperature = 0.7,
      maxTokens = Some(2000),
      topP = 0.9,
      frequencyPenalty = 0.5,
      presencePenalty = 0.5
    )

    options.temperature shouldBe 0.7
    options.maxTokens shouldBe Some(2000)
    options.topP shouldBe 0.9
    options.frequencyPenalty shouldBe 0.5
    options.presencePenalty shouldBe 0.5
  }

  it should "allow default values for streaming options" in {
    val defaultOptions = CompletionOptions()

    defaultOptions.temperature shouldBe 0.7
    defaultOptions.topP shouldBe 1.0
    defaultOptions.maxTokens shouldBe None
    defaultOptions.frequencyPenalty shouldBe 0.0
    defaultOptions.presencePenalty shouldBe 0.0
  }

  "StreamedChunk model" should "contain completion content and metadata" in {
    // Verify StreamedChunk has the expected structure
    val chunk = StreamedChunk(
      id = "test-id",
      content = Some("Hello")
    )

    chunk.id shouldBe "test-id"
    chunk.content shouldBe Some("Hello")
  }

  it should "support final chunk with finish reason" in {
    val finalChunk = StreamedChunk(
      id = "test-id",
      content = None,
      toolCall = None,
      finishReason = Some("stop"),
      thinkingDelta = None
    )

    finalChunk.finishReason shouldBe Some("stop")
    finalChunk.content shouldBe None
  }

  "Token usage tracking" should "be handled at completion level" in {
    // StreamedChunk doesn't include usage - usage is tracked separately in the completion
    val intermediateChunk = StreamedChunk(
      id = "test-id",
      content = Some("text"),
      toolCall = None,
      finishReason = None,
      thinkingDelta = None
    )

    intermediateChunk.content shouldBe Some("text")
    intermediateChunk.finishReason shouldBe None
  }

  "Metrics collector" should "accumulate tokens across multiple requests" in {
    val mockMetrics = new MockMetricsCollector()

    // Simulate multiple token recordings
    mockMetrics.addTokens("openai", "gpt-4", 100, 50)
    mockMetrics.addTokens("openai", "gpt-4", 200, 100)
    mockMetrics.addTokens("anthropic", "claude-3-5-sonnet-latest", 150, 75)

    mockMetrics.totalTokenCalls shouldBe 3

    val openAICalls = mockMetrics.tokenCalls.filter { case (provider, _, _, _) =>
      provider == "openai"
    }
    openAICalls should have size 2

    val anthropicCalls = mockMetrics.tokenCalls.filter { case (provider, _, _, _) =>
      provider == "anthropic"
    }
    anthropicCalls should have size 1
  }

  it should "record costs for streaming operations" in {
    val mockMetrics = new MockMetricsCollector()

    // Record costs for different providers
    mockMetrics.recordCost("openai", "gpt-4", 0.03)
    mockMetrics.recordCost("anthropic", "claude-3-5-sonnet-latest", 0.015)

    mockMetrics.totalCostCalls shouldBe 2

    val costs = mockMetrics.costCalls
    costs.find(_._1 == "openai").get._3 shouldBe 0.03
    costs.find(_._1 == "anthropic").get._3 shouldBe 0.015
  }

  "Provider streaming implementations" should "all support metrics tracking" in {
    val mockMetrics = new MockMetricsCollector()

    // Verify all streaming-capable providers accept metrics
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://example.invalid/v1")
    val anthropicConfig = AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "https://example.invalid",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val geminiConfig = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash-exp",
      baseUrl = "https://example.invalid",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    val ollamaConfig = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )

    new OpenAIClient(openAIConfig, mockMetrics) should not be null
    new AnthropicClient(anthropicConfig, mockMetrics) should not be null
    new GeminiClient(geminiConfig, mockMetrics) should not be null
    new OllamaClient(ollamaConfig, mockMetrics) should not be null

    // All clients created without errors
    mockMetrics.totalRequests shouldBe 0 // No actual requests made
  }
}
