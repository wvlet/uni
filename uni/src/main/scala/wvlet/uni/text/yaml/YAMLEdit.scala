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
package wvlet.uni.text.yaml

import wvlet.uni.json.JSON.*

/**
  * Edits on [[YAMLNode]] trees that keep the source of every node they do not change
  */
private[yaml] object YAMLEdit:
  import YAMLNode.*

  def set(n: YAMLNode, path: Seq[String | Int], v: JSONValue): YAMLNode =
    path match
      case Seq() =>
        replace(n, v)
      case (k: String) +: rest =>
        n match
          case m: Mapping =>
            val i = m.entries.indexWhere(e => keyOf(e.key).contains(k))
            if i >= 0 then
              val e = m.entries(i)
              m.copy(entries = m.entries.updated(i, e.copy(value = set(e.value, rest, v))))
            else
              m.copy(entries = m.entries :+ YAMLEntry(YAMLWriter.key(k), build(rest, v)))
          case other if isNull(other) =>
            withAnchor(build(path, v), other.anchorOf)
          case _ =>
            throw IllegalArgumentException(s"Cannot set key '${k}': the node is not a mapping")
      case (i: Int) +: rest =>
        n match
          case s: Sequence if i >= 0 && i < s.items.size =>
            s.copy(items = s.items.updated(i, set(s.items(i), rest, v)))
          case s: Sequence if i == s.items.size =>
            s.copy(items = s.items :+ build(rest, v))
          case s: Sequence =>
            throw IndexOutOfBoundsException(s"Index ${i} is out of range for ${s.items.size} items")
          case other if isNull(other) && i == 0 =>
            withAnchor(build(path, v), other.anchorOf)
          case _ =>
            throw IllegalArgumentException(s"Cannot set index ${i}: the node is not a sequence")

  def remove(n: YAMLNode, path: Seq[String | Int]): YAMLNode =
    (n, path) match
      case (m: Mapping, Seq(k: String)) =>
        val entries = m.entries.filterNot(e => keyOf(e.key).contains(k))
        if entries.size == m.entries.size then
          m
        else
          m.copy(entries = entries)
      case (m: Mapping, (k: String) +: rest) =>
        m.entries.indexWhere(e => keyOf(e.key).contains(k)) match
          case -1 =>
            m
          case i =>
            val e     = m.entries(i)
            val value = remove(e.value, rest)
            if value eq e.value then
              m
            else
              m.copy(entries = m.entries.updated(i, e.copy(value = value)))
      case (s: Sequence, Seq(i: Int)) if i >= 0 && i < s.items.size =>
        s.copy(items = s.items.patch(i, Nil, 1))
      case (s: Sequence, (i: Int) +: rest) if rest.nonEmpty && i >= 0 && i < s.items.size =>
        val item = remove(s.items(i), rest)
        if item eq s.items(i) then
          s
        else
          s.copy(items = s.items.updated(i, item))
      case _ =>
        n

  /**
    * Change the node, which resolves to `old`, so that it resolves to `now`
    */
  def update(n: YAMLNode, old: JSONValue, now: JSONValue): YAMLNode =
    if old == now then
      n
    else
      (n, old, now) match
        case (m: Mapping, o: JSONObject, w: JSONObject) if m.entries.forall(e => plainKey(e.key)) =>
          val kept = m
            .entries
            .flatMap { e =>
              val k = keyOf(e.key).get
              w.get(k)
                .map { x =>
                  e.copy(value = update(e.value, o.get(k).getOrElse(JSONNull()), x))
                }
            }
          val existing = m.entries.flatMap(e => keyOf(e.key)).toSet
          val added    = w
            .v
            .collect {
              case (k, x) if !existing.contains(k) =>
                YAMLEntry(YAMLWriter.key(k), YAMLWriter.fromJSON(x))
            }
          m.copy(entries = kept ++ added)
        case (s: Sequence, JSONArray(o), JSONArray(w)) =>
          val items = w
            .indices
            .map { i =>
              if i < s.items.size then
                update(s.items(i), o.lift(i).getOrElse(JSONNull()), w(i))
              else
                YAMLWriter.fromJSON(w(i))
            }
          s.copy(items = items)
        case _ =>
          replace(n, now)

  /**
    * Replace a node with a new value. A scalar keeps its style when it can, and the text around it,
    * such as a comment on the same line. The anchor stays, so aliases to it still resolve.
    */
  private def replace(n: YAMLNode, v: JSONValue): YAMLNode =
    val node = YAMLWriter.fromJSON(v)
    (n, node) match
      case (old: Scalar, s: Scalar) =>
        val style =
          v match
            case JSONString(_) if old.style != ScalarStyle.Plain =>
              old.style
            case _ =>
              s.style
        s.copy(style = style, anchor = old.anchorOf, source = old.source.detached)
      case (old @ (_: Scalar | _: Alias), s) if !isBlock(s) =>
        withAnchor(s, old.anchorOf).withSource(old.source.detached)
      case (old @ (Mapping(_, true, _, _, _) | Sequence(_, true, _, _, _)), s) if !isBlock(s) =>
        withAnchor(s, old.anchorOf).withSource(old.source.detached)
      case (old, s) =>
        withAnchor(s, old.anchorOf)

  private def isBlock(n: YAMLNode): Boolean =
    n match
      case m: Mapping =>
        !m.flow && m.entries.nonEmpty
      case s: Sequence =>
        !s.flow && s.items.nonEmpty
      case _ =>
        false

  private def withAnchor(n: YAMLNode, anchor: Option[String]): YAMLNode =
    if anchor.isEmpty then
      n
    else
      n match
        case s: Scalar =>
          s.copy(anchor = anchor)
        case m: Mapping =>
          m.copy(anchor = anchor)
        case s: Sequence =>
          s.copy(items = s.items, anchor = anchor)
        case a: Alias =>
          a

  /**
    * A new node holding `v` at the path, with mappings and sequences created on the way
    */
  private def build(path: Seq[String | Int], v: JSONValue): YAMLNode =
    path match
      case Seq() =>
        YAMLWriter.fromJSON(v)
      case (k: String) +: rest =>
        Mapping(Seq(YAMLEntry(YAMLWriter.key(k), build(rest, v))))
      case (i: Int) +: rest if i == 0 =>
        Sequence(Seq(build(rest, v)))
      case (i: Int) +: _ =>
        throw IndexOutOfBoundsException(s"Index ${i} is out of range for a new sequence")

  private def keyOf(k: YAMLNode): Option[String] =
    k match
      case s: Scalar =>
        Some(s.value)
      case _ =>
        None

  private def plainKey(k: YAMLNode): Boolean = keyOf(k).exists(_ != "<<")

  private def isNull(n: YAMLNode): Boolean =
    n match
      case s: Scalar =>
        s.style == ScalarStyle.Plain && s.tagOf.isEmpty && YAMLParser.isNull(s.value)
      case _ =>
        false

end YAMLEdit
