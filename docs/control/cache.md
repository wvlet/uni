# Caching

Uni provides caching through Rx operators and dedicated cache utilities.

## Rx Cache

Cache the last value in a reactive stream:

```scala
import wvlet.uni.rx.Rx
import java.util.concurrent.TimeUnit

// Cache the result of an expensive operation
val cached = Rx.single(expensiveComputation())
  .cache

// Multiple subscriptions reuse the cached value
cached.subscribe(println)  // Computes
cached.subscribe(println)  // Uses cached
```

## TTL Cache

Expire cached values after a duration:

```scala
val cached = Rx.single(fetchData())
  .cache
  .expireAfterWrite(5, TimeUnit.MINUTES)

// Value is recomputed after 5 minutes
```

## Manual Cache Management

```scala
val cached = Rx.single(fetchData()).cache

// Check current cached value
cached.getCurrent match
  case Some(value) => println(s"Cached: ${value}")
  case None => println("No cached value")
```

## Periodic Refresh

Combine interval with cache:

```scala
import java.util.concurrent.TimeUnit

// Refresh data every 30 seconds
val refreshing = Rx
  .interval(30, TimeUnit.SECONDS)
  .flatMap(_ => Rx.single(fetchLatestData()))
  .cache

// Subscribers always get the latest cached value
refreshing.subscribe { data =>
  updateUI(data)
}
```

## Request Deduplication

Use cache to deduplicate concurrent requests:

```scala
class DataService:
  private val cache = Rx.single(loadData()).cache

  def getData: Rx[Data] = cache
```

## Cache Patterns

### Lazy Initialization

```scala
val lazyData = Rx.single {
  logger.info("Loading data...")
  loadExpensiveData()
}.cache

// Data is loaded only on first access
```

### Conditional Refresh

```scala
def getWithRefresh(forceRefresh: Boolean): Rx[Data] =
  if forceRefresh then
    Rx.single(fetchData())
  else
    cachedData
```

### Fallback on Failure

```scala
val robust = Rx.single(fetchFromPrimary())
  .recover {
    case _: NetworkException =>
      cachedFallback.getCurrent.getOrElse(defaultValue)
  }
  .cache
```

## Best Practices

1. **Set appropriate TTLs** - Balance freshness vs. performance
2. **Handle cache misses** - Provide fallback values
3. **Monitor hit rates** - Tune cache parameters
4. **Consider memory** - Cache size impacts memory usage
5. **Thread safety** - Rx cache is thread-safe

## LRU Cache (Custom)

For more advanced caching, implement LRU:

```scala
import scala.collection.mutable

class LRUCache[K, V](maxSize: Int):
  private val cache = mutable.LinkedHashMap[K, V]()

  def get(key: K): Option[V] =
    cache.get(key).map { value =>
      // Move to end (most recently used)
      cache.remove(key)
      cache.put(key, value)
      value
    }

  def put(key: K, value: V): Unit =
    cache.remove(key)
    cache.put(key, value)
    if cache.size > maxSize then
      cache.remove(cache.head._1)
```
