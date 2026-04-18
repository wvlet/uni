# Control Flow

uni provides utilities for handling failures gracefully and managing resources properly.

## Overview

| Component | Description |
|-----------|-------------|
| [Retry](./retry) | Automatic retry with backoff strategies |
| [Circuit Breaker](./circuit-breaker) | Prevent cascade failures |
| [Rate Limiter](./rate-limiter) | Throttle operation throughput (JVM) |
| [Cache](./cache) | TTL and LRU caching |
| [Resource](./resource) | Safe resource acquisition and release |

## Quick Example

```scala
import wvlet.uni.control.{Retry, CircuitBreaker, Resource}

// Retry a flaky operation
val result = Retry.withBackoff(maxRetry = 3).run {
  callExternalService()
}

// Protect with circuit breaker
val breaker = CircuitBreaker(maxFailures = 5)
val protected = breaker.protect {
  riskyOperation()
}

// Safe resource handling
Resource.withResource(openConnection()) { conn =>
  useConnection(conn)
} // Automatically closed
```

## When to Use

### Retry
- Network calls that may fail transiently
- Database operations during high load
- API calls with rate limiting

### Circuit Breaker
- External service dependencies
- Preventing cascade failures
- Graceful degradation

### Caching
- Expensive computations
- Frequently accessed data
- Rate-limited API responses

### Resource Management
- File handles
- Database connections
- Network sockets

## Package

```scala
import wvlet.uni.control.*
```
