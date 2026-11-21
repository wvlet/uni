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
package wvlet.uni.cloud

import wvlet.uni.storage.*

import wvlet.uni.util.Sha256

import wvlet.uni.test.UniTest

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Checks the pure-Scala RSA signer against java.security */
class RsaJvmTest extends UniTest:
  private def pem(der: Array[Byte], label: String): String =
    val b64 = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)
    s"-----BEGIN ${label}-----\n${b64}\n-----END ${label}-----\n"

  test("PKCS#8 keys sign like java.security SHA256withRSA") {
    Seq(1024, 2048, 3072).foreach { bits =>
      val gen = KeyPairGenerator.getInstance("RSA")
      gen.initialize(bits)
      val kp  = gen.generateKeyPair()
      val key = Rsa.parsePem(pem(kp.getPrivate.getEncoded, "PRIVATE KEY"))
      key.modulus shouldBe kp.getPublic.asInstanceOf[RSAPublicKey].getModulus
      Seq("", "a", "hello world", "x" * 10000).foreach { msg =>
        val data = msg.getBytes("UTF-8")
        val jdk  = Signature.getInstance("SHA256withRSA")
        jdk.initSign(kp.getPrivate)
        jdk.update(data)
        val ours = Rsa.signSha256(key, data)
        ours.toSeq shouldBe jdk.sign().toSeq

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(kp.getPublic)
        verifier.update(data)
        verifier.verify(ours) shouldBe true
      }
    }
  }

  test("PKCS#1 keys parse too") {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    val kp    = gen.generateKeyPair()
    val pkcs8 = kp.getPrivate.getEncoded
    // The PKCS#1 RSAPrivateKey is the OCTET STRING inside PKCS#8
    val info  = Der.read(pkcs8, 0)
    val inner = info.children(pkcs8)(2).content(pkcs8)
    val key1  = Rsa.parsePem(pem(inner, "RSA PRIVATE KEY"))
    val key8  = Rsa.parsePem(pem(pkcs8, "PRIVATE KEY"))
    val data  = "same".getBytes("UTF-8")
    Rsa.signSha256(key1, data).toSeq shouldBe Rsa.signSha256(key8, data).toSeq
  }

end RsaJvmTest
