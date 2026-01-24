---
layout: page
title: Testing Guide
parent: Getting Started
nav_order: 6
---

# Testing LLM4S Applications
{: .no_toc }

Learn how to test LLM-powered applications effectively without spending money on API calls.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Testing Strategy

Testing LLM applications requires a different approach than traditional software. You need to balance:

1. **Speed** - Tests should run fast
2. **Cost** - Avoid expensive API calls in CI/CD
3. **Determinism** - LLM responses vary, so test behaviors not exact outputs
4. **Coverage** - Test error paths, timeouts, and edge cases

---

## Unit Testing with Mock Clients

For pure logic tests, mock the LLM client:

### Basic Mock

```scala
import org.llm4s.llmconnect.{LLMClient, CompletionResponse, CompletionRequest}
import org.llm4s.llmconnect.model.{Message, Usage}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WeatherAgentSpec extends AnyFlatSpec with Matchers {

  // Mock client that returns canned responses
  class MockLLMClient extends LLMClient {
    override def complete(request: CompletionRequest): Result[CompletionResponse] = {
      Right(CompletionResponse(
        content = "The weather in London is 15°C and cloudy.",
        finishReason = Some("stop"),
        usage = Some(Usage(promptTokens = 10, completionTokens = 15, totalTokens = 25)),
        model = "mock-model",
        role = "assistant"
      ))
    }

    override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
      Right(Iterator(
        CompletionResponse(content = "The weather", finishReason = None, model = "mock"),
        CompletionResponse(content = " is sunny", finishReason = Some("stop"), model = "mock")
      ))
    }
  }

  "WeatherAgent" should "extract city from user query" in {
    val agent = new WeatherAgent(new MockLLMClient)
    val result = agent.run("What's the weather in London?")
    
    result match {
      case Right(response) =>
        response.content should include("London")
        response.content should include("°C")
      case Left(error) =>
        fail(s"Expected success but got: $error")
    }
  }

  it should "handle errors gracefully" in {
    class FailingMockClient extends LLMClient {
      override def complete(request: CompletionRequest): Result[CompletionResponse] = {
        Left(LLMError.NetworkError("Connection timeout"))
      }
      override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
        Left(LLMError.NetworkError("Connection timeout"))
      }
    }

    val agent = new WeatherAgent(new FailingMockClient)
    val result = agent.run("What's the weather?")
    
    result match {
      case Left(LLMError.NetworkError(_)) => succeed
      case other => fail(s"Expected NetworkError but got: $other")
    }
  }
}
```

### Parameterized Mock

For more complex scenarios:

```scala
class ConfigurableMockClient(responses: Map[String, String]) extends LLMClient {
  override def complete(request: CompletionRequest): Result[CompletionResponse] = {
    val userMessage = request.messages.find(_.role == "user").map(_.content).getOrElse("")
    
    responses.get(userMessage) match {
      case Some(responseText) =>
        Right(CompletionResponse(
          content = responseText,
          finishReason = Some("stop"),
          model = "mock-model",
          role = "assistant"
        ))
      case None =>
        Left(LLMError.InvalidRequest(s"No mock response for: $userMessage"))
    }
  }

  override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
    complete(request).map(response => Iterator(response))
  }
}

// Usage in tests
val mockResponses = Map(
  "What is 2+2?" -> "2+2 equals 4",
  "What is the capital of France?" -> "The capital of France is Paris"
)

val client = new ConfigurableMockClient(mockResponses)
```

---

## Integration Testing with Ollama

For integration tests, use Ollama to avoid API costs:

### Setup

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull a small, fast model
ollama pull llama3.2

