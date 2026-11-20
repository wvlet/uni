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
package wvlet.uni.http

/**
  * An immutable, case-insensitive multi-map for HTTP headers and query parameters. Supports
  * multiple values per key while providing case-insensitive key lookup.
  *
  * Entries are kept in insertion order, in arrays: a request carries tens of headers, so a linear
  * scan over lower-case keys finds one faster than hashing, and building the map costs a few array
  * slots instead of two hash maps. Maps with more than [[HttpMultiMap.IndexThreshold]] keys also
  * get a hash index, so lookups and builds stay linear in the number of keys.
  */
class HttpMultiMap private (
    // Slots [0, n) hold each distinct key: its lookup form, its first spelling, and its values in
    // the order they were added. The arrays are never written after construction.
    private val lowerKeys: Array[String],
    private val originalKeys: Array[String],
    private val values: Array[List[String]],
    private val n: Int
):
  import HttpMultiMap.*

  // Built on first lookup of a large map. Racing threads build equal indexes, so no lock is needed.
  @volatile
  private var index: java.util.HashMap[String, Integer] = null

  def isEmpty: Boolean  = n == 0
  def nonEmpty: Boolean = n != 0
  def size: Int         = n

  /** The slot of a lookup-form key, or -1 */
  private def slotOf(lowerKey: String): Int =
    if n <= IndexThreshold then
      var i = 0
      while i < n do
        if lowerKeys(i) == lowerKey then
          return i
        i += 1
      -1
    else
      var idx = index
      if idx == null then
        idx = buildIndex(lowerKeys, n)
        index = idx
      val slot = idx.get(lowerKey)
      if slot == null then
        -1
      else
        slot.intValue

  /**
    * Get the first value for a key (case-insensitive)
    */
  def get(key: String): Option[String] =
    val i = slotOf(lower(key))
    if i < 0 then
      None
    else
      Some(values(i).head)

  /**
    * Get all values for a key (case-insensitive)
    */
  def getAll(key: String): Seq[String] =
    val i = slotOf(lower(key))
    if i < 0 then
      Nil
    else
      values(i)

  /**
    * Get the first value or a default
    */
  def getOrElse(key: String, default: => String): String =
    val i = slotOf(lower(key))
    if i < 0 then
      default
    else
      values(i).head

  /**
    * Check if a key exists (case-insensitive)
    */
  def contains(key: String): Boolean = slotOf(lower(key)) >= 0

  /**
    * Add a value to a key, preserving existing values
    */
  def add(key: String, value: String): HttpMultiMap =
    val lowerKey = lower(key)
    val i        = slotOf(lowerKey)
    if i >= 0 then
      withValues(i, values(i) :+ value)
    else
      appended(lowerKey, key, value :: Nil)

  /**
    * Set a key to a single value, replacing any existing values
    */
  def set(key: String, value: String): HttpMultiMap =
    val lowerKey = lower(key)
    val i        = slotOf(lowerKey)
    if i >= 0 then
      withValues(i, value :: Nil)
    else
      appended(lowerKey, key, value :: Nil)

  /**
    * Remove all values for a key (case-insensitive)
    */
  def remove(key: String): HttpMultiMap =
    val i = slotOf(lower(key))
    if i < 0 then
      this
    else
      val lk = Array.ofDim[String](n - 1)
      val ok = Array.ofDim[String](n - 1)
      val vs = Array.ofDim[List[String]](n - 1)
      System.arraycopy(lowerKeys, 0, lk, 0, i)
      System.arraycopy(originalKeys, 0, ok, 0, i)
      System.arraycopy(values, 0, vs, 0, i)
      System.arraycopy(lowerKeys, i + 1, lk, i, n - i - 1)
      System.arraycopy(originalKeys, i + 1, ok, i, n - i - 1)
      System.arraycopy(values, i + 1, vs, i, n - i - 1)
      new HttpMultiMap(lk, ok, vs, n - 1)

  private def withValues(i: Int, vs: List[String]): HttpMultiMap =
    val copy = java.util.Arrays.copyOf(values, n)
    copy(i) = vs
    new HttpMultiMap(lowerKeys, originalKeys, copy, n)

  private def appended(lowerKey: String, key: String, vs: List[String]): HttpMultiMap =
    val lk = java.util.Arrays.copyOf(lowerKeys, n + 1)
    val ok = java.util.Arrays.copyOf(originalKeys, n + 1)
    val v  = java.util.Arrays.copyOf(values, n + 1)
    lk(n) = lowerKey
    ok(n) = key
    v(n) = vs
    new HttpMultiMap(lk, ok, v, n + 1)

  /** A builder that starts from this map's entries, for adding several without a copy each */
  private[http] def toBuilder: HttpMultiMap.Builder = HttpMultiMap.Builder(this)

  /**
    * Add a key-value pair (alias for add)
    */
  def +(entry: (String, String)): HttpMultiMap = add(entry._1, entry._2)

  /**
    * Add all entries from another map
    */
  def ++(other: HttpMultiMap): HttpMultiMap =
    if other.isEmpty then
      this
    else if isEmpty then
      other
    else
      val b = Builder(this)
      other.foreachEntry((k, v) => b.add(k, v))
      b.result()

  /**
    * Call `f` with each (key, value) pair, in insertion order, without building the pairs. Keys use
    * original casing.
    */
  def foreachEntry(f: (String, String) => Unit): Unit =
    var i = 0
    while i < n do
      val k  = originalKeys(i)
      var vs = values(i)
      while vs.nonEmpty do
        f(k, vs.head)
        vs = vs.tail
      i += 1

  /**
    * Get all entries as a sequence of (key, value) pairs, in insertion order. Keys use original
    * casing.
    */
  def entries: Seq[(String, String)] =
    val b = Vector.newBuilder[(String, String)]
    foreachEntry((k, v) => b += (k -> v))
    b.result()

  /**
    * Get all keys (in original casing), in insertion order
    */
  def keys: Iterable[String] = originalKeys.view.take(n).toVector

  /**
    * Convert to a raw Map (lowercase keys)
    */
  def toMap: Map[String, Seq[String]] =
    val b = Map.newBuilder[String, Seq[String]]
    var i = 0
    while i < n do
      b += lowerKeys(i) -> values(i)
      i += 1
    b.result()

  override def toString: String = entries
    .map { case (k, v) =>
      s"${k}: ${v}"
    }
    .mkString(", ")

