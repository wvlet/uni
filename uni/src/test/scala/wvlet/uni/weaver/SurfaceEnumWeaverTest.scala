package wvlet.uni.weaver

import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

/**
  * Weaver.fromSurface must encode enums with parameterized cases exactly like `derives Weaver`, so
  * RPC/HTTP code (which resolves codecs from a Surface) and derived codecs can read each other's
  * data.
  */
class SurfaceEnumWeaverTest extends UniTest:
  import RecursiveSumTypeWeaverTest.*

  private def surfaceWeaver[A](surface: Surface): Weaver[A] = Weaver
    .fromSurface(surface)
    .asInstanceOf[Weaver[A]]

  private val tree: ENode = ENode.Split(
    Dir.Row,
    Seq(
      ENode.Group(Seq("a", "b"), Some("a")),
      ENode.Split(Dir.Column, Seq(ENode.Group(Seq.empty, None)), Seq(0.3, 0.7))
    ),
    Seq(0.5, 0.5)
  )

  private val expr: Expr = Expr.Add(Expr.Succ(Expr.Zero), Some(Expr.Add(Expr.Zero, None)))

  test("encode a recursive enum like the derived codec") {
    val w     = surfaceWeaver[ESaved](Surface.of[ESaved])
    val saved = ESaved(1, tree)
    val json  = w.toJson(saved)
    json shouldBe Weaver.toJson(saved)
    w.fromJson(json) shouldBe saved
  }

  test("encode a singleton case of an enum with parameterized cases as an object") {
    val w = surfaceWeaver[Expr](Surface.of[Expr])
    w.toJson(Expr.Zero) shouldBe """{"@type":"Zero"}"""
    w.toJson(Expr.Zero) shouldBe Weaver.toJson[Expr](Expr.Zero)
    w.fromJson("""{"@type":"Zero"}""") shouldBe Expr.Zero
  }

  test("round-trip an enum recursive through Option") {
    val w    = surfaceWeaver[Expr](Surface.of[Expr])
    val json = w.toJson(expr)
    json shouldBe Weaver.toJson(expr)
    w.fromJson(json) shouldBe expr
  }

  test("read data written by the derived codec, and the reverse") {
    val w = surfaceWeaver[ENode](Surface.of[ENode])
    w.fromJson(Weaver.toJson(tree)) shouldBe tree
    Weaver.fromJson[ENode](w.toJson(tree)) shouldBe tree
    Weaver.unweave[ENode](w.weave(tree)) shouldBe tree
    w.unweave(Weaver.weave(tree)) shouldBe tree
  }

  test("enum with only singleton cases still encodes as a string") {
    val w = surfaceWeaver[Dir](Surface.of[Dir])
    w.toJson(Dir.Column) shouldBe "\"Column\""
    w.toJson(Dir.Column) shouldBe Weaver.toJson(Dir.Column)
    w.fromJson("\"Row\"") shouldBe Dir.Row
  }

  test("read a singleton case written as a bare string") {
    val w = surfaceWeaver[Expr](Surface.of[Expr])
    w.fromJson("\"Zero\"") shouldBe Expr.Zero
    Weaver.fromJson[Expr]("\"Zero\"") shouldBe Expr.Zero
    val e = intercept[Exception] {
      w.fromJson("\"Succ\"")
    }
    e.getMessage shouldContain "Unknown singleton 'Succ'"
  }

end SurfaceEnumWeaverTest
