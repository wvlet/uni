package wvlet.uni.util

import wvlet.uni.control.Guard

import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import scala.util.Random

/**
  * Represents a UUIDv7 value.
  *
  * UUIDv7 is a time-ordered UUID that is sortable and includes a millisecond-precision Unix
  * timestamp. Structure:
  * `|--- Unix TS (48 bits) ---|-- ver (4 bits) --|-- rand_a (12 bits) --|-- var (2 bits) --|-- rand_b (62 bits) --|`
  *   - Unix TS: 48-bit big-endian unsigned integer, milliseconds since Unix epoch.
  *   - ver: 4-bit version, set to `0111` (7).
  *   - rand_a: First 12 bits of 74-bit random number.
  *   - var: 2-bit variant, set to `10` (RFC 4122).
  *   - rand_b: Remaining 62 bits of 74-bit random number.
  *
  * @param mostSignificantBits
  *   The most significant 64 bits of the UUIDv7.
  * @param leastSignificantBits
  *   The least significant 64 bits of the UUIDv7.
  */
final class UUIDv7 private[util] (val mostSignificantBits: Long, val leastSignificantBits: Long)
    extends Comparable[UUIDv7]:

  /**
    * Returns the 48-bit timestamp value from the UUIDv7.
    */
  def timestamp: Long = mostSignificantBits >>> 16

  /**
    * Returns the version number (should be 7).
    */
  def version: Int = ((mostSignificantBits & 0x000000000000f000L) >>> 12).toInt

  /**
    * Returns the variant number (should be 2 for RFC 4122).
    */
  def variant: Int = ((leastSignificantBits & 0xc000000000000000L) >>> 62).toInt

  /**
    * Convert this UUIDv7 to java.util.UUID.
    */
  def toUUID: UUID = new UUID(mostSignificantBits, leastSignificantBits)

  /**
    * Returns a compact 22-character Base62 representation of this UUIDv7. The encoding is
    * sort-order preserving and URL-safe.
    */
  def toBase62: String = Base62.encode128bits(mostSignificantBits, leastSignificantBits)

  /**
    * Returns the string representation of this UUIDv7. The format is
    * "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx".
    */
  override def toString: String = toUUID.toString

  override def compareTo(that: UUIDv7): Int =
    if this.mostSignificantBits < that.mostSignificantBits then
      -1
    else if this.mostSignificantBits > that.mostSignificantBits then
      1
    else if this.leastSignificantBits < that.leastSignificantBits then
      -1
    else if this.leastSignificantBits > that.leastSignificantBits then
      1
    else
      0

  override def hashCode(): Int =
    val H = 1234567
    (
      (mostSignificantBits ^ (mostSignificantBits >>> 32)) * H +
        (leastSignificantBits ^ (leastSignificantBits >>> 32))
    ).toInt

  override def equals(other: Any): Boolean =
    other match
      case that: UUIDv7 =>
        this.mostSignificantBits == that.mostSignificantBits &&
        this.leastSignificantBits == that.leastSignificantBits
      case _ =>
        false

end UUIDv7

/**
  * Configurable UUIDv7 generator that encapsulates the state needed for monotonic UUID generation.
  * Each generator instance maintains its own state for thread-safe operation.
  *
  * @param randomSource
  *   The random number generator to use (defaults to SecureRandom)
  */
