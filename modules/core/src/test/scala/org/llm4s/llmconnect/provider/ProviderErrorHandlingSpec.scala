package org.llm4s.llmconnect.provider

import org.llm4s.error._
import org.llm4s.llmconnect.config._
import org.llm4s.metrics.{ ErrorKind, MockMetricsCollector }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Comprehensive tests for error handling across all provider clients.
 * Tests authentication errors, rate limits, and configuration validation.
 */
class ProviderErrorHandlingSpec extends AnyFlatSpec with Matchers {

  "Provider clients" should "accept metrics collector for error tracking" in {
    val mockMetrics = new MockMetricsCollector()

    // Test with OpenAI client
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://api.openai.com/v1")
    val openAIClient = new OpenAIClient(openAIConfig, mockMetrics)

    openAIClient should not be null
    mockMetrics.totalRequests shouldBe 0 // No requests yet

    // Test with Anthropic client
    val anthropicConfig = AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val anthropicClient = new AnthropicClient(anthropicConfig, mockMetrics)

    anthropicClient should not be null
  }

  it should "properly configure error handling for all providers" in {
    val mockMetrics = new MockMetricsCollector()

    // OpenAI
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://api.openai.com/v1")
    val openAIClient = new OpenAIClient(openAIConfig, mockMetrics)
    openAIClient should not be null

    // Anthropic
    val anthropicConfig = AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val anthropicClient = new AnthropicClient(anthropicConfig, mockMetrics)
    anthropicClient should not be null

    // Gemini
    val geminiConfig = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash-exp",
      baseUrl = "https://generativelanguage.googleapis.com",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    val geminiClient = new GeminiClient(geminiConfig, mockMetrics)
    geminiClient should not be null

    // Ollama
    val ollamaConfig = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )
    val ollamaClient = new OllamaClient(ollamaConfig, mockMetrics)
    ollamaClient should not be null

