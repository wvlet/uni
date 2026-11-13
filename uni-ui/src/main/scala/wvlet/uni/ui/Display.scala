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

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions

/** The color of a [[Badge]]: the CSS modifier is `uni-badge--<cssName>`. */
enum BadgeTone(val cssName: String):
  case Neutral extends BadgeTone("neutral")
  case Accent  extends BadgeTone("accent")
  case Danger  extends BadgeTone("danger")

/**
  * A small label for a count or a status, such as the number of calls next to a tab name.
  *
  * {{{
  *   Badge(log.calls.map(_.size.toString))
  *   Badge.dot("New output").withTone(BadgeTone.Accent)
  * }}}
  */
class Badge private (content: Seq[DomNode], tone: BadgeTone, isDot: Boolean, label: Option[String])
    extends RxElement:
  def withTone(t: BadgeTone): Badge = new Badge(content, t, isDot, label)

  override def render: RxElement =
    val shape =
      if isDot then
        " uni-badge--dot"
      else
        ""
    span(
      cls          -> s"uni-badge uni-badge--${tone.cssName}${shape}",
      data("slot") -> "badge",
      label.map(l => Seq(role -> "img", ariaLabel -> l)).getOrElse(Seq.empty[DomNode]),
      content
    )

end Badge

object Badge:
  // `new` is required here: `apply(content*)` replaces the constructor proxy
  def apply(content: DomNode*): Badge = new Badge(content, BadgeTone.Neutral, false, None)

  /** A dot with no text, such as an unread marker; `label` is its accessible name. */
  def dot(label: String): Badge = new Badge(Seq.empty, BadgeTone.Accent, true, Some(label))

/** A keyboard key, such as `Kbd("⌘")` or `Kbd("Enter")`, rendered as `<kbd>`. */
class Kbd(key: String) extends RxElement:
  override def render: RxElement =
    wvlet.uni.dom.HtmlTags.tag("kbd")(cls -> "uni-kbd", data("slot") -> "kbd", key)

/**
  * A thin bar along the bottom of a window or pane. Put [[StatusBar.spacer]] between items to push
  * the rest to the far end.
  *
  * {{{
  *   StatusBar(span("Connected"), StatusBar.spacer, span("UTF-8"))
  * }}}
  */
class StatusBar(content: DomNode*) extends RxElement:
  override def render: RxElement = footer(
    cls          -> "uni-statusbar",
    data("slot") -> "statusbar",
    content
  )

object StatusBar:
  def spacer: RxElement = span(cls -> "uni-statusbar__spacer", data("slot") -> "statusbar-spacer")