# Start server
ollama serve
```

### Test Configuration

```hocon
# src/test/resources/application.conf
llm4s {
  provider {
    model = "ollama/llama3.2"
    ollama {
      base-url = "http://localhost:11434"
    }
  }
  request-timeout = 30 seconds
}
```

### Integration Test Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMIntegrationSpec extends AnyFlatSpec with Matchers {

  // Only run if Ollama is available
  def ollamaAvailable: Boolean = {
    try {
      val url = new java.net.URL("http://localhost:11434")
      val connection = url.openConnection()
      connection.setConnectTimeout(1000)
      connection.connect()
      true
    } catch {
      case _: Exception => false
    }
  }

  "LLMClient" should "complete basic requests" in {
    assume(ollamaAvailable, "Ollama server not available")

    val result = for {
      config <- Llm4sConfig.provider()
      client <- LLMConnect.getClient(config)
      response <- client.complete(
        List(UserMessage("Say 'hello' and nothing else")),
        None
      )
    } yield response

    result match {
      case Right(response) =>
        response.content.toLowerCase should include("hello")
      case Left(error) =>
        fail(s"Request failed: $error")
    }
  }

  it should "handle streaming responses" in {
    assume(ollamaAvailable, "Ollama server not available")

    val result = for {
      config <- Llm4sConfig.provider()
      client <- LLMConnect.getClient(config)
      stream <- client.completeStreaming(
        List(UserMessage("Count: 1, 2, 3")),
        None
      )
    } yield stream

    result match {
      case Right(stream) =>
        val chunks = stream.toList
        chunks should not be empty
        chunks.last.finishReason should be(Some("stop"))
      case Left(error) =>
        fail(s"Streaming failed: $error")
    }
  }
}
```

---

## Testing Error Handling

Always test error paths:

```scala
class ErrorHandlingSpec extends AnyFlatSpec with Matchers {

  "Agent" should "handle rate limiting" in {
    class RateLimitedClient extends LLMClient {
      override def complete(request: CompletionRequest): Result[CompletionResponse] = {
        Left(LLMError.RateLimitError("Rate limit exceeded"))
      }
      override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
        Left(LLMError.RateLimitError("Rate limit exceeded"))
      }
    }

    val agent = new Agent(new RateLimitedClient)
    val result = agent.run("test query", tools = ToolRegistry.empty)

    result match {
      case Left(LLMError.RateLimitError(_)) => succeed
      case other => fail(s"Expected RateLimitError but got: $other")
    }
  }

  it should "handle authentication errors" in {
    class UnauthorizedClient extends LLMClient {
      override def complete(request: CompletionRequest): Result[CompletionResponse] = {
        Left(LLMError.AuthenticationError("Invalid API key"))
      }
      override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
        Left(LLMError.AuthenticationError("Invalid API key"))
      }
    }

    val agent = new Agent(new UnauthorizedClient)
    val result = agent.run("test", tools = ToolRegistry.empty)

    result.isLeft shouldBe true
  }

  it should "handle network timeouts" in {
    class TimeoutClient extends LLMClient {
      override def complete(request: CompletionRequest): Result[CompletionResponse] = {
        Thread.sleep(5000)  // Simulate timeout
        Left(LLMError.NetworkError("Request timeout"))
      }
      override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
        Left(LLMError.NetworkError("Request timeout"))
      }
    }

    val agent = new Agent(new TimeoutClient)
    val result = agent.run("test", tools = ToolRegistry.empty)

    result match {
      case Left(LLMError.NetworkError(_)) => succeed
      case other => fail(s"Expected NetworkError but got: $other")
    }
  }
}
```

---

## Testing Tool Calling

Test that tools are invoked correctly:

```scala
class ToolCallingSpec extends AnyFlatSpec with Matchers {

  "Agent" should "invoke weather tool" in {
    var toolWasCalled = false
    var capturedCity: Option[String] = None

    val weatherTool = new Tool {
      override def name: String = "get_weather"
      override def description: String = "Get weather for a city"
      override def parameters: ToolParameters = ToolParameters(
        properties = Map("city" -> Property("string", "City name"))
      )
      override def execute(args: Map[String, Any]): Result[String] = {
        toolWasCalled = true
        capturedCity = args.get("city").map(_.toString)
        Right(s"Weather in ${capturedCity.getOrElse("unknown")}: 20°C")
      }
    }

    // Mock client that calls the tool
    class ToolCallingMock extends LLMClient {
      override def complete(request: CompletionRequest): Result[CompletionResponse] = {
        Right(CompletionResponse(
          content = "",
          finishReason = Some("tool_calls"),
          toolCalls = Some(List(
            ToolCall(
              id = "call_1",
              name = "get_weather",
              arguments = Map("city" -> "London")
            )
          )),
          model = "mock",
          role = "assistant"
        ))
      }
      override def completeStreaming(request: CompletionRequest): Result[Iterator[CompletionResponse]] = {
        complete(request).map(Iterator(_))
      }
    }

    val tools = new ToolRegistry(List(weatherTool))
    val agent = new Agent(new ToolCallingMock)
    
    agent.run("What's the weather in London?", tools)

    toolWasCalled shouldBe true
    capturedCity shouldBe Some("London")
  }
}
```

