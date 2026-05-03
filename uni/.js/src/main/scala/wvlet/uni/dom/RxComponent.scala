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
package wvlet.uni.dom

/**
  * A component that wraps inner content with outer chrome — the airframe-rx-html `RxComponent`
  * shape, ported so wvlet code can keep its `extends RxComponent` definitions intact.
  *
  * Subclasses implement `render(content: RxElement)` to describe the wrapping markup. The `apply`
  * overloads invoke `render` with the supplied content, or with [[RxElement.empty]] for standalone
  * use:
  *
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   object MainFrame extends RxComponent:
  *     override def render(content: RxElement): RxElement = div(
  *       cls -> "h-screen max-h-screen",
  *       NavBar,
  *       content,
  *       URLParamManager()
  *     )
  *
  *   MainFrame(editor).renderTo("main")  // outer chrome wraps `editor`
  *   MainFrame()                         // standalone with no inner content
  * }}}
  */
abstract class RxComponent:

  /**
    * Render this component, wrapping the given content.
    */
  def render(content: RxElement): RxElement

  /**
    * Wrap the given content using this component's chrome. Returns an [[RxElement]] that can be
    * passed as a child or rendered with [[wvlet.uni.dom.all.renderTo]].
    */
  def apply(content: RxElement): RxElement = render(content)

  /**
    * Standalone render — equivalent to `apply(RxElement.empty)`. Useful when the component does not
    * actually slot any inner content (or when the slot is already wired internally).
    */
  def apply(): RxElement = render(RxElement.empty)

end RxComponent
