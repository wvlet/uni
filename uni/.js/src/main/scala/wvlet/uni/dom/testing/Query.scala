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
package wvlet.uni.dom.testing

import org.scalajs.dom

/**
  * Query a rendered DOM subtree for elements, modeled on Testing Library's queries.
  *
  * Two flavors per selector:
  *   - `queryBy...` returns `Option`/`Seq` (absence is not an error)
  *   - `getBy...` returns the element or throws [[ElementNotFoundException]] (use in assertions
  *     where the element must exist)
  */
class Query(val root: dom.Element):

  // --- Raw CSS selectors ---

  def queryAll(selector: String): Seq[dom.Element] =
    val nodes = root.querySelectorAll(selector)
    (0 until nodes.length).map(i => nodes(i).asInstanceOf[dom.Element])

  def query(selector: String): Option[dom.Element] = Option(root.querySelector(selector))

  def get(selector: String): dom.Element = query(selector).getOrElse(
    throw ElementNotFoundException(s"No element matches selector: ${selector}")
  )

  // --- data-testid ---

  def queryAllByTestId(testId: String): Seq[dom.Element] = queryAll(s"[data-testid='${testId}']")

  def queryByTestId(testId: String): Option[dom.Element] = queryAllByTestId(testId).headOption

  def getByTestId(testId: String): dom.Element = queryByTestId(testId).getOrElse(
    throw ElementNotFoundException(s"No element with data-testid='${testId}'")
  )

  // --- text content ---

  /**
    * All elements whose trimmed text matches and that have no descendant element with the same
    * matching text (i.e. the deepest/most-specific matches), so `getByText("Save")` returns the
    * `<button>` rather than an enclosing `<div>`.
    */
  def queryAllByText(matcher: TextMatcher): Seq[dom.Element] =
    val all = queryAll("*").filter(e => matcher.matches(e.textContent))
    all.filterNot(e => all.exists(other => (other ne e) && e.contains(other)))

  def queryByText(matcher: TextMatcher): Option[dom.Element] = queryAllByText(matcher).headOption

  def getByText(matcher: TextMatcher): dom.Element = queryByText(matcher).getOrElse(
    throw ElementNotFoundException(s"No element with text matching: ${matcher}")
  )

end Query

/** A matcher for element text, accepting an exact `String` or a `Regex`. */
trait TextMatcher:
  def matches(text: String): Boolean

object TextMatcher:
  import scala.language.implicitConversions

  /** Exact match against the element's trimmed text content. */
  given fromString: Conversion[String, TextMatcher] with
    def apply(expected: String): TextMatcher =
      new TextMatcher:
        def matches(text: String): Boolean = text.trim == expected
        override def toString: String      = s"\"${expected}\""

  /** Regex match against the element's trimmed text content. */
  given fromRegex: Conversion[scala.util.matching.Regex, TextMatcher] with
    def apply(re: scala.util.matching.Regex): TextMatcher =
      new TextMatcher:
        def matches(text: String): Boolean = re.findFirstIn(text.trim).isDefined
        override def toString: String      = s"/${re}/"

/** Thrown by `getBy...` queries when no matching element is found. */
class ElementNotFoundException(message: String) extends RuntimeException(message)
