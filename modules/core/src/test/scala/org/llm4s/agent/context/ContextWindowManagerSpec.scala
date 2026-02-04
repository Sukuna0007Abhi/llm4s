package org.llm4s.agent.context

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.{ AgentState, ContextWindowConfig }
import org.llm4s.llmconnect.model.{ Conversation, UserMessage, AssistantMessage }

class ContextWindowManagerSpec extends AnyFlatSpec with Matchers {

  "ContextWindowManager.pruneConversation" should "prune when message limit exceeded" in {
    val state = AgentState(
      conversation = Conversation(messages =
        List(
          UserMessage("Message 1"),
          AssistantMessage(Some("Response 1")),
          UserMessage("Message 2"),
          AssistantMessage(Some("Response 2")),
          UserMessage("Message 3"),
          AssistantMessage(Some("Response 3"))
        )
      ),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig(maxMessages = Some(4))
    val pruned = ContextWindowManager.pruneConversation(state, config)

    pruned.conversation.messages.length should be <= 4
  }

  "ContextWindowManager.needsPruning" should "detect when message limit exceeded" in {
    val state = AgentState(
      conversation = Conversation(messages =
        List(
          UserMessage("1"),
          UserMessage("2"),
          UserMessage("3"),
          UserMessage("4"),
          UserMessage("5"),
          UserMessage("6")
        )
      ),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig(maxMessages = Some(4))
    ContextWindowManager.needsPruning(state, config) shouldBe true
  }

  it should "return false when within limits" in {
    val state = AgentState(
      conversation = Conversation(messages =
        List(
          UserMessage("1"),
          UserMessage("2")
        )
      ),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig(maxMessages = Some(10))
    ContextWindowManager.needsPruning(state, config) shouldBe false
  }

  it should "detect when token limit exceeded" in {
    val state = AgentState(
      conversation = Conversation(messages =
        List.fill(20)(
          UserMessage("This is a test message with many words to exceed token limits")
        )
      ),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig(maxTokens = Some(50))
    ContextWindowManager.needsPruning(state, config) shouldBe true
  }

  "ContextWindowManager.getStats" should "provide accurate statistics" in {
    val state = AgentState(
      conversation = Conversation(messages =
        List(
          UserMessage("Hello"),
          AssistantMessage(Some("Hi there")),
          UserMessage("How are you")
        )
      ),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig(maxMessages = Some(10), maxTokens = Some(100))
    val stats  = ContextWindowManager.getStats(state, config)

    stats.messageCount shouldBe 3
    stats.tokenCount should be > 0
    stats.maxMessages shouldBe Some(10)
    stats.maxTokens shouldBe Some(100)
  }

  it should "calculate message utilization" in {
    val state = AgentState(
      conversation = Conversation(messages = List.fill(5)(UserMessage("test"))),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig(maxMessages = Some(10))
    val stats  = ContextWindowManager.getStats(state, config)

    stats.messageUtilization shouldBe Some(50.0)
  }

  it should "handle configs without limits" in {
    val state = AgentState(
      conversation = Conversation(messages = List(UserMessage("test"))),
      tools = org.llm4s.toolapi.ToolRegistry.empty
    )

    val config = ContextWindowConfig()
    val stats  = ContextWindowManager.getStats(state, config)

    stats.messageUtilization shouldBe None
    stats.tokenUtilization shouldBe None
    stats.needsPruning shouldBe false
  }

  "ContextStats.toString" should "format nicely" in {
    val stats = ContextStats(
      messageCount = 5,
      tokenCount = 150,
      maxMessages = Some(10),
      maxTokens = Some(200),
      needsPruning = false
    )

    stats.toString should include("5/10")
    stats.toString should include("150/200")
    stats.toString should include("needsPruning=false")
  }
}
