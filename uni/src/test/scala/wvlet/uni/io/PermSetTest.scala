/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.io

import wvlet.uni.test.UniTest

class PermSetTest extends UniTest:

  test("create from octal bits") {
    val perms = PermSet(0x1ed) // 0o755
    perms.ownerRead shouldBe true
    perms.ownerWrite shouldBe true
    perms.ownerExecute shouldBe true
    perms.groupRead shouldBe true
    perms.groupWrite shouldBe false
    perms.groupExecute shouldBe true
    perms.otherRead shouldBe true
    perms.otherWrite shouldBe false
    perms.otherExecute shouldBe true
  }

  test("create from permission string") {
    val perms = PermSet("rwxr-xr-x")
    perms.bits shouldBe 0x1ed // 0o755
    perms.ownerRead shouldBe true
    perms.ownerWrite shouldBe true
    perms.ownerExecute shouldBe true
    perms.groupRead shouldBe true
    perms.groupWrite shouldBe false
    perms.groupExecute shouldBe true
    perms.otherRead shouldBe true
    perms.otherWrite shouldBe false
    perms.otherExecute shouldBe true
  }

  test("create from string rw-r--r--") {
    val perms = PermSet("rw-r--r--")
    perms.bits shouldBe 0x1a4 // 0o644
    perms.ownerRead shouldBe true
    perms.ownerWrite shouldBe true
    perms.ownerExecute shouldBe false
    perms.groupRead shouldBe true
    perms.groupWrite shouldBe false
    perms.groupExecute shouldBe false
    perms.otherRead shouldBe true
    perms.otherWrite shouldBe false
    perms.otherExecute shouldBe false
  }

  test("create from string ---------") {
    val perms = PermSet("---------")
    perms.bits shouldBe 0
  }

  test("create from string rwxrwxrwx") {
    val perms = PermSet("rwxrwxrwx")
    perms.bits shouldBe 0x1ff // 0o777
  }

  test("fromOctalString") {
    PermSet.fromOctalString("755").bits shouldBe 0x1ed
    PermSet.fromOctalString("644").bits shouldBe 0x1a4
    PermSet.fromOctalString("777").bits shouldBe 0x1ff
    PermSet.fromOctalString("0").bits shouldBe 0
    PermSet.fromOctalString("700").bits shouldBe 0x1c0
  }

  test("toOctalString") {
    PermSet(0x1ed).toOctalString shouldBe "755"
    PermSet(0x1a4).toOctalString shouldBe "644"
    PermSet(0x1ff).toOctalString shouldBe "777"
    PermSet(0).toOctalString shouldBe "000"
  }

  test("toPermString") {
    PermSet(0x1ed).toPermString shouldBe "rwxr-xr-x"
    PermSet(0x1a4).toPermString shouldBe "rw-r--r--"
    PermSet(0x1ff).toPermString shouldBe "rwxrwxrwx"
    PermSet(0).toPermString shouldBe "---------"
  }

  test("toString returns perm string") {
    PermSet(0x1ed).toString shouldBe "rwxr-xr-x"
  }

  test("roundtrip: string -> PermSet -> string") {
    val strings = Seq("rwxr-xr-x", "rw-r--r--", "rwxrwxrwx", "---------", "rwx------", "---r--r--")
    strings.foreach { s =>
      PermSet(s).toPermString shouldBe s
    }
  }

  test("roundtrip: octal -> PermSet -> octal") {
    val octals = Seq("755", "644", "777", "000", "700", "004")
    octals.foreach { s =>
      PermSet.fromOctalString(s).toOctalString shouldBe s
    }
  }

  test("union") {
    val a = PermSet("rw-------")
    val b = PermSet("---r-x---")
    val c = a | b
    c.toPermString shouldBe "rw-r-x---"
  }

  test("intersection") {
    val a = PermSet("rwxr-x---")
    val b = PermSet("r-xr-xr-x")
    val c = a & b
    c.toPermString shouldBe "r-xr-x---"
  }

  test("diff") {
    val a = PermSet("rwxr-xr-x")
    val b = PermSet("--x--x--x")
    val c = a.diff(b)
    c.toPermString shouldBe "rw-r--r--"
  }

  test("predefined constants") {
    PermSet.empty.bits shouldBe 0
    PermSet.all.bits shouldBe 0x1ff
    PermSet.all.toPermString shouldBe "rwxrwxrwx"
    PermSet.empty.toPermString shouldBe "---------"
  }

  test("reject invalid bits") {
    intercept[IllegalArgumentException] {
      PermSet(0x200) // Above 0o777
    }
    intercept[IllegalArgumentException] {
      PermSet(-1)
    }
  }

  test("reject invalid string length") {
    intercept[IllegalArgumentException] {
      PermSet("rwx")
    }
    intercept[IllegalArgumentException] {
      PermSet("rwxr-xr-x-")
    }
  }

  test("reject invalid string characters") {
    intercept[IllegalArgumentException] {
      PermSet("abc------")
    }
  }

  test("reject invalid octal string") {
    intercept[IllegalArgumentException] {
      PermSet.fromOctalString("888")
    }
    intercept[IllegalArgumentException] {
      PermSet.fromOctalString("")
    }
  }

  test("equality") {
    PermSet(0x1ed) shouldBe PermSet("rwxr-xr-x")
    PermSet.fromOctalString("644") shouldBe PermSet("rw-r--r--")
  }

end PermSetTest
