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

end TerminalLinkTest
