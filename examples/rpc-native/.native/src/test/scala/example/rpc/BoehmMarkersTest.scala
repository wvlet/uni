package example.rpc

import wvlet.uni.test.UniTest

import scala.scalanative.unsafe.*

@extern
private object GcShim:
  def uni_gc_marker_count(): CInt = extern

class BoehmMarkersTest extends UniTest:
  test("uni runs Boehm with one marker thread unless GC_MARKERS is set") {
    // Marker threads start with the first thread other than main
    val t = Thread(() => ())
    t.start()
    t.join()
    val count = GcShim.uni_gc_marker_count()
    if count < 0 then
      skip("Not built with the Boehm GC")
    else if sys.env.contains("GC_MARKERS") || sys.env.contains("GC_NPROCS") then
      skip("GC_MARKERS or GC_NPROCS is set")
    else
      count shouldBe 1
  }
