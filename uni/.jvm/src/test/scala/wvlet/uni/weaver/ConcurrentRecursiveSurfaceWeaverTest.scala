package wvlet.uni.weaver

import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

object ConcurrentRecursiveSurfaceWeaverTest:
  case class Node(value: Int, next: Option[Node])

class ConcurrentRecursiveSurfaceWeaverTest extends UniTest:
  import ConcurrentRecursiveSurfaceWeaverTest.*

  test("concurrent fromSurface for the same recursive type never observes a partial tree") {
    // Regression: an earlier draft committed sub-weavers (e.g. Option[Node]) to the
    // shared cache mid-build, so a parallel reader could grab one whose embedded
    // LazyWeaver(Node) had no cache target yet → IllegalStateException at pack time.
    // JVM-only because java.util.concurrent isn't available on Scala.js.
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    try
      val task: Runnable =
        () =>
          val w    = Weaver.fromSurface(Surface.of[Node]).asInstanceOf[Weaver[Any]]
          val node = Node(1, Some(Node(2, None)))
          // Round-trip exercises every embedded LazyWeaver.
          w.fromJson(w.toJson(node)) shouldBe node
      val futures = (1 to 64).map(_ => pool.submit(task))
      futures.foreach(_.get())
    finally
      pool.shutdown()
  }

end ConcurrentRecursiveSurfaceWeaverTest
