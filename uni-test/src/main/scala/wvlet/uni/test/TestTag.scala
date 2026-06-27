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
package wvlet.uni.test

/**
  * A tag attached to a test via `test(name, tags*)`. Tags both select tests at run time
  * (`--tags:`/`--exclude-tags:`) and, for built-ins like [[TestTag.Flaky]], alter how a result is
  * reported. Built-in layer tags ([[TestTag.UI]], [[TestTag.Electron]], …) let one suite span
  * multiple testing layers and still be run layer-by-layer, as in VSCode's separate
  * unit/integration/UI test commands.
  *
  * {{{
  *   test("renders the toolbar", UI) { ... }          // layer tag
  *   test("hits the network", Electron, Slow) { ... } // multiple tags
  *   test("retried under load", Flaky) { ... }         // failure -> skipped
  *   test("end-to-end happy path", Smoke) { ... }      // category tag
  *   test("legacy path", TestTag("legacy")) { ... }    // custom tag
  * }}}
  *
  * The name is a trait parameter, so defining your own semantic tag is a one-liner:
  * `case object Gpu extends TestTag("gpu")`.
  */
trait TestTag(val name: String):
  override def toString: String = s"TestTag(${name})"

object TestTag:

  /** Create a custom tag by name, e.g. `TestTag("smoke")`. */
  def apply(name: String): TestTag = new TestTag(name) {}

  /**
    * Treat a bare string as a custom tag, so `test("x", "smoke")` works. Requires
    * `import scala.language.implicitConversions` at the call site.
    */
  given Conversion[String, TestTag] = apply(_)

  /**
    * Marks a test as flaky: a failure is reported as `Skipped` (with a `[flaky]` prefix) instead of
    * failing the build. Replaces the old `test(name, flaky = true)` boolean.
    */
  case object Flaky extends TestTag("flaky")

  // Common testing-layer tags (see plans/2026-06-27-multi-layer-testing.md).
  case object UI          extends TestTag("ui")
  case object Electron    extends TestTag("electron")
  case object Integration extends TestTag("integration")

  /** A quick sanity check across the stack; typically run as a fast pre-merge subset. */
  case object Smoke extends TestTag("smoke")

  /** A slow test, usually excluded from the fast inner-loop run via `--exclude-tags:slow`. */
  case object Slow extends TestTag("slow")
