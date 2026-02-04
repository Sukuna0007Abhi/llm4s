package org.llm4s.agent.context

import org.llm4s.llmconnect.model.Message

/**
 * Token counting utilities for context window management.
 *
 * Provides various token counting strategies with different accuracy/performance trade-offs.
 */
object TokenCounter {

  /**
   * Default token counter using a simple word-based estimate.
   *
   * This is a rough approximation: words * 1.3
   * Good enough for most use cases without external dependencies.
   *
   * @param message The message to count tokens for
   * @return Estimated token count
   */
  def default(message: Message): Int = {
    val words = message.content.split("\\s+").length
    (words * 1.3).toInt
  }

  /**
   * Conservative token counter that overestimates.
   *
   * Uses words * 1.5 to provide a safety buffer.
   * Better to underestimate available space than overflow.
   *
   * @param message The message to count tokens for
   * @return Estimated token count (conservative)
   */
  def conservative(message: Message): Int = {
    val words = message.content.split("\\s+").length
    (words * 1.5).toInt
  }

  /**
   * Character-based token counter.
   *
   * Uses characters / 4 as estimate (typical for many tokenizers).
   * More accurate than word-based for code or technical text.
   *
   * @param message The message to count tokens for
   * @return Estimated token count
   */
  def characterBased(message: Message): Int =
    message.content.length / 4

  /**
   * Creates a custom token counter from a function.
   *
   * Use this to integrate with actual tokenizers like tiktoken.
   *
   * {{{
   * val myCounter = TokenCounter.custom { message =>
   *   // Use actual tokenizer
   *   tiktoken.encode(message.content).length
   * }
   * }}}
   *
   * @param fn Function that counts tokens for a message
   * @return Custom token counter
   */
  def custom(fn: Message => Int): Message => Int = fn

  /**
   * No-op counter that always returns 0.
   *
   * Useful when you only want message-based pruning, not token-based.
   */
  val noop: Message => Int = _ => 0
}
