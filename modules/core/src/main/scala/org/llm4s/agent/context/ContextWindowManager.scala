package org.llm4s.agent.context

import org.llm4s.agent.{ Agent, AgentState, ContextWindowConfig }
import org.llm4s.types.Result

/**
 * High-level API for managing conversation context windows.
 *
 * Provides convenience methods that wrap AgentState's lower-level pruning functions
 * with a more discoverable and structured configuration API.
 */
object ContextWindowManager {

  /**
   * Prune conversation history based on configuration.
   *
   * This is a convenience wrapper around AgentState.pruneConversation() with
   * a default token counter.
   *
   * @param state The current agent state with conversation history
   * @param config Configuration for context window management
   * @return New AgentState with pruned conversation
   */
  def pruneConversation(
    state: AgentState,
    config: ContextWindowConfig
  ): AgentState =
    AgentState.pruneConversation(state, config, TokenCounter.default)

  /**
   * Continue a conversation with context management handled by continueConversation.
   *
   * Agent.continueConversation() already handles pruning via contextWindowConfig parameter.
   * This is a convenience method with simpler signature for common cases.
   *
   * @param agent The agent to use for continuation
   * @param state Current agent state
   * @param userMessage New user message to append
   * @param config Context window configuration
   * @return Result with new AgentState and response
   */
  def continueWithPruning(
    agent: Agent,
    state: AgentState,
    userMessage: String,
    config: ContextWindowConfig
  ): Result[AgentState] =
    // continueConversation handles pruning internally via contextWindowConfig
    agent.continueConversation(
      previousState = state,
      newUserMessage = userMessage,
      contextWindowConfig = Some(config)
    )

  /**
   * Run multiple conversation turns with automatic pruning.
   *
   * Applies context management for each turn to maintain window constraints.
   *
   * @param agent The agent to use
   * @param initialState Starting agent state
   * @param userMessages Sequence of user messages
   * @param config Context window configuration
   * @return Result with final AgentState after all turns
   */
  def runMultiTurnWithPruning(
    agent: Agent,
    initialState: AgentState,
    userMessages: List[String],
    config: ContextWindowConfig
  ): Result[AgentState] =
    userMessages.foldLeft[Result[AgentState]](Right(initialState)) { (resultState, message) =>
      resultState.flatMap { state =>
        agent.continueConversation(
          previousState = state,
          newUserMessage = message,
          contextWindowConfig = Some(config)
        )
      }
    }

  /**
   * Check if conversation needs pruning based on config.
   *
   * Returns true if either token limit or message limit would be exceeded.
   *
   * @param state Current agent state
   * @param config Context window configuration
   * @param tokenCounter Token counter function to use
   * @return True if pruning is needed
   */
  def needsPruning(
    state: AgentState,
    config: ContextWindowConfig,
    tokenCounter: org.llm4s.llmconnect.model.Message => Int = TokenCounter.default
  ): Boolean = {
    val messages = state.conversation.messages

    // Check message count
    val exceedsMessageLimit = config.maxMessages.exists(limit => messages.length > limit)

    // Check token count if configured
    val exceedsTokenLimit = config.maxTokens.exists { _ =>
      val totalTokens = messages.map(tokenCounter).sum
      val limit       = config.maxTokens.get
      totalTokens > limit
    }

    exceedsMessageLimit || exceedsTokenLimit
  }

  /**
   * Get current context window statistics.
   *
   * Useful for monitoring and debugging context management.
   *
   * @param state Current agent state
   * @param config Context window configuration
   * @param tokenCounter Token counter function to use
   * @return Statistics about current context usage
   */
  def getStats(
    state: AgentState,
    config: ContextWindowConfig,
    tokenCounter: org.llm4s.llmconnect.model.Message => Int = TokenCounter.default
  ): ContextStats = {
    val messages    = state.conversation.messages
    val totalTokens = messages.map(tokenCounter).sum

    ContextStats(
      messageCount = messages.length,
      tokenCount = totalTokens,
      maxMessages = config.maxMessages,
      maxTokens = config.maxTokens,
      needsPruning = needsPruning(state, config, tokenCounter)
    )
  }
}

/**
 * Statistics about current context window usage.
 *
 * @param messageCount Current number of messages
 * @param tokenCount Estimated total token count
 * @param maxMessages Configured max messages (if set)
 * @param maxTokens Configured max tokens (if set)
 * @param needsPruning Whether pruning is currently needed
 */
case class ContextStats(
  messageCount: Int,
  tokenCount: Int,
  maxMessages: Option[Int],
  maxTokens: Option[Int],
  needsPruning: Boolean
) {

  /**
   * Calculate utilization percentage for messages.
   * Returns None if no max is configured.
   */
  def messageUtilization: Option[Double] =
    maxMessages.map(max => (messageCount.toDouble / max) * 100)

  /**
   * Calculate utilization percentage for tokens.
   * Returns None if no max is configured.
   */
  def tokenUtilization: Option[Double] =
    maxTokens.map(max => (tokenCount.toDouble / max) * 100)

  override def toString: String = {
    val msgInfo = maxMessages.map(max => s"$messageCount/$max").getOrElse(s"$messageCount")
    val tokInfo = maxTokens.map(max => s"$tokenCount/$max").getOrElse(s"$tokenCount")
    s"ContextStats(messages=$msgInfo, tokens=$tokInfo, needsPruning=$needsPruning)"
  }
}