---

## Testing RAG Applications

Test document retrieval and answer generation separately:

```scala
class RAGSpec extends AnyFlatSpec with Matchers {

  "VectorStore" should "retrieve relevant documents" in {
    val documents = List(
      "Scala is a functional programming language",
      "Python is a dynamically typed language",
      "Java runs on the JVM"
    )

    // Test retrieval without LLM
    val vectorStore = new InMemoryVectorStore()
    documents.foreach(doc => vectorStore.add(doc, embedder.embed(doc)))

    val results = vectorStore.search("functional programming", topK = 1)
    
    results.head should include("Scala")
  }

  "RAG pipeline" should "include context in LLM prompt" in {
    val mockClient = new MockLLMClient {
      var lastPrompt: Option[String] = None

      override def complete(request: CompletionRequest): Result[CompletionResponse] = {
        lastPrompt = request.messages.find(_.role == "user").map(_.content)
        Right(CompletionResponse(
          content = "Based on the context, Scala is functional.",
          finishReason = Some("stop"),
          model = "mock",
          role = "assistant"
        ))
      }
    }

    val rag = new RAGPipeline(mockClient, vectorStore, embedder)
    rag.query("What is Scala?")

    mockClient.lastPrompt.get should include("context")
    mockClient.lastPrompt.get should include("functional")
  }
}
```

---

## CI/CD Testing Strategy

### Fast CI Pipeline

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Scala
        uses: olafurpg/setup-scala@v14
        with:
          java-version: 21
      
      - name: Install Ollama
        run: |
          curl -fsSL https://ollama.com/install.sh | sh
          ollama serve &
          sleep 5
          ollama pull llama3.2
      
      - name: Run unit tests (fast)
        run: sbt "testOnly *UnitSpec"
      
      - name: Run integration tests (with Ollama)
        run: sbt "testOnly *IntegrationSpec"
        env:
          LLM_MODEL: ollama/llama3.2
      
      # Skip expensive tests in CI
      - name: Run full test suite
        run: sbt test
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
```

### Test Categorization

```scala
// Tag tests by speed/cost
import org.scalatest.Tag

object UnitTest extends Tag("UnitTest")
object IntegrationTest extends Tag("IntegrationTest")
object ExpensiveTest extends Tag("ExpensiveTest")

class FastSpec extends AnyFlatSpec {
  "Fast unit test" should "run in CI" taggedAs UnitTest in {
    // Mock-based test
  }
}

class SlowSpec extends AnyFlatSpec {
  "Expensive test" should "run manually" taggedAs ExpensiveTest in {
    // Uses real OpenAI API
  }
}
```

Run specific test categories:

```bash
# Fast tests only
sbt "testOnly * -- -n UnitTest"

# Everything except expensive tests
sbt "testOnly * -- -l ExpensiveTest"
```

---

## Best Practices

1. ✅ **Mock by default**: Use mock clients for unit tests
2. ✅ **Ollama for integration**: Free and fast enough for CI
3. ✅ **Test behaviors, not outputs**: LLM responses vary, so test that tools are called, documents are retrieved, etc.
4. ✅ **Use deterministic models when possible**: Set temperature=0 for more predictable outputs
5. ✅ **Separate concerns**: Test tool logic independently from LLM integration
6. ✅ **Tag expensive tests**: Don't run them in every CI build
7. ✅ **Use smaller models in CI**: llama3.2 is fast and free via Ollama

---

## Example Test Suite Structure

```
src/test/scala/
├── unit/
│   ├── ToolSpec.scala           # Pure logic tests (mocked)
│   ├── ConfigSpec.scala         # Configuration parsing
│   └── ErrorHandlingSpec.scala  # Error path tests
├── integration/
│   ├── LLMClientSpec.scala      # Real LLM calls (Ollama)
│   ├── AgentSpec.scala          # End-to-end agent tests
│   └── RAGSpec.scala            # RAG pipeline tests
└── resources/
    └── application.conf         # Test config (Ollama)
```

---

## Next Steps

- [Configuration Guide](configuration) - Configure test environments
- [Examples](/examples/) - See tests in action
- [CI/CD Best Practices](/advanced/ci-cd) - Production testing strategies

---

## Additional Resources

- [ScalaTest Documentation](https://www.scalatest.org/)
- [Ollama Models](https://ollama.com/library) - Available test models
- [Testing Guide](/reference/testing-guide) - Advanced testing patterns
