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

import wvlet.uni.rx.Rx

/**
  * A list rendered item by item. When `items` emits, only the items whose key is new are rendered,
  * items whose key is gone are removed, and the rest keep their DOM nodes (and their state, such as
  * focus, scroll position, or a running animation), moved into the new order. An item whose key
  * stays but whose value changes (`!=`) is rendered again. Create one with [[each]].
  */
case class KeyedList[A, K](items: Rx[Seq[A]], key: A => K, render: A => DomNode) extends DomNode

/**
  * Render each item of `items` once per key, so an update touches only the items that changed.
  * Prefer this over `items.map(_.map(render))`, which rebuilds the whole list on every update.
  *
  * {{{
  *   div(each(store.messages)(_.id)(m => Bubble(m)))
  * }}}
  *
  * Keys must be unique within one value of `items`; a repeated key keeps only its first item.
  */
def each[A, K](items: Rx[Seq[A]])(key: A => K)(render: A => DomNode): DomNode = KeyedList(
  items,
  key,
  render
)