end HttpMultiMap

// The companion's apply takes entries, so construction below uses `new`
object HttpMultiMap:
  /** Maps with more keys than this get a hash index for lookups */
  private[http] final val IndexThreshold = 16

  private def buildIndex(lowerKeys: Array[String], n: Int): java.util.HashMap[String, Integer] =
    val idx = java.util.HashMap[String, Integer](n * 2)
    var i   = 0
    while i < n do
      idx.put(lowerKeys(i), i)
      i += 1
    idx

  val empty: HttpMultiMap = new HttpMultiMap(Array.empty, Array.empty, Array.empty, 0)

  def apply(entries: (String, String)*): HttpMultiMap = fromSeq(entries)

  def fromSeq(entries: Seq[(String, String)]): HttpMultiMap =
    val b = newBuilder
    entries.foreach(e => b.add(e._1, e._2))
    b.result()

  def fromMap(map: Map[String, String]): HttpMultiMap =
    val b = newBuilder
    map.foreach((k, v) => b.add(k, v))
    b.result()

  def fromMultiMap(map: Map[String, Seq[String]]): HttpMultiMap =
    val b = newBuilder
    map.foreach((k, vs) => vs.foreach(v => b.add(k, v)))
    b.result()

  /**
    * The lookup form of a key. Header names are ASCII, so ASCII keys lower-case without
    * `String.toLowerCase`, and come back unchanged when already lower-case. Scala Native's
    * `toLowerCase` copies every string through a `StringBuilder`, and every add, get, and contains
    * goes through here. Other keys use `toLowerCase`, as before.
    */
  private[http] def lower(key: String): String =
    var i     = 0
    var upper = false
    while i < key.length do
      val c = key.charAt(i)
      if c >= 0x80 then
        return key.toLowerCase
      if c >= 'A' && c <= 'Z' then
        upper = true
      i += 1
    if !upper then
      key
    else
      val known = HttpHeader.lowerCaseNames.get(key)
      if known != null then
        known
      else
        lowerAscii(key)

  /**
    * `a.regionMatches(true, aFrom, b, 0, b.length)`, without Scala Native's Unicode case tables for
    * ASCII chars: header names and codings are ASCII, and each table lookup is a search. Other
    * chars fold as `String.equalsIgnoreCase` does.
    */
  private[http] def regionMatchesIgnoreCase(a: String, aFrom: Int, b: String): Boolean =
    if aFrom < 0 || aFrom + b.length > a.length then
      false
    else
      var i = 0
      while i < b.length && sameIgnoringCase(a.charAt(aFrom + i), b.charAt(i)) do
        i += 1
      i == b.length

  /** `a.equalsIgnoreCase(b)`, with the ASCII fast path of [[regionMatchesIgnoreCase]] */
  private[http] def equalsIgnoreCase(a: String, b: String): Boolean =
    a.length == b.length && regionMatchesIgnoreCase(a, 0, b)

  private def sameIgnoringCase(x: Char, y: Char): Boolean =
    if x == y then
      true
    else if (x | y) < 0x80 then
      val lx = x | 0x20
      lx == (y | 0x20) && lx >= 'a' && lx <= 'z'
    else
      Character.toLowerCase(Character.toUpperCase(x)) ==
        Character.toLowerCase(Character.toUpperCase(y))

  private[http] def lowerAscii(key: String): String =
    val chars = key.toCharArray
    var i     = 0
    while i < chars.length do
      val c = chars(i)
      if c >= 'A' && c <= 'Z' then
        chars(i) = (c + 32).toChar
      i += 1
    String(chars)

  /**
    * Collects entries in insertion-ordered arrays and hands them to the map without copying. Adding
    * after [[result]] copies first, so a built map never changes.
    */
  class Builder private[HttpMultiMap] (
      private var lowerKeys: Array[String],
      private var originalKeys: Array[String],
      private var values: Array[List[String]],
      private var n: Int
  ):
    def this() = this(Array.ofDim(8), Array.ofDim(8), Array.ofDim(8), 0)

    private[HttpMultiMap] def this(from: HttpMultiMap) =
      this(
        java.util.Arrays.copyOf(from.lowerKeys, from.n + 8),
        java.util.Arrays.copyOf(from.originalKeys, from.n + 8),
        java.util.Arrays.copyOf(from.values, from.n + 8),
        from.n
      )
      if from.n > IndexThreshold then
        index = buildIndex(lowerKeys, from.n)

    // Set by result(): the arrays then belong to a map
    private var shared = false
    // Lookup-form key -> slot, once there are more than IndexThreshold keys
    private var index: java.util.HashMap[String, Integer] = null

    private def slotOf(lowerKey: String): Int =
      if index != null then
        val slot = index.get(lowerKey)
        if slot == null then
          -1
        else
          slot.intValue
      else
        var i = 0
        while i < n do
          if lowerKeys(i) == lowerKey then
            return i
          i += 1
        -1

    def add(key: String, value: String): Builder =
      if shared then
        lowerKeys = lowerKeys.clone()
        originalKeys = originalKeys.clone()
        values = values.clone()
        shared = false
      val lowerKey = lower(key)
      val i        = slotOf(lowerKey)
      if i >= 0 then
        values(i) = values(i) :+ value
      else
        if n == lowerKeys.length then
          val size = math.max(8, n * 2)
          lowerKeys = java.util.Arrays.copyOf(lowerKeys, size)
          originalKeys = java.util.Arrays.copyOf(originalKeys, size)
          values = java.util.Arrays.copyOf(values, size)
        lowerKeys(n) = lowerKey
        originalKeys(n) = key
        values(n) = value :: Nil
        if index != null then
          index.put(lowerKey, n)
        n += 1
        if index == null && n > IndexThreshold then
          index = buildIndex(lowerKeys, n)
      this

    def +=(entry: (String, String)): Builder = add(entry._1, entry._2)

    def ++=(entries: Seq[(String, String)]): Builder =
      entries.foreach(e => add(e._1, e._2))
      this

    def result(): HttpMultiMap =
      if n == 0 then
        empty
      else
        shared = true
        new HttpMultiMap(lowerKeys, originalKeys, values, n)

  end Builder

  def newBuilder: Builder = Builder()

end HttpMultiMap
