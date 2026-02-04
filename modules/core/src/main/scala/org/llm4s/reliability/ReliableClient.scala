package org.llm4s.reliability

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, Completion, CompletionOptions, StreamedChunk }
import org.llm4s.types.Result
import org.llm4s.error._
import org.llm4s.metrics.{ MetricsCollector, ErrorKind }

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

/**
 * Wrapper that adds reliability features to any LLMClient.
 *
 * Provides:
 * - Retry with configurable policies (exponential backoff, linear, fixed)
 * - Circuit breaker to fail fast when service is down
 * - Deadline enforcement to prevent hanging operations
 * - Metrics tracking for retry attempts and circuit breaker state
 *
 * @param underlying The client to wrap
 * @param config Reliability configuration
 * @param collector Optional metrics collector for observability
 */
final class ReliableClient(
  underlying: LLMClient,
  config: ReliabilityConfig,
  collector: Option[MetricsCollector] = None
) extends LLMClient {

  // Circuit breaker state (mutable but thread-safe via volatile)
  @volatile private var circuitState: CircuitState = CircuitState.Closed
  @volatile private var failureCount: Int          = 0
  @volatile private var successCount: Int          = 0
  @volatile private var lastFailureTime: Long      = 0L

  // LLMClient interface methods
  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] =
    if (!config.enabled) {
      underlying.complete(conversation, options)
    } else {
      executeWithReliability(() => underlying.complete(conversation, options))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    if (!config.enabled) {
      underlying.streamComplete(conversation, options, onChunk)
    } else {
      executeWithReliability(() => underlying.streamComplete(conversation, options, onChunk))
    }

  override def getContextWindow(): Int     = underlying.getContextWindow()
  override def getReserveCompletion(): Int = underlying.getReserveCompletion()
  override def validate(): Result[Unit]    = underlying.validate()
  override def close(): Unit               = underlying.close()

  // Helper to get provider name for metrics
  private def provider: String = underlying.getClass.getSimpleName.replace("Client", "").toLowerCase

  /**
   * Execute operation with retry, circuit breaker, and deadline.
   */
  private def executeWithReliability[A](operation: () => Result[A]): Result[A] = {
    // Check circuit breaker state
    checkCircuitBreaker() match {
      case Left(error) =>
        collector.foreach(_.recordError(ErrorKind.ServiceError, provider))
        return Left(error)
      case Right(_) => // Continue
    }

    // Apply deadline if configured
    val result = config.deadline match {
      case Some(deadline) =>
        executeWithDeadline(operation, deadline)
      case None =>
        executeWithRetry(operation, attemptNumber = 1)
    }

    // Update circuit breaker state based on result
    result match {
      case Right(_) =>
        onSuccess()
      case Left(_) =>
        onFailure()
    }

    result
  }

  /**
   * Execute operation with deadline enforcement.
   */
  private def executeWithDeadline[A](operation: () => Result[A], deadline: Duration): Result[A] = {
    val startTime           = System.currentTimeMillis()
    val deadlineMs          = startTime + deadline.toMillis
    var attempt             = 1
    var lastError: LLMError = null

    while (System.currentTimeMillis() < deadlineMs && attempt <= config.retryPolicy.maxAttempts)
      executeWithRetry(operation, attempt) match {
        case success @ Right(_) =>
          return success

        case Left(error) =>
          lastError = error
          val remainingTime = deadlineMs - System.currentTimeMillis()

          if (remainingTime <= 0) {
            collector.foreach(_.recordError(ErrorKind.Timeout, provider))
            return Left(
              TimeoutError(
                message = s"Operation exceeded deadline of ${deadline.toSeconds}s after $attempt attempts",
                timeoutDuration = deadline,
                operation = "reliable-client.complete"
              )
            )
          }

          if (attempt < config.retryPolicy.maxAttempts && config.retryPolicy.isRetryable(error)) {
            val delay       = config.retryPolicy.delayFor(attempt, error)
            val actualDelay = delay.min(Duration.fromNanos(remainingTime * 1000000))
            Thread.sleep(actualDelay.toMillis)
            attempt += 1
          } else {
            return Left(error)
          }
      }

    // Deadline exceeded
    collector.foreach(_.recordError(ErrorKind.Timeout, provider))
    Left(
      TimeoutError(
        message =
          s"Operation exceeded deadline of ${deadline.toSeconds}s after $attempt attempts. Last error: ${lastError.message}",
        timeoutDuration = deadline,
        operation = "reliable-client.complete"
      )
    )
  }

  /**
   * Execute operation with retry logic.
   */
  @tailrec
  private def executeWithRetry[A](operation: () => Result[A], attemptNumber: Int): Result[A] =
    operation() match {
      case success @ Right(_) =>
        success

      case Left(error) if attemptNumber < config.retryPolicy.maxAttempts && config.retryPolicy.isRetryable(error) =>
        // Record retry attempt
        collector.foreach(_.recordRetryAttempt(provider, attemptNumber))

        // Calculate delay
        val delay = config.retryPolicy.delayFor(attemptNumber, error)
        Thread.sleep(delay.toMillis)

        // Retry
        executeWithRetry(operation, attemptNumber + 1)

      case failure @ Left(error) =>
        // Max attempts reached or non-retryable error
        if (attemptNumber > 1) {
          collector.foreach(_.recordError(ErrorKind.Unknown, provider))
          Left(
            ExecutionError(
              message = s"Operation failed after $attemptNumber attempts. Last error: ${error.message}",
              operation = "reliable-client.complete"
            )
          )
        } else {
          failure
        }
    }

  /**
   * Check circuit breaker state and transition if needed.
   */
  private def checkCircuitBreaker(): Result[Unit] =
    circuitState match {
      case CircuitState.Closed =>
        Right(())

      case CircuitState.Open =>
        val now = System.currentTimeMillis()
        if ((now - lastFailureTime) > config.circuitBreaker.recoveryTimeout.toMillis) {
          // Transition to half-open
          circuitState = CircuitState.HalfOpen
          successCount = 0
          collector.foreach(_.recordCircuitBreakerTransition(provider, "half-open"))
          Right(())
        } else {
          // Stay open
          Left(
            ServiceError(
              httpStatus = 503,
              provider = "circuit-breaker",
              details = "Circuit breaker is open - service appears to be down"
            )
          )
        }

      case CircuitState.HalfOpen =>
        // Allow request in half-open state
        Right(())
    }

  /**
   * Handle successful operation.
   */
  private def onSuccess(): Unit =
    circuitState match {
      case CircuitState.Closed =>
        // Reset failure count
        failureCount = 0

      case CircuitState.HalfOpen =>
        // Track successes in half-open state
        successCount += 1
        if (successCount >= config.circuitBreaker.successThreshold) {
          // Close circuit
          circuitState = CircuitState.Closed
          failureCount = 0
          successCount = 0
          collector.foreach(_.recordCircuitBreakerTransition(provider, "closed"))
        }

      case CircuitState.Open =>
      // Should not happen
    }

  /**
   * Handle failed operation.
   */
  private def onFailure(): Unit = {
    lastFailureTime = System.currentTimeMillis()

    circuitState match {
      case CircuitState.Closed =>
        // Track failures
        failureCount += 1
        if (failureCount >= config.circuitBreaker.failureThreshold) {
          // Open circuit
          circuitState = CircuitState.Open
          collector.foreach(_.recordCircuitBreakerTransition(provider, "open"))
        }

      case CircuitState.HalfOpen =>
        // Single failure in half-open → back to open
        circuitState = CircuitState.Open
        successCount = 0
        collector.foreach(_.recordCircuitBreakerTransition(provider, "open"))

      case CircuitState.Open =>
      // Already open
    }
  }

  /**
   * Get current circuit breaker state (for testing/monitoring).
   */
  def currentCircuitState: CircuitState = circuitState

  /**
   * Reset circuit breaker state (for testing).
   */
  def resetCircuitBreaker(): Unit = {
    circuitState = CircuitState.Closed
    failureCount = 0
    successCount = 0
    lastFailureTime = 0L
  }
}

object ReliableClient {

  /**
   * Wrap a client with default reliability configuration.
   */
  def apply(client: LLMClient): ReliableClient =
    new ReliableClient(client, ReliabilityConfig.default, None)

  /**
   * Wrap a client with custom reliability configuration.
   */
  def apply(client: LLMClient, config: ReliabilityConfig): ReliableClient =
    new ReliableClient(client, config, None)

  /**
   * Wrap a client with reliability + metrics.
   */
  def apply(client: LLMClient, config: ReliabilityConfig, collector: MetricsCollector): ReliableClient =
    new ReliableClient(client, config, Some(collector))
}

/**
 * Circuit breaker state.
 */
sealed trait CircuitState
object CircuitState {
  case object Closed   extends CircuitState // Normal operation
  case object Open     extends CircuitState // Failing fast
  case object HalfOpen extends CircuitState // Testing recovery
}
