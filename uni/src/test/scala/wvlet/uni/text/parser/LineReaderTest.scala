package wvlet.uni.text.parser

import wvlet.uni.test.UniTest

class LineReaderTest extends UniTest:

  test("track lines and columns") {
    val r = LineReader("ab\n  cd\n")
    r.peek shouldBe 'a'
    r.advance(3)
    (r.line, r.column) shouldBe (1, 0)
    r.skipSpaces() shouldBe 2
    r.column shouldBe 2
    r.restOfLine shouldBe "cd"
    r.readToLineEnd() shouldBe "cd"
    r.isAtLineEnd shouldBe true
    r.skipLineBreak() shouldBe true
    r.isEOF shouldBe true
    r.peek shouldBe Tokens.SU
  }

  test("normalize line breaks") {
    val r = LineReader("a\r\nb\rc")
    r.text shouldBe "a\nb\nc"
    r.nextLine()
    r.nextLine()
    (r.line, r.peek) shouldBe (2, 'c')
  }

  test("reset to a mark") {
    val r = LineReader("x\ny\nz")
    val m = r.mark
    r.advance(4)
    r.line shouldBe 2
    r.reset(m)
    (r.offset, r.line, r.column) shouldBe (0, 0, 0)
  }

  test("format errors with a caret") {
    val r = LineReader("key: value\nbad line\n")
    r.nextLine()
    r.advance(4)
    val e = intercept[TextParseException] {
      r.error("Unexpected space")
    }
    e.offset shouldBe 15
    e.getMessage shouldBe "Unexpected space (line 2, column 5)\nbad line\n    ^"
  }

end LineReaderTest
