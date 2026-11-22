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

import wvlet.uni.cloud.*
import wvlet.uni.util.Sha256

import wvlet.uni.test.UniTest

/** V4 signed URLs checked against Google's conformance vectors */
class GcsSignedUrlTest extends UniTest:
  import GcsSignedUrlTest.*

  private lazy val key = Rsa.parsePem(pem)

  private lazy val signer =
    new GoogleSigner:
      def serviceAccountEmail: String          = email
      def sign(data: Array[Byte]): Array[Byte] = Rsa.signSha256(key, data)

  test("parse the conformance service-account key") {
    key.modulus.bitLength() shouldBe 2048
    key.toString shouldNotContain "BEGIN"
  }

  // Each vector signs with RSA, which takes ~0.3 seconds in Native's debug build. The URL
  // canonicalization is shared Scala code that the JVM checks against every vector, so Native
  // checks a few to cover its BigInteger arithmetic.
  private val checkedVectors =
    if isScalaNative then
      vectors.take(3)
    else
      vectors

  checkedVectors.foreach { v =>
    test(s"conformance: ${v.description}") {
      val url = GcsSignedUrl.sign(
        v.method,
        "https",
        "storage.googleapis.com",
        GcsSignedUrl.path(ObjectUri(v.bucket, v.obj, "gs")),
        signer,
        v.expiration,
        query = v.query,
        headers = v.headers,
        epochMillis = AwsTime.parseIso(v.timestamp).get
      )
      url shouldBe v.expectedUrl
    }
  }

end GcsSignedUrlTest

