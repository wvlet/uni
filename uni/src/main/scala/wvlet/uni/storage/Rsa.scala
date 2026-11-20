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
package wvlet.uni.storage

import java.math.BigInteger
import java.util.Base64

/**
  * An RSA private key with its CRT parameters. `toString` hides every secret part, so a key can't
  * leak into logs.
  */
final class RsaPrivateKey(
    val modulus: BigInteger,
    val publicExponent: BigInteger,
    private[storage] val privateExponent: BigInteger,
    private[storage] val p: BigInteger,
    private[storage] val q: BigInteger,
    private[storage] val dp: BigInteger,
    private[storage] val dq: BigInteger,
    private[storage] val qInv: BigInteger
):
  /** The modulus length in bytes (256 for a 2048-bit key) */
  def length: Int = (modulus.bitLength() + 7) / 8

  override def toString: String = s"RsaPrivateKey(${modulus.bitLength()} bits, ****)"

/**
  * RSASSA-PKCS1-v1_5 with SHA-256 (JWT `RS256`, GCS `GOOG4-RSA-SHA256`) in pure Scala, since Scala
  * Native has no `java.security`. The same code runs on every platform. The private-key operation
  * uses the Chinese remainder theorem and RSA blinding, because `BigInteger.modPow` does not run in
  * constant time.
  */
object Rsa:
  // DER of DigestInfo { AlgorithmIdentifier sha256, NULL } before the 32-byte hash (RFC 8017 9.2)
  private val sha256DigestInfo: Array[Byte] = Array(
    0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05,
    0x00, 0x04, 0x20
  ).map(_.toByte)

  // OID 1.2.840.113549.1.1.1 (rsaEncryption)
  private val rsaEncryptionOid: Array[Byte] = Array(
    0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01
  ).map(_.toByte)

  /**
    * Reads a PEM private key: PKCS#8 (`BEGIN PRIVATE KEY`, as in Google service-account JSON) or
    * PKCS#1 (`BEGIN RSA PRIVATE KEY`)
    */
  def parsePem(pem: String): RsaPrivateKey =
    val isPkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
    if !isPkcs1 && !pem.contains("BEGIN PRIVATE KEY") then
      throw invalidKey("expected a PEM block with BEGIN PRIVATE KEY or BEGIN RSA PRIVATE KEY")
    val body =
      pem.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("-----")).mkString
    val der =
      try
        Base64.getDecoder.decode(body)
      catch
        case _: IllegalArgumentException =>
          throw invalidKey("the PEM body is not valid Base64")
    if isPkcs1 then
      parsePkcs1(der)
    else
      parsePkcs8(der)

  /** PKCS#8 PrivateKeyInfo { version, AlgorithmIdentifier, OCTET STRING RSAPrivateKey } */
  def parsePkcs8(der: Array[Byte]): RsaPrivateKey =
    val info = Der.read(der, 0)
    info.expect(Der.Sequence, "PrivateKeyInfo")
    val parts = info.children(der)
    if parts.length < 3 then
      throw invalidKey("PrivateKeyInfo is too short")
    val alg = parts(1)
    alg.expect(Der.Sequence, "AlgorithmIdentifier")
    val oid = alg.children(der).headOption.getOrElse(throw invalidKey("no algorithm OID"))
    oid.expect(Der.ObjectId, "algorithm OID")
    if !java.util.Arrays.equals(oid.content(der), rsaEncryptionOid) then
      throw invalidKey("not an RSA key")
    val octets = parts(2)
    octets.expect(Der.OctetString, "privateKey")
    parsePkcs1(octets.content(der))

  /** PKCS#1 RSAPrivateKey { version, n, e, d, p, q, dP, dQ, qInv } */
  def parsePkcs1(der: Array[Byte]): RsaPrivateKey =
    val seq = Der.read(der, 0)
    seq.expect(Der.Sequence, "RSAPrivateKey")
    val ints = seq.children(der)
    if ints.length < 9 then
      throw invalidKey("RSAPrivateKey is too short")
    val v = ints
      .take(9)
      .map { t =>
        t.expect(Der.Integer, "RSAPrivateKey field")
        BigInteger(t.content(der))
      }
    if v(0).signum() != 0 then
      throw invalidKey("multi-prime RSA keys are not supported")
    RsaPrivateKey(v(1), v(2), v(3), v(4), v(5), v(6), v(7), v(8))

  /** The RSASSA-PKCS1-v1_5 SHA-256 signature of `message`, `key.length` bytes long */
  def signSha256(key: RsaPrivateKey, message: Array[Byte]): Array[Byte] =
    val k  = key.length
    val em = encode(Sha256.digest(message), k)
    val m  = BigInteger(1, em)
    val r  = blindingFactor(key, message)
    // Blind: m' = m * r^e, sign m', then unblind with r^-1
    val blinded = m.multiply(r.modPow(key.publicExponent, key.modulus)).mod(key.modulus)
    val s       = crt(key, blinded).multiply(r.modInverse(key.modulus)).mod(key.modulus)
    // Check the result, so a computation fault never releases a signature that leaks the key
    if s.modPow(key.publicExponent, key.modulus) != m then
      throw StorageException(StorageErrorCode.InvalidCredentials, "RSA signature check failed")
    toFixedLength(s, k)

  /** Verifies a signature with the public part of `key` (for tests and self-checks) */
  def verifySha256(
      modulus: BigInteger,
      publicExponent: BigInteger,
      message: Array[Byte],
      signature: Array[Byte]
  ): Boolean =
    val k = (modulus.bitLength() + 7) / 8
    if signature.length != k then
      false
    else
      val s = BigInteger(1, signature)
      s.compareTo(modulus) < 0 &&
      java
        .util
        .Arrays
        .equals(
          toFixedLength(s.modPow(publicExponent, modulus), k),
          encode(Sha256.digest(message), k)
        )

  // EMSA-PKCS1-v1_5: 0x00 0x01 FF..FF 0x00 DigestInfo || hash
  private def encode(hash: Array[Byte], k: Int): Array[Byte] =
    val t = sha256DigestInfo ++ hash
    if k < t.length + 11 then
      throw invalidKey("the RSA key is too short")
    val em = Array.fill[Byte](k)(0xff.toByte)
    em(0) = 0
    em(1) = 1
    em(k - t.length - 1) = 0
    System.arraycopy(t, 0, em, k - t.length, t.length)
    em

  private def crt(key: RsaPrivateKey, c: BigInteger): BigInteger =
    val m1 = c.modPow(key.dp, key.p)
    val m2 = c.modPow(key.dq, key.q)
    val h  = key.qInv.multiply(m1.subtract(m2)).mod(key.p)
    m2.add(h.multiply(key.q))

  private var counter = 0L

  /**
    * A blinding factor in [2, n-1] coprime to n. Scala.js and Scala Native have no SecureRandom in
    * every setup, so it is derived with HMAC-SHA256 keyed by the private exponent, over the
    * message, the time, and a counter: it is unpredictable to anyone without the key, which is what
    * blinding needs.
    */
  private def blindingFactor(key: RsaPrivateKey, message: Array[Byte]): BigInteger =
    val hmacKey = key.privateExponent.toByteArray
    val seed    = Sha256.digest(message)
    var result  = Option.empty[BigInteger]
    while result.isEmpty do
      val n = synchronized {
        counter += 1
        counter
      }
      val salt = s"${System.nanoTime()}:${System.currentTimeMillis()}:${n}".getBytes("UTF-8")
      val out  = java.io.ByteArrayOutputStream()
      var i    = 0
      while out.size() < key.length + 8 do
        out.write(Sha256.hmac(hmacKey, seed ++ salt ++ Array(i.toByte)))
        i += 1
      val r = BigInteger(1, out.toByteArray).mod(key.modulus)
      if r.compareTo(BigInteger.valueOf(2)) >= 0 && r.gcd(key.modulus) == BigInteger.ONE then
        result = Some(r)
    result.get

  private def toFixedLength(v: BigInteger, k: Int): Array[Byte] =
    val b = v.toByteArray
    if b.length == k then
      b
    else if b.length == k + 1 && b(0) == 0 then
      b.drop(1)
    else
      val out = new Array[Byte](k)
      System.arraycopy(b, 0, out, k - b.length, b.length)
      out

  private def invalidKey(msg: String): StorageException = StorageException(
    StorageErrorCode.InvalidCredentials,
    s"Invalid RSA private key: ${msg}"
  )

