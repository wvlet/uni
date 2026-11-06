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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.StorageCodec
import wvlet.uni.rx.{Cancelable, Rx}

/**
  * Light or dark colors for uni-ui. `uni-ui.css` reads `data-theme` on the root element: `light`
  * and `dark` force a scheme, and no attribute follows the OS (`prefers-color-scheme`).
  *
  * {{{
  *   val scheme = Storage.local("app.colorScheme", ColorScheme.System)
  *   ColorScheme.bind(scheme)
  * }}}
  */
enum ColorScheme(val label: String) derives StorageCodec:
  case System extends ColorScheme("System")
  case Light  extends ColorScheme("Light")
  case Dark   extends ColorScheme("Dark")

object ColorScheme:

  /** Set (or, for System, remove) the `data-theme` attribute on `root`. */
  def applyTo(root: dom.Element, scheme: ColorScheme): Unit =
    scheme match
      case ColorScheme.System =>
        root.removeAttribute("data-theme")
      case ColorScheme.Light =>
        root.setAttribute("data-theme", "light")
      case ColorScheme.Dark =>
        root.setAttribute("data-theme", "dark")

  /**
    * Keep `root` (the `<html>` element by default) in step with `scheme`. Cancel the result to
    * stop.
    */
  def bind(scheme: Rx[ColorScheme], root: dom.Element = dom.document.documentElement): Cancelable =
    scheme.run(s => applyTo(root, s))

end ColorScheme