class UUIDv7Generator(random: Random = SecureRandom.getInstance) extends Guard:

  private val MinTimeMillis = 0L
  private val MaxTimeMillis = (1L << 48) - 1 // 0xFFFFFFFFFFFFL

  // For synchronizing access to lastGeneratedTimestamp and sequence/randomness for monotonicity
  private var lastGeneratedTime = -1L
  // Use 74 bits of randomness. We'll generate 10 bytes (80 bits) and use the needed parts.
  private var lastRandomBytes = new Array[Byte](10)
  random.nextBytes(lastRandomBytes)

  /**
    * Creates a new UUIDv7 instance. This method aims to ensure monotonicity when called rapidly.
    */
  def newUUIDv7(): UUIDv7 =
    val currentTimeMillis = Instant.now().toEpochMilli()
    newUUIDv7(currentTimeMillis)

  /**
    * Creates a new UUIDv7 with a specified timestamp. This method is primarily for testing or
    * specific use cases where timestamp control is needed. It handles potential clock rollbacks and
    * ensures monotonicity by incrementing random bits if the same millisecond is requested multiple
    * times.
    *
    * @param timestampMillis
    *   The timestamp in milliseconds since the Unix epoch.
    * @return
    *   A new UUIDv7 instance.
    */
  def newUUIDv7(timestampMillis: Long): UUIDv7 = guard {
    val ts = math.max(MinTimeMillis, math.min(timestampMillis, MaxTimeMillis))

    if ts > lastGeneratedTime then
      lastGeneratedTime = ts
      random.nextBytes(lastRandomBytes)
    else // Same millisecond or clock moved backwards
      // Increment the 74-bit random part.
      // The 74 random bits are sourced from the 80-bit lastRandomBytes.
      // To increment the 74-bit number by 1, we need to add 2^(80-74) = 2^6 = 64
      // to the 80-bit number represented by lastRandomBytes.
      val incrementAmount = 1L << (lastRandomBytes.length * 8 - 74) // 1L << 6 = 64

      var k                  = lastRandomBytes.length - 1
      var carryFromIncrement = incrementAmount
      while k >= 0 do
        if carryFromIncrement == 0 then
          k = -1 // break loop using k
        else
          val value = (lastRandomBytes(k) & 0xffL) + carryFromIncrement
          lastRandomBytes(k) = value.toByte
          carryFromIncrement = value >>> 8 // Next carry
          k -= 1

      // If carryFromIncrement is still > 0, it means the 80-bit number overflowed.
      // This is highly unlikely with an increment of 64 unless lastRandomBytes was all 0xFFs.
      // If it did overflow, get fresh random bits.
      if carryFromIncrement > 0 then
        random.nextBytes(lastRandomBytes)

    // Construct mostSignificantBits:
    // [ unix_ts_ms (48 bits) ] [ version (4 bits) ] [ rand_a (12 bits) ]
    var msb = ts << 16 // unix_ts_ms
    msb |= (0x7L << 12) // version (0111)
    msb |= (lastRandomBytes(0) & 0xffL) << 4 // rand_a (first 8 bits)
    msb |= (lastRandomBytes(1) & 0xf0L) >>> 4 // rand_a (next 4 bits)

    // Construct leastSignificantBits:
    // [ variant (2 bits) ] [ rand_b (62 bits) ]
    var lsb = (0x2L << 62) // variant (10)

    // rand_b (remaining 62 bits)
    // rand_a used first 12 bits from lastRandomBytes (bytes 0 and first 4 bits of byte 1)
    // rand_b starts from the last 4 bits of byte 1, then bytes 2 through 9 (8 bytes = 64 bits, need 62)

    lsb |= (lastRandomBytes(1) & 0x0fL) << 58 // rand_b (4 bits from byte 1)
    lsb |= (lastRandomBytes(2) & 0xffL) << 50 // rand_b (8 bits from byte 2)
    lsb |= (lastRandomBytes(3) & 0xffL) << 42 // rand_b (8 bits from byte 3)
    lsb |= (lastRandomBytes(4) & 0xffL) << 34 // rand_b (8 bits from byte 4)
    lsb |= (lastRandomBytes(5) & 0xffL) << 26 // rand_b (8 bits from byte 5)
    lsb |= (lastRandomBytes(6) & 0xffL) << 18 // rand_b (8 bits from byte 6)
    lsb |= (lastRandomBytes(7) & 0xffL) << 10 // rand_b (8 bits from byte 7)
    lsb |= (lastRandomBytes(8) & 0xffL) << 2  // rand_b (8 bits from byte 8)
    lsb |= (lastRandomBytes(9) & 0xc0L) >>> 6 // rand_b (last 2 bits from byte 9)
    // Total random bits used: 12 (rand_a) + 4+8*7+2 = 12 + 4 + 56 + 2 = 74 bits

    new UUIDv7(msb, lsb)
  }

end UUIDv7Generator