object GcsSignedUrlTest:
  case class Vector(
      description: String,
      method: String,
      bucket: String,
      obj: String,
      headers: Seq[(String, String)],
      query: Seq[(String, String)],
      timestamp: String,
      expiration: Long,
      expectedUrl: String
  )

  // From googleapis/conformance-tests storage/v1/v4_signatures.json (Apache 2.0)
  val vectors = Seq(
    Vector(
      "Simple GET",
      "GET",
      "test-bucket",
      "test-object",
      Seq(),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host&X-Goog-Signature=95e6a13d43a1d1962e667f17397f2b80ac9bdd1669210d5e08e0135df9dff4e56113485dbe429ca2266487b9d1796ebdee2d7cf682a6ef3bb9fbb4c351686fba90d7b621cf1c4eb1fdf126460dd25fa0837dfdde0a9fd98662ce60844c458448fb2b352c203d9969cb74efa4bdb742287744a4f2308afa4af0e0773f55e32e92973619249214b97283b2daa14195244444e33f938138d1e5f561088ce8011f4986dda33a556412594db7c12fc40e1ff3f1bedeb7a42f5bcda0b9567f17f65855f65071fabb88ea12371877f3f77f10e1466fff6ff6973b74a933322ff0949ce357e20abe96c3dd5cfab42c9c83e740a4d32b9e11e146f0eb3404d2e975896f74"
    ),
    Vector(
      "Simple PUT",
      "PUT",
      "test-bucket",
      "test-object",
      Seq(),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host&X-Goog-Signature=8adff1d4285739e31aa68e73767a46bc5511fde377497dbe08481bf5ceb34e29cc9a59921748d8ec3dd4085b7e9b7772a952afedfcdaecb3ae8352275b8b7c867f204e3db85076220a3127a8a9589302fc1181eae13b9b7fe41109ec8cdc93c1e8bac2d7a0cc32a109ca02d06957211326563ab3d3e678a0ba296e298b5fc5e14593c99d444c94724cc4be97015dbff1dca377b508fa0cb7169195de98d0e4ac96c42b918d28c8d92d33e1bd125ce0fb3cd7ad2c45dae65c22628378f6584971b8bf3945b26f2611eb651e9b6a8648970c1ecf386bb71327b082e7296c4e1ee2fc0bdd8983da80af375c817fb1ad491d0bc22c0f51dba0d66e2cffbc90803e47"
    ),
    Vector(
      "POST for resumable uploads",
      "POST",
      "test-bucket",
      "test-object",
      Seq("X-Goog-Resumable" -> "start"),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host%3Bx-goog-resumable&X-Goog-Signature=4a6d39b23343cedf4c30782aed4b384001828c79ffa3a080a481ea01a640dea0a0ceb58d67a12cef3b243c3f036bb3799c6ee88e8db3eaf7d0bdd4b70a228d0736e07eaa1ee076aff5c6ce09dff1f1f03a0d8ead0d2893408dd3604fdabff553aa6d7af2da67cdba6790006a70240f96717b98f1a6ccb24f00940749599be7ef72aaa5358db63ddd54b2de9e2d6d6a586eac4fe25f36d86fc6ab150418e9c6fa01b732cded226c6d62fc95b72473a4cc55a8257482583fe66d9ab6ede909eb41516a8690946c3e87b0f2052eb0e97e012a14b2f721c42e6e19b8a1cd5658ea36264f10b9b1ada66b8ed5bf7ed7d1708377ac6e5fe608ae361fb594d2e5b24c54"
    ),
    Vector(
      "Vary expiration and timestamp",
      "GET",
      "test-bucket",
      "test-object",
      Seq(),
      Seq(),
      "2019-03-01T09:00:00Z",
      20,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190301%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190301T090000Z&X-Goog-Expires=20&X-Goog-SignedHeaders=host&X-Goog-Signature=9669ed5b10664dc594c758296580662912cf4bcc5a4ba0b6bf055bcbf6f34eed7bdad664f534962174a924741a0c273a4f67bc1847cef20192a6beab44223bd9d4fbbd749c407b79997598c30f82ddc269ff47ec09fa3afe74e00616d438df0d96a7d8ad0adacfad1dc3286f864d924fe919fb0dce45d3d975c5afe8e13af2db9cc37ba77835f92f7669b61e94c6d562196c1274529e76cfff1564cc2cad7d5387dc8e12f7a5dfd925685fe92c30b43709eee29fa2f66067472cee5423d1a3a4182fe8cea75c9329d181dc6acad7c393cd04f8bf5bc0515127d8ebd65d80c08e19ad03316053ea60033fd1b1fd85a69c576415da3bf0a3718d9ea6d03e0d66f0"
    ),
    Vector(
      "Vary bucket and object",
      "GET",
      "test-bucket2",
      "test-object2",
      Seq(),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket2/test-object2?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host&X-Goog-Signature=36e3d58dfd3ec1d2dd2f24b5ee372a71e811ffaa2162a2b871d26728d0354270bc116face87127532969c4a3967ed05b7309af741e19c7202f3167aa8c2ac420b61417d6451442bb91d7c822cd17be8783f01e05372769c88913561d27e6660dd8259f0081a71f831be6c50283626cbf04494ac10c394b29bb3bce74ab91548f58a37118a452693cf0483d77561fc9cac8f1765d2c724994cca46a83517a10157ee0347a233a2aaeae6e6ab5e204ff8fc5f54f90a3efdb8301d9fff5475d58cd05b181affd657f48203f4fb133c3a3d355b8eefbd10d5a0a5fd70d06e9515460ad74e22334b2cba4b29cae4f6f285cdb92d8f3126d7a1479ca3bdb69c207d860"
    ),
    Vector(
      "Slashes in object name should not be URL encoded",
      "GET",
      "test-bucket",
      "path/with/slashes/under_score/amper&sand/file.ext",
      Seq("header/name/with/slash" -> "should-be-encoded"),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/path/with/slashes/under_score/amper%26sand/file.ext?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=header%2Fname%2Fwith%2Fslash%3Bhost&X-Goog-Signature=2a9a82e84e39f5d2c0d980514db17f8c3dece473c9a5743d54e8453f9811927b1b99ce548c534cababd8fa339183e75b410e12e32a4c72f5ff176e95651fabed0072e59e7e236eb7e26f52c0ce599db1c47ae07af1a98d20872b6fde23432c0a5fcf4fb2dda735169198c80cd5cc51be9904f7e5eef2cc489ff44ac5697c529e4b34ac08709a7d2e425619377212c64561ed8b4d2fcb70a26e4f9236f995ab4658d240ac85c7a353bae6b2d39d5fc0716afa435a1f6e100db5504612b5e610db370623ab4b8eba3c03c98f23dcb4b9ffd518f2212abb2f93649d25385d71603d470cff0b7631adb9d0849d38609dedb3097761c8f47ec0d57777bb063611c05b"
    ),
    Vector(
      "Simple headers",
      "GET",
      "test-bucket",
      "test-object",
      Seq("BAR" -> "BAR-value", "foo" -> "foo-value"),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=bar%3Bfoo%3Bhost&X-Goog-Signature=68ecd3b008328ed30d91e2fe37444ed7b9b03f28ed4424555b5161980531ef87db1c3a5bc0265aad5640af30f96014c94fb2dba7479c41bfe1c020eb90c0c6d387d4dd09d4a5df8b60ea50eb6b01cdd786a1e37020f5f95eb8f9b6cd3f65a1f8a8a65c9fcb61ea662959efd9cd73b683f8d8804ef4d6d9b2852419b013368842731359d7f9e6d1139032ceca75d5e67cee5fd0192ea2125e5f2955d38d3d50cf116f3a52e6a62de77f6207f5b95aaa1d7d0f8a46de89ea72e7ea30f21286318d7eba0142232b0deb3a1dc9e1e812a981c66b5ffda3c6b01a8a9d113155792309fd53a3acfd054ca7776e8eec28c26480cd1e3c812f67f91d14217f39a606669d"
    ),
    Vector(
      "Headers with colons",
      "GET",
      "test-bucket",
      "test-object",
      Seq("BAR" -> "2023-02-10T03:", "foo" -> "2023-02-10T02:00:00Z"),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=bar%3Bfoo%3Bhost&X-Goog-Signature=30b831c18b5cdef6dceaa476a395a28d80002ad70b4419af8fd63eaad02c2fbb4b4829b3a4e33e7796a9ce92735498dfc20e0fbc177172f7c8ab6a07736512c7c923ef2f28a2e72d727fd61ca89495c9e62d51b93a2f7061451240c909ed8d05a7bcf616c1ad90fa5cdbc27c4724dec6b29db04129b32402db4ddf7b5b554724481bfdbf41cb24c3c6b9e33bb411c864077d6a19a750a90eb5ad9370d2b171df2813c9a864b40b2ee215ae9790d7916155de863708aa5121bca42e4695def5322f3726f8e1a7ec56da7a1a4f6b959253513a10f7edf6594c02340021b8cc709b0177ec6bb127fc2fb705f508bde045ed94603471c19c1c6af165f559a3c4741b"
    ),
    Vector(
      "Headers should be trimmed",
      "GET",
      "test-bucket",
      "test-object",
      Seq(
        "collapsed" -> "abc    def",
        "leading"   -> "    xyz",
        "trailing"  -> "abc    ",
        "tabs"      -> "\tabc\t\t\t\tdef\t"
      ),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=collapsed%3Bhost%3Bleading%3Btabs%3Btrailing&X-Goog-Signature=75d77a3ed2d9b74ff7e1e23b2fd7cc714ad4cc32518c65f3a8197827cd87d302623bab990cf2ff3a633bfaae69b6c2d897add78c105aa68411229610421c4239579add4aff6bdbd5067a0fd61c3aa0029d7de0f8ae88fa3458fa70f875e841d6df9598597d9012b9f848c6857e08f2704ca2f332c71738490ffdda2ed928f9340549d7295745725062d28dc1696eab7cb3b88ac4fd445e951423f645d680a60dd8033d65b65f4c10286f59f4258dbb2bcf36a76ffdd40574104cbbf0b76901c24df5854f24c42e9192fcedc386d85704fec6a6bad3a5201e1fb6c491a4c43371b0913420743580daf3504e99204c6ec894b4d70cd27bc60c3fe2850e8bf3ed22"
    ),
    Vector(
      "Header value with multiple inline values",
      "GET",
      "test-bucket",
      "test-object",
      Seq("multiple" -> " xyz ,  abc, def  , xyz   "),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host%3Bmultiple&X-Goog-Signature=5cc113735625341f59c7203f0c2c9febc95ba6af6b9c38814f8e523214712087dc0996e4960d273ae1889f248ac1e58d4d19cb3a69ad7670e9a8ca1b434e878f59339dc7006cf32dfd715337e9f593e0504371839174962a08294586e0c78160a7aa303397888c8350637c6af3b32ac310886cc4590bfda9ca561ee58fb5b8ec56bc606d2ada6e7df31f4276e9dcb96bcaea39dc2cd096f3fad774f9c4b30e317ad43736c05f76831437f44e8726c1e90d3f6c9827dc273f211f32fc85658dfc5d357eb606743a6b00a29e519eef1bebaf9db3e8f4b1f5f9afb648ad06e60bc42fa8b57025056697c874c9ea76f5a73201c9717ea43e54713ff3502ff3fc626b"
    ),
    Vector(
      "List Objects",
      "GET",
      "test-bucket",
      "",
      Seq(),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host&X-Goog-Signature=6dbe94f8e52b2b8a9a476b1c857efa474e09944e2b52b925800316e094a7169d8dbe0df9c0ac08dabb22ac7e827470ceccd65f5a3eadba2a4fb9beebfe37f0d9bb1e552b851fa31a25045bdf019e507f5feb44f061551ef1aeb18dcec0e38ba2e2f77d560a46eaace9c56ed9aa642281301a9d848b0eb30749e34bc7f73a3d596240533466ff9b5f289cd0d4c845c7d96b82a35a5abd0c3aff83e4440ee6873e796087f43545544dc8c01afe1d79c726696b6f555371e491980e7ec145cca0803cf562c38f3fa1d724242f5dea25aac91d74ec9ddd739ff65523627763eaef25cd1f95ad985aaf0079b7c74eb5bcb2870a9b137a7b2c8e41fbe838c95872f75b"
    ),
    Vector(
      "Query Parameter Encoding",
      "GET",
      "test-bucket",
      "test-object",
      Seq(),
      Seq("aA0é/=%-_.~" -> "~ ._-%=/é0Aa"),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host&aA0%C3%A9%2F%3D%25-_.~=~%20._-%25%3D%2F%C3%A90Aa&X-Goog-Signature=0e4f289c28626a32af95d78f5a0d2c75e5f0bef1cfe5035a532a8185e3ad45b1d5e8ad5ae0fb1898420e40a1576479861c6579db7e3b28ef8386160995ac1374be85e780ac3dfcc62538e9b2e2c51555950fb6fd0d7ecc509d3ccd9c02af5a6c6eb930d21a7383792300eb50a093a597c1da2a290ed56b01844251f8271d0d5e61fc3f4273a0c26f80e061c06124d93346ea1c50388e3fe73494c05ac27a54caedc04d1476c276c7602554e4cc1933e41df31ea523f4009e879c92333f98b4313755470d7e400bbbde9f5bef36f40b2a1c4a85edbd62aaa84a516e3df055d5d3f575b9ea0f1c24f057240852d9e618403e1dd2cd0fa7fc16b071b07322172256"
    ),
    Vector(
      "Query Parameter Ordering",
      "GET",
      "test-bucket",
      "test-object",
      Seq(),
      Seq("prefix" -> "/foo", "X-Goog-Meta-Foo" -> "bar"),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-Meta-Foo=bar&X-Goog-SignedHeaders=host&prefix=%2Ffoo&X-Goog-Signature=a07745e1b3d59b85cbe11aa766df72c22468959e7217615dccb7f030234f66b60b37e480f30725ed51f29816362ca8286c619ebb66448ff1d370be2a4a48aacf20d3d2d6200ed17341a5791baf2ee5cd9c2823adacc6264f66c8a54fa887e1bce3c55cf78fb2f6a52618cf09d6f945f63d148052a7b66a75e075ff5065828a806b84bdc49a42399be7483225c720d5e18a6160f79d815f433e7921694fe1d041099851793c2581db0e5ca503cfb566e414f900ceede5f9b22030edd32ab20b6f7f9fb2afba89098b9364e03397c03a94eac3a140c99979b8786844fb4f6c62c1985378939dd1bbaea8e41b9100dda85a27733171cc78d96ee362ea2c3432f4d8"
    ),
    Vector(
      "Header Ordering",
      "GET",
      "test-bucket",
      "test-object",
      Seq("X-Goog-Date" -> "20190201T090000Z"),
      Seq(),
      "2019-02-01T09:00:00Z",
      10,
      "https://storage.googleapis.com/test-bucket/test-object?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test-iam-credentials%40dummy-project-id.iam.gserviceaccount.com%2F20190201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20190201T090000Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host%3Bx-goog-date&X-Goog-Signature=55a28435997457f1498291e878fd39c5f321973057d2541886020fdfd212b1467d9eeffdc70951ea952d634cb4193e657ed5b7860c46d37f7d904774680a16e518aa9dff273e8441d6893de615eb592e3113d682ad64a87eb0e0c48df17c30f899e7f940ba230530b30f725ab9ec38789682413752de6a026ae69dd858843100645f3ec986aed618d229f8844d378e0e66e907ede6dff7aac56723f51eb830e8877a56100c86a876173424602abefe6c22b6540a2b36634860b2e89137f297cca8f080bdf3433a9d614c5ab2ec84f65412b45516b30500886a2300f23c3423ae0e91546e3471ee08d06894bddc76203a418d46f35bf0b4574f7b24a693fb046c"
    )
  )

  val email = "test-iam-credentials@dummy-project-id.iam.gserviceaccount.com"
  val pem   = Seq(
    "-----BEGIN PRIVATE KEY-----",
    "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCsPzMirIottfQ2",
    "ryjQmPWocSEeGo7f7Q4/tMQXHlXFzo93AGgU2t+clEj9L5loNhLVq+vk+qmnyDz5",
    "Q04y8jVWyMYzzGNNrGRW/yaYqnqlKZCy1O3bmnNjV7EDbC/jE1ZLBY0U3HaSHfn6",
    "S9ND8MXdgD0/ulRTWwq6vU8/w6i5tYsU7n2LLlQTl1fQ7/emO9nYcCFJezHZVa0H",
    "meWsdHwWsok0skwQYQNIzP3JF9BpR5gJT2gNge6KopDesJeLoLzaX7cUnDn+CAnn",
    "LuLDwwSsIVKyVxhBFsFXPplgpaQRwmGzwEbf/Xpt9qo26w2UMgn30jsOaKlSeAX8",
    "cS6ViF+tAgMBAAECggEACKRuJCP8leEOhQziUx8Nmls8wmYqO4WJJLyk5xUMUC22",
    "SI4CauN1e0V8aQmxnIc0CDkFT7qc9xBmsMoF+yvobbeKrFApvlyzNyM7tEa/exh8",
    "DGD/IzjbZ8VfWhDcUTwn5QE9DCoon9m1sG+MBNlokB3OVOt8LieAAREdEBG43kJu",
    "yQTOkY9BGR2AY1FnAl2VZ/jhNDyrme3tp1sW1BJrawzR7Ujo8DzlVcS2geKA9at7",
    "55ua5GbHz3hfzFgjVXDfnkWzId6aHypUyqHrSn1SqGEbyXTaleKTc6Pgv0PgkJjG",
    "hZazWWdSuf1T5Xbs0OhAK9qraoAzT6cXXvMEvvPt6QKBgQDXcZKqJAOnGEU4b9+v",
    "Odoh+nssdrIOBNMu1m8mYbUVYS1aakc1iDGIIWNM3qAwbG+yNEIi2xi80a2RMw2T",
    "9RyCNB7yqCXXVKLBiwg9FbKMai6Vpk2bWIrzahM9on7AhCax/X2AeOp+UyYhFEy6",
    "UFG4aHb8THscL7b515ukSuKb5QKBgQDMq+9PuaB0eHsrmL6q4vHNi3MLgijGg/zu",
    "AXaPygSYAwYW8KglcuLZPvWrL6OG0+CrfmaWTLsyIZO4Uhdj7MLvX6yK7IMnagvk",
    "L3xjgxSklEHJAwi5wFeJ8ai/1MIuCn8p2re3CbwISKpvf7Sgs/W4196P4vKvTiAz",
    "jcTiSYFIKQKBgCjMpkS4O0TakMlGTmsFnqyOneLmu4NyIHgfPb9cA4n/9DHKLKAT",
    "oaWxBPgatOVWs7RgtyGYsk+XubHkpC6f3X0+15mGhFwJ+CSE6tN+l2iF9zp52vqP",
    "Qwkjzm7+pdhZbmaIpcq9m1K+9lqPWJRz/3XXuqi+5xWIZ7NaxGvRjqaNAoGAdK2b",
    "utZ2y48XoI3uPFsuP+A8kJX+CtWZrlE1NtmS7tnicdd19AtfmTuUL6fz0FwfW4Su",
    "lQZfPT/5B339CaEiq/Xd1kDor+J7rvUHM2+5p+1A54gMRGCLRv92FQ4EON0RC1o9",
    "m2I4SHysdO3XmjmdXmfp4BsgAKJIJzutvtbqlakCgYB+Cb10z37NJJ+WgjDt+yT2",
    "yUNH17EAYgWXryfRgTyi2POHuJitd64Xzuy6oBVs3wVveYFM6PIKXlj8/DahYX5I",
    "R2WIzoCNLL3bEZ+nC6Jofpb4kspoAeRporj29SgesK6QBYWHWX2H645RkRGYGpDo",
    "51gjy9m/hSNqBbH2zmh04A==",
    "-----END PRIVATE KEY-----"
  ).mkString("\n")

end GcsSignedUrlTest
