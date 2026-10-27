# HTTP Retry Strategies

uni's HTTP client includes automatic retry for transient failures.

## Default Retry Behavior

Retry is enabled by default: up to 3 retries with exponential backoff.

```scala
import wvlet.uni.http.{Http, HttpRequest}

val client  = Http.client.newSyncClient
val request = HttpRequest.get("https://api.example.com/users")

// Automatically retries on transient failures
val response = client.send(request)
```

## Configuring Retries

```scala
// Set max retry count for every request from this client
val client = Http.client.withMaxRetry(5).newSyncClient

// Or derive a client with a different retry count
val response = client.withMaxRetry(3).send(request)
```

## Disabling Retry

```scala
// Disable for specific request
val response = client.noRetry.send(request)

// Or for the whole client
val noRetryClient = Http.client.noRetry.newSyncClient
```

## Retryable Conditions

The HTTP client retries on:

| Condition | Description |
|-----------|-------------|
| Connection errors | `EOFException`; connection refused, closed channel, protocol errors (JVM) |
| Timeouts | `TimeoutException`; `SocketTimeoutException` (JVM) |
| 5xx Server errors | All 5xx statuses; 503 adds extra wait |
| 408, 410, 429, 499 | Request timeout, gone, rate limiting, client closed request |

## Non-Retryable Conditions

The following are not retried:

| Condition | Description |
|-----------|-------------|
| Other 4xx Client errors | 400, 401, 403, 404, etc. |
| SSL errors | Handshake, key, and peer verification failures (JVM) |
| `CircuitBreakerOpenException` | Circuit breaker is open |

A non-retryable status is thrown immediately as an `HttpException`; a non-retryable
exception (for example an SSL handshake failure) is rethrown as is.

## Exception Classification

```scala
import wvlet.uni.http.HttpExceptionClassifier

// Check if an exception is retryable
HttpExceptionClassifier.classifyExecutionFailure(exception).isRetryable match
  case true  => // Will be retried
  case false => // Will not be retried
```

## Backoff Strategy

The default client uses exponential backoff without jitter:

1. First retry: 1s
2. Second retry: 1.5s
3. Third retry: ~2.3s
4. ...growing 1.5x per retry, capped at 30s

## Handling Max Retry Exceeded

`HttpMaxRetryException` is not an `HttpException`; catch it separately:

```scala
import wvlet.uni.http.HttpMaxRetryException

try
  val response = client.send(request)
catch
  case e: HttpMaxRetryException =>
    println(s"Failed after ${e.retryContext.retryCount} retries")
    println(s"Last error: ${e.getCause.getMessage}")
    // Use fallback or fail
```

## Custom Retry Logic

For more control, use the control module:

A `noRetry` client reports each retryable failure (connection errors, 429, 5xx)
as an `HttpMaxRetryException` whose cause is the original error:

```scala
import java.io.IOException
import wvlet.uni.control.{ResultClass, Retry}
import wvlet.uni.http.HttpMaxRetryException

val response = Retry
  .withBackOff(maxRetry = 10, initialIntervalMillis = 500)
  .retryOn {
    case e: HttpMaxRetryException =>
      e.getCause match
        case _: IOException =>
          ResultClass.retryableFailure(e)
        case _ if e.statusCode == 429 =>
          ResultClass.retryableFailure(e)
        case _ =>
          ResultClass.nonRetryableFailure(e)
    case e =>
      ResultClass.nonRetryableFailure(e)
  }
  .run {
    client.noRetry.send(request)
  }
```

## Rate Limiting

Handle rate limits gracefully:

```scala
import wvlet.uni.control.Retry
import wvlet.uni.http.HttpMaxRetryException

val response = Retry
  .withBackOff(maxRetry = 5)
  .beforeRetry { ctx =>
    ctx.lastError match
      case e: HttpMaxRetryException if e.statusCode == 429 =>
        // Wait longer for rate limits
        Thread.sleep(ctx.retryCount * 1000L)
      case _ =>
        ()
  }
  .run {
    client.noRetry.send(request)
  }
```

## Combining with Circuit Breaker

```scala
import wvlet.uni.control.CircuitBreaker

val breaker = CircuitBreaker.withConsecutiveFailures(5)

val response = breaker.run {
  client.send(request)  // Has built-in retry
}
```

## Best Practices

1. **Use default retry** for most cases
2. **Disable for idempotent checks** like auth validation
3. **Monitor retry rates** to detect service issues
4. **Set reasonable limits** to avoid long waits
5. **Log retry attempts** for debugging