object UUIDv7:
  // Default generator instance for backward compatibility
  private val defaultGenerator = new UUIDv7Generator()

  /**
    * Creates a new UUIDv7 instance. This method aims to ensure monotonicity when called rapidly.
    */
  def newUUIDv7(): UUIDv7 = defaultGenerator.newUUIDv7()

  /**
    * Creates a new UUIDv7 with a specified timestamp. This method is primarily for testing or
    * specific use cases where timestamp control is needed. It handles potential clock rollbacks and
    * ensures monotonicity by incrementing random bits if the same millisecond is requested multiple
    * times.
    *
    * @param timestampMillis
    *   The timestamp in milliseconds since the Unix epoch.
    * @return
    *   A new UUIDv7 instance.
    */
  def newUUIDv7(timestampMillis: Long): UUIDv7 = defaultGenerator.newUUIDv7(timestampMillis)

  /**
    * Creates a new UUIDv7Generator with default configuration.
    *
    * @return
    *   A new UUIDv7Generator instance.
    */
  def createGenerator(): UUIDv7Generator = new UUIDv7Generator()

  /**
    * Creates a new UUIDv7Generator with a custom random source.
    *
    * @param randomSource
    *   The random number generator to use.
    * @return
    *   A new UUIDv7Generator instance.
    */
  def createGenerator(randomSource: Random): UUIDv7Generator = new UUIDv7Generator(randomSource)

  /**
    * Creates a UUIDv7 from a 22-character Base62 string.
    *
    * @param base62Str
    *   The Base62-encoded UUIDv7 string (22 characters).
    * @return
    *   A UUIDv7 instance.
    * @throws IllegalArgumentException
    *   if the string is not valid Base62 or does not represent a valid UUIDv7.
    */
  def fromBase62(base62Str: String): UUIDv7 =
    val (msb, lsb) = Base62.decode128bits(base62Str)
    val u7         = new UUIDv7(msb, lsb)
    if u7.version != 7 then
      throw IllegalArgumentException(
        s"Invalid UUIDv7 Base62 string: version is ${u7.version}, expected 7"
      )
    if u7.variant != 2 then
      throw IllegalArgumentException(
        s"Invalid UUIDv7 Base62 string: variant is ${u7.variant}, expected 2 (RFC 4122)"
      )
    u7

  /**
    * Creates a UUIDv7 from its string representation.
    *
    * @param uuidStr
    *   The UUID string (e.g., "f81d4fae-7dec-11d0-a765-00a0c91e6bf6").
    * @return
    *   A UUIDv7 instance.
    * @throws IllegalArgumentException
    *   if the string does not represent a valid UUIDv7.
    */
  def fromString(uuidStr: String): UUIDv7 =
    val uuid = UUID.fromString(uuidStr)
    // Validate version and variant if it's supposed to be a strict UUIDv7 parser
    val u7 = new UUIDv7(uuid.getMostSignificantBits, uuid.getLeastSignificantBits)
    if u7.version != 7 then
      throw new IllegalArgumentException(
        s"Invalid UUIDv7 string: version is ${u7.version}, expected 7"
      )
    if u7.variant != 2 then
      // Variant in UUID class is slightly different: 0 for NCS, 2 for RFC4122, 6 for MS, 7 for future
      // Our variant calculation ( (lsb & 0xC000000000000000L) >>> 62 ) directly gives 2 for '10xx...'
      throw new IllegalArgumentException(
        s"Invalid UUIDv7 string: variant is ${u7.variant}, expected 2 (RFC 4122)"
      )
    u7

  /**
    * Creates a UUIDv7 from a java.util.UUID. This method will check if the provided UUID conforms
    * to UUIDv7 structure (version 7, variant 2).
    *
    * @param uuid
    *   The java.util.UUID instance.
    * @return
    *   A UUIDv7 instance.
    * @throws IllegalArgumentException
    *   if the UUID does not represent a valid UUIDv7.
    */
  def fromUUID(uuid: UUID): UUIDv7 =
    val u7 = new UUIDv7(uuid.getMostSignificantBits, uuid.getLeastSignificantBits)
    if u7.version != 7 then
      throw new IllegalArgumentException(
        s"Invalid UUID: version is ${u7.version}, expected 7 for UUIDv7"
      )
    else if u7.variant != 2 then
      throw new IllegalArgumentException(
        s"Invalid UUID: variant is ${u7.variant}, expected 2 (RFC 4122) for UUIDv7"
      )
    else
      u7

  /**
    * Creates a UUIDv7 from a 16-byte array.
    * @param bytes
    *   The 16-byte array.
    * @return
    *   A UUIDv7 instance.
    * @throws IllegalArgumentException
    *   if the byte array is not 16 bytes long or does not represent a valid UUIDv7.
    */
  def fromBytes(bytes: Array[Byte]): UUIDv7 =
    if bytes.length != 16 then
      throw new IllegalArgumentException("Input byte array must be 16 bytes long")
    val bb  = ByteBuffer.wrap(bytes)
    val msb = bb.getLong()
    val lsb = bb.getLong()
    val u7  = new UUIDv7(msb, lsb)
    // Optionally validate version and variant here as well
    if u7.version != 7 || u7.variant != 2 then
      throw new IllegalArgumentException(
        "Bytes do not represent a valid UUIDv7 structure (version/variant mismatch)"
      )
    u7

  /**
    * Returns the byte representation of this UUIDv7.
    * @param u7
    *   The UUIDv7 instance.
    * @return
    *   A 16-byte array.
    */
  def toBytes(u7: UUIDv7): Array[Byte] =
    val bytes = new Array[Byte](16)
    val bb    = ByteBuffer.wrap(bytes)
    bb.putLong(u7.mostSignificantBits)
    bb.putLong(u7.leastSignificantBits)
    bytes

end UUIDv7
