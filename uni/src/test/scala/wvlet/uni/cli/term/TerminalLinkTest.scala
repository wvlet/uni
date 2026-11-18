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
package wvlet.uni.cli.term

import wvlet.uni.test.UniTest

class TerminalLinkTest extends UniTest:

  test("allow web and mail links by default") {
    TerminalLink.isAllowed("https://wvlet.org/uni") shouldBe true
    TerminalLink.isAllowed("http://localhost:8080/") shouldBe true
    TerminalLink.isAllowed("HTTPS://WVLET.ORG") shouldBe true
    TerminalLink.isAllowed("mailto:dev@example.com") shouldBe true
  }

  test("reject other schemes") {
    TerminalLink.isAllowed("javascript:alert(1)") shouldBe false
    TerminalLink.isAllowed("JavaScript:alert(1)") shouldBe false
    TerminalLink.isAllowed("file:///etc/passwd") shouldBe false
    TerminalLink.isAllowed("vscode://file/x") shouldBe false
    TerminalLink.isAllowed("data:text/html,x") shouldBe false
  }

  test("reject URIs without a valid scheme") {
    TerminalLink.isAllowed("") shouldBe false
    TerminalLink.isAllowed("wvlet.org/uni") shouldBe false
    TerminalLink.isAllowed(":https://x") shouldBe false
    TerminalLink.isAllowed("1http://x") shouldBe false
    TerminalLink.scheme("ｈttps://x") shouldBe None
  }

  test("reject URIs with spaces or control characters") {
    TerminalLink.isAllowed(" https://x") shouldBe false
    TerminalLink.isAllowed("https://x\njavascript:y") shouldBe false
    TerminalLink.isAllowed("https://x y") shouldBe false
  }

  test("use a custom allowlist") {
    TerminalLink.isAllowed("file:///tmp/a.txt", Set("file")) shouldBe true
    TerminalLink.isAllowed("https://x", Set.empty) shouldBe false
    TerminalLink.scheme("Git+SSH://host/repo") shouldBe Some("git+ssh")
  }

  private def urls(text: String): Seq[String] = TerminalLink.findUrls(text).map(_.uri)

  test("find http and https URLs in plain text") {
    TerminalLink.findUrls("see https://wvlet.org/uni now") shouldBe
      Seq(TerminalLink.UrlMatch(start = 4, end = 25, uri = "https://wvlet.org/uni"))
    urls("http://a.b/c?q=1&r=2#top and HTTPS://X.ORG") shouldBe
      Seq("http://a.b/c?q=1&r=2#top", "HTTPS://X.ORG")
  }

  test("find only http and https URLs") {
    urls("ftp://x.org file:///etc/passwd javascript:alert(1) www.x.org mailto:a@b.c") shouldBe
      Seq.empty
    urls("https:// alone") shouldBe Seq.empty
    urls("xhttps://x.org") shouldBe Seq.empty
  }

  test("leave trailing punctuation and unmatched brackets out of a URL") {
    urls("Open https://x.org/a.") shouldBe Seq("https://x.org/a")
    urls("(see https://x.org/a), then") shouldBe Seq("https://x.org/a")
    urls("https://en.wikipedia.org/wiki/Scala_(programming_language)") shouldBe
      Seq("https://en.wikipedia.org/wiki/Scala_(programming_language)")
    urls("'https://x.org/a'") shouldBe Seq("https://x.org/a")
    urls("[link](https://x.org/a)") shouldBe Seq("https://x.org/a")
    urls("<https://x.org/a>") shouldBe Seq("https://x.org/a")
  }

  test("end a URL at whitespace or a non-ASCII character") {
    urls("https://x.org/a\tnext") shouldBe Seq("https://x.org/a")
    urls("「https://x.org/a」") shouldBe Seq("https://x.org/a")
  }

end TerminalLinkTest