end Rsa

/** A minimal DER reader for the structures of an RSA private key */
private[storage] object Der:
  val Integer: Int     = 0x02
  val OctetString: Int = 0x04
  val Null: Int        = 0x05
  val ObjectId: Int    = 0x06
  val Sequence: Int    = 0x30

  /** One tag-length-value, located in its buffer */
  case class Tlv(tag: Int, start: Int, length: Int):
    def end: Int                             = start + length
    def content(b: Array[Byte]): Array[Byte] = java.util.Arrays.copyOfRange(b, start, end)

    def expect(t: Int, what: String): Unit =
      if tag != t then
        throw StorageException(
          StorageErrorCode.InvalidCredentials,
          f"Invalid RSA private key: ${what} has DER tag 0x${tag}%02x, expected 0x${t}%02x"
        )

    def children(b: Array[Byte]): IndexedSeq[Tlv] =
      val out = IndexedSeq.newBuilder[Tlv]
      var pos = start
      while pos < end do
        val c = read(b, pos, end)
        out += c
        pos = c.end
      out.result()

  def read(b: Array[Byte], pos: Int, limit: Int = -1): Tlv =
    val end =
      if limit < 0 then
        b.length
      else
        limit
    def fail(): Nothing =
      throw StorageException(
        StorageErrorCode.InvalidCredentials,
        "Invalid RSA private key: truncated DER"
      )
    if pos + 2 > end then
      fail()
    val tag   = b(pos) & 0xff
    val first = b(pos + 1) & 0xff
    var i     = pos + 2
    val len   =
      if first < 0x80 then
        first
      else
        val n = first & 0x7f
        if n == 0 || n > 4 || i + n > end then
          fail()
        var l = 0L
        (0 until n).foreach { _ =>
          l = (l << 8) | (b(i) & 0xff)
          i += 1
        }
        if l > Int.MaxValue then
          fail()
        l.toInt
    if i + len > end then
      fail()
    Tlv(tag, i, len)

  end read

end Der
