package wvlet.uni.weaver

import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

object RecursiveSurfaceWeaverTest:
  // Self-recursive abstract class — mirrors wvlet's `DataType` shape that triggered #515:
  // building Weaver[TypeNode] needs Weaver[List[TypeNode]] needs Weaver[TypeNode]…
  abstract class TypeNode(val name: String, val children: List[TypeNode])

  // Concrete subtype to exercise round-trip on a non-abstract field that itself
  // contains the recursive abstract type.
  case class TypeColumn(label: String, node: TypeNode) derives Weaver

  // Self-recursive concrete case class via Option to break the compile-time cycle.
  case class Node(value: Int, next: Option[Node])

  // Mutually recursive case classes via Option (avoids divergent implicit search at derive time).
  case class Branch(name: String, child: Option[Leaf])
  case class Leaf(value: Int, parent: Option[Branch])
end RecursiveSurfaceWeaverTest

class RecursiveSurfaceWeaverTest extends UniTest:
  import RecursiveSurfaceWeaverTest.*

  test("fromSurface does not throw for self-recursive abstract class with self-typed children") {
    // Without the LazyWeaver fix this throws java.lang.IllegalStateException: Recursive update
    // from ConcurrentHashMap.computeIfAbsent during the recursive build of Weaver[TypeNode].
    val w = Weaver.fromSurface(Surface.of[TypeNode])
    w shouldNotBe null
  }

  test("fromSurface for surface containing a self-recursive abstract field") {
    val w = Weaver.fromSurface(Surface.of[TypeColumn])
    w shouldNotBe null
  }

  test("fromSurface for self-recursive case class") {
    val w = Weaver.fromSurface(Surface.of[Node])
    w shouldNotBe null
  }

  test("fromSurface for mutually recursive case classes") {
    val w1 = Weaver.fromSurface(Surface.of[Branch])
    val w2 = Weaver.fromSurface(Surface.of[Leaf])
    w1 shouldNotBe null
    w2 shouldNotBe null
  }

  test("LazyWeaver round-trips data through self-recursive case class") {
    // Exercises LazyWeaver.pack/unpack: the cache is populated by the time the
    // placeholder is touched at runtime.
    val w    = Weaver.fromSurface(Surface.of[Node]).asInstanceOf[Weaver[Any]]
    val node = Node(1, Some(Node(2, Some(Node(3, None)))))
    val json = w.toJson(node)
    val back = w.fromJson(json)
    back shouldBe node
  }

  test("concurrent fromSurface for the same recursive type never observes a partial tree") {
    // Regression: an earlier draft committed sub-weavers (e.g. Option[Node]) to the
    // shared cache mid-build, so a parallel reader could grab one whose embedded
    // LazyWeaver(Node) had no cache target yet → IllegalStateException at pack time.
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

end RecursiveSurfaceWeaverTest
