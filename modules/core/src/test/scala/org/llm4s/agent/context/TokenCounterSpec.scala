package org.llm4s.agent.context

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.{ UserMessage, AssistantMessage }

class TokenCounterSpec extends AnyFlatSpec with Matchers {

  "TokenCounter.default" should "estimate tokens based on words * 1.3" in {
    val message = UserMessage("Hello world")
    TokenCounter.default(message) shouldBe 2 // 2 words * 1.3 = 2.6 -> 2
  }

  it should "handle longer text" in {
    val message = UserMessage("This is a longer message with ten words total")
    TokenCounter.default(message) shouldBe 11 // 9 words * 1.3 = 11.7 -> 11
  }

  it should "handle empty messages" in {
    val message = UserMessage("")
    TokenCounter.default(message) shouldBe 1 // 1 word (empty string has split length 1) * 1.3 = 1.3 -> 1
  }

  "TokenCounter.conservative" should "overestimate with words * 1.5" in {
    val message = UserMessage("Hello world")
    TokenCounter.conservative(message) shouldBe 3 // 2 words * 1.5 = 3
  }

  "TokenCounter.characterBased" should "use characters / 4" in {
    val message = UserMessage("test") // 4 characters
    TokenCounter.characterBased(message) shouldBe 1

    val longer = UserMessage("This is a test message") // 22 characters
    TokenCounter.characterBased(longer) shouldBe 5
  }

  "TokenCounter.custom" should "use provided function" in {
    val counter = TokenCounter.custom(msg => msg.content.length)
    val message = UserMessage("hello")
    counter(message) shouldBe 5
  }

  "TokenCounter.noop" should "always return 0" in {
    val message = UserMessage("This message has content but counter returns 0")
    TokenCounter.noop(message) shouldBe 0
  }

  it should "work with any message type" in {
    TokenCounter.default(UserMessage("user message")) should be > 0
    TokenCounter.default(AssistantMessage(Some("assistant message"))) should be > 0
  }
}
