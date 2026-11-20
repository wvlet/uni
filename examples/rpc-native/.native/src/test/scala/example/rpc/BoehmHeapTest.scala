package example.rpc

import wvlet.uni.http.HttpServer
import wvlet.uni.test.UniTest

import scala.scalanative.unsafe.*

@extern
private object GcHeapShim:
  def uni_gc_expand_heap_to(target: CLongLong): CLongLong = extern

class BoehmHeapTest extends UniTest:
  test(
    "a started server runs Boehm with at least a 16 MB heap unless GC_INITIAL_HEAP_SIZE is set"
  ) {
    if sys.env.contains("GC_INITIAL_HEAP_SIZE") then
      skip("GC_INITIAL_HEAP_SIZE is set")
    TodoRpc
      .design(0)
      .build[HttpServer] { _ =>
        // A target of 0 grows nothing and reports the heap size
        val heap = GcHeapShim.uni_gc_expand_heap_to(0)
        if heap < 0 then
          skip("Not built with the Boehm GC")
        (heap >= (16L << 20)) shouldBe true
      }
  }
