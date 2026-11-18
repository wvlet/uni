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

import wvlet.uni.dom.DomNode

/**
  * Reads a `content` argument that takes one element or several: `content = form` or `content =
  * Seq(title, form)`. It is a union rather than two overloads, so `Seq(div(...), "text")` still
  * converts each element to a node.
  */
private[ui] object Content:
  def nodes(c: DomNode | Seq[DomNode]): Seq[DomNode] =
    c match
      case s: Seq[?] =>
        s.asInstanceOf[Seq[DomNode]]
      case n: DomNode =>
        Seq(n)
