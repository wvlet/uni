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
package wvlet.uni.io

/**
  * Immutable representation of POSIX file permission bits (rwxrwxrwx / octal).
  *
  * Supports construction from octal integer or string, individual permission checks, set operations
  * (union, intersection, diff), and string/octal formatting.
  *
  * @param bits
  *   The 9-bit permission value (0-511 / 0o000-0o777)
  */
case class PermSet(bits: Int):
  require(
    (bits & ~PermSet.PermissionMask) == 0,
    s"Permission bits must be in range 0-511 (0o000-0o777), got: ${bits}"
  )

  def ownerRead: Boolean    = (bits & PermSet.OwnerRead) != 0
  def ownerWrite: Boolean   = (bits & PermSet.OwnerWrite) != 0
  def ownerExecute: Boolean = (bits & PermSet.OwnerExecute) != 0
  def groupRead: Boolean    = (bits & PermSet.GroupRead) != 0
  def groupWrite: Boolean   = (bits & PermSet.GroupWrite) != 0
  def groupExecute: Boolean = (bits & PermSet.GroupExecute) != 0
  def otherRead: Boolean    = (bits & PermSet.OtherRead) != 0
  def otherWrite: Boolean   = (bits & PermSet.OtherWrite) != 0
  def otherExecute: Boolean = (bits & PermSet.OtherExecute) != 0

  /**
    * Returns the union of this and another permission set.
    */
  def |(other: PermSet): PermSet = PermSet(bits | other.bits)

  /**
    * Returns the intersection of this and another permission set.
    */
  def &(other: PermSet): PermSet = PermSet(bits & other.bits)

  /**
    * Returns this permission set with the bits from `other` removed.
    */
  def diff(other: PermSet): PermSet = PermSet(bits & ~other.bits)

  /**
    * Returns the octal string representation (e.g., "755").
    */
  def toOctalString: String =
    val owner = (bits >> 6) & 7
    val group = (bits >> 3) & 7
    val other = bits & 7
    s"${owner}${group}${other}"

  /**
    * Returns the POSIX string representation (e.g., "rwxr-xr-x").
    */
  def toPermString: String =
    val sb = StringBuilder()
    sb.append(
      if ownerRead then
        'r'
      else
        '-'
    )
    sb.append(
      if ownerWrite then
        'w'
      else
        '-'
    )
    sb.append(
      if ownerExecute then
        'x'
      else
        '-'
    )
    sb.append(
      if groupRead then
        'r'
      else
        '-'
    )
    sb.append(
      if groupWrite then
        'w'
      else
        '-'
    )
    sb.append(
      if groupExecute then
        'x'
      else
        '-'
    )
    sb.append(
      if otherRead then
        'r'
      else
        '-'
    )
    sb.append(
      if otherWrite then
        'w'
      else
        '-'
    )
    sb.append(
      if otherExecute then
        'x'
      else
        '-'
    )
    sb.result()

  end toPermString

  override def toString: String = toPermString

end PermSet

object PermSet:
  /** 9-bit mask for POSIX permission bits (0o777). */
  val PermissionMask: Int = 0x1ff

  val OwnerRead: Int    = 0x100 // 0o400
  val OwnerWrite: Int   = 0x080 // 0o200
  val OwnerExecute: Int = 0x040 // 0o100
  val GroupRead: Int    = 0x020 // 0o040
  val GroupWrite: Int   = 0x010 // 0o020
  val GroupExecute: Int = 0x008 // 0o010
  val OtherRead: Int    = 0x004 // 0o004
  val OtherWrite: Int   = 0x002 // 0o002
  val OtherExecute: Int = 0x001 // 0o001

  /** Mask for any execute bit (owner | group | other). */
  val AnyExecute: Int = OwnerExecute | GroupExecute | OtherExecute

  val empty: PermSet = PermSet(0)
  val all: PermSet   = PermSet(PermissionMask)

  /**
    * Creates a PermSet from a POSIX permission string like "rwxr-xr-x".
    *
    * @throws IllegalArgumentException
    *   if the string is not exactly 9 characters or contains invalid characters
    */
  def apply(s: String): PermSet =
    require(s.length == 9, s"Permission string must be exactly 9 characters, got: \"${s}\"")
    var bits = 0
    // Owner
    bits |= parseBit(s(0), 'r', OwnerRead)
    bits |= parseBit(s(1), 'w', OwnerWrite)
    bits |= parseBit(s(2), 'x', OwnerExecute)
    // Group
    bits |= parseBit(s(3), 'r', GroupRead)
    bits |= parseBit(s(4), 'w', GroupWrite)
    bits |= parseBit(s(5), 'x', GroupExecute)
    // Other
    bits |= parseBit(s(6), 'r', OtherRead)
    bits |= parseBit(s(7), 'w', OtherWrite)
    bits |= parseBit(s(8), 'x', OtherExecute)
    PermSet(bits)

  /**
    * Creates a PermSet from an octal string like "755" or "0755". Accepts 1-4 digit octal strings;
    * special bits (setuid/setgid/sticky) in the leading digit are masked off.
    */
  def fromOctalString(s: String): PermSet =
    require(
      s.length >= 1 && s.length <= 4 && s.forall(c => c >= '0' && c <= '7'),
      s"Invalid octal permission string: \"${s}\""
    )
    PermSet(Integer.parseInt(s, 8) & PermissionMask)

  private def parseBit(c: Char, expected: Char, bit: Int): Int =
    if c == expected then
      bit
    else if c == '-' then
      0
    else
      throw IllegalArgumentException(
        s"Invalid permission character '${c}', expected '${expected}' or '-'"
      )

end PermSet