    // OpenRouter
    val openRouterConfig = OpenAIConfig(
      apiKey = "test-key",
      model = "anthropic/claude-3.5-sonnet",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val openRouterClient = new OpenRouterClient(openRouterConfig, mockMetrics)
    openRouterClient should not be null

    // Zai
    val zaiConfig = ZaiConfig(
      apiKey = "test-key",
      model = "GLM-4.7",
      baseUrl = "https://api.z.ai/api/paas/v4",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val zaiClient = new ZaiClient(zaiConfig, mockMetrics)
    zaiClient should not be null
  }

  "Error types" should "be distinguishable by ErrorKind" in {
    val authError = AuthenticationError("openai", "Invalid API key")
    ErrorKind.fromLLMError(authError) shouldBe ErrorKind.Authentication

    val rateLimit = RateLimitError("anthropic")
    ErrorKind.fromLLMError(rateLimit) shouldBe ErrorKind.RateLimit

    val validationError = ValidationError("model", "invalid value")
    ErrorKind.fromLLMError(validationError) shouldBe ErrorKind.Validation
  }

  "MetricsCollector" should "track different error types separately" in {
    val mockMetrics = new MockMetricsCollector()

    // Simulate recording different error types
    mockMetrics.observeRequest(
      "openai",
      "gpt-4",
      org.llm4s.metrics.Outcome.Error(ErrorKind.Authentication),
      scala.concurrent.duration.Duration.Zero
    )

    mockMetrics.observeRequest(
      "anthropic",
      "claude-3-5-sonnet-latest",
      org.llm4s.metrics.Outcome.Error(ErrorKind.RateLimit),
      scala.concurrent.duration.Duration.Zero
    )

    mockMetrics.observeRequest(
      "gemini",
      "gemini-2.0-flash-exp",
      org.llm4s.metrics.Outcome.Error(ErrorKind.Unknown),
      scala.concurrent.duration.Duration.Zero
    )

    mockMetrics.totalRequests shouldBe 3
    mockMetrics.hasErrorRequest("openai", ErrorKind.Authentication) shouldBe true
    mockMetrics.hasErrorRequest("anthropic", ErrorKind.RateLimit) shouldBe true
    mockMetrics.hasErrorRequest("gemini", ErrorKind.Unknown) shouldBe true
  }

  "Provider configurations" should "validate required fields" in {
    // OpenAI config requires API key and model
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://api.openai.com/v1")
    openAIConfig.apiKey should not be empty
    openAIConfig.model should not be empty

    // Anthropic config requires API key and model
    val anthropicConfig = AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    anthropicConfig.apiKey should not be empty
    anthropicConfig.model should not be empty

    // Gemini config requires API key and model
    val geminiConfig = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash-exp",
      baseUrl = "https://generativelanguage.googleapis.com",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    geminiConfig.apiKey should not be empty
    geminiConfig.model should not be empty

    // Ollama config requires model and base URL
    val ollamaConfig = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )
    ollamaConfig.model should not be empty
    ollamaConfig.baseUrl should not be empty

    // Zai config requires API key and model
    val zaiConfig = ZaiConfig(
      apiKey = "test-key",
      model = "GLM-4.7",
      baseUrl = "https://api.z.ai/api/paas/v4",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    zaiConfig.apiKey should not be empty
    zaiConfig.model should not be empty
  }

  "Error messages" should "contain useful debugging information" in {
    val authError = AuthenticationError("openai", "Invalid API key")
    authError.message should include("openai")
    authError.message should include("Invalid API key")

    val rateError = RateLimitError("anthropic", 60)
    rateError.message should include("anthropic")

    val validationError = ValidationError("model", "must be non-empty")
    validationError.message should include("model")
    validationError.message should include("must be non-empty")
  }

  "Provider clients" should "use noop metrics when no collector provided" in {
    // All providers should accept default noop metrics
    val openAIConfig = OpenAIConfig.fromValues("gpt-4", "test-key", None, "https://api.openai.com/v1")
    val anthropicConfig = AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = "https://api.anthropic.com",
      contextWindow = 200000,
      reserveCompletion = 4096
    )
    val geminiConfig = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash-exp",
      baseUrl = "https://generativelanguage.googleapis.com",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    val ollamaConfig = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )
    val zaiConfig = ZaiConfig(
      apiKey = "test-key",
      model = "GLM-4.7",
      baseUrl = "https://api.z.ai/api/paas/v4",
      contextWindow = 128000,
      reserveCompletion = 4096
    )

    // These should all use noop metrics by default (no errors)
    new OpenAIClient(openAIConfig, org.llm4s.metrics.MetricsCollector.noop) should not be null
    new AnthropicClient(anthropicConfig, org.llm4s.metrics.MetricsCollector.noop) should not be null
    new GeminiClient(geminiConfig, org.llm4s.metrics.MetricsCollector.noop) should not be null
    new OllamaClient(ollamaConfig, org.llm4s.metrics.MetricsCollector.noop) should not be null
    new ZaiClient(zaiConfig, org.llm4s.metrics.MetricsCollector.noop) should not be null
  }

  "Error types" should "be created correctly and have proper recoverability" in {
    // Rate limit errors are recoverable (can retry after delay)
    val rateLimitError = RateLimitError("openai")
    rateLimitError should not be null
    rateLimitError.message should include("openai")
    LLMError.isRecoverable(rateLimitError) shouldBe true

    // Authentication errors are non-recoverable (need to fix credentials)
    val authError = AuthenticationError("openai", "Invalid API key")
    authError should not be null
    authError.message should include("openai")
    LLMError.isRecoverable(authError) shouldBe false

    // Validation errors are non-recoverable (need to fix the input)
    val validationError = ValidationError("model", "Invalid request format")
    validationError should not be null
    validationError.message should include("model")
    LLMError.isRecoverable(validationError) shouldBe false
  }
}
