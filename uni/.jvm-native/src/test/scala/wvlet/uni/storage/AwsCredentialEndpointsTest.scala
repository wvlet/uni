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

import wvlet.uni.http.Http
import wvlet.uni.http.HttpServer
import wvlet.uni.http.HttpStatus
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.io.PermSet
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest
import wvlet.uni.util.URLEncoder

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
  * Web identity, AssumeRole chaining, and SSO against a local server that plays STS, the SSO
  * portal, and SSO-OIDC. Runs on the JVM and Native; on Node.js a server in the same thread can't
  * answer the synchronous client.
  */
class AwsCredentialEndpointsTest extends UniTest:
  FileSystemInit.init()

  private val webToken   = "web-identity-token-must-not-leak"
  private val ssoToken   = "sso-access-token"
  private val ssoRenewed = "sso-access-token-renewed"

  /** What the fake endpoints received, for assertions */
  private val requests = ConcurrentLinkedQueue[(String, Map[String, String], Option[String])]()

  private def form(body: String): Map[String, String] =
    body
      .split("&")
      .filter(_.nonEmpty)
      .map { kv =>
        val i = kv.indexOf('=')
        URLEncoder.decode(kv.substring(0, i)) -> URLEncoder.decode(kv.substring(i + 1))
      }
      .toMap

  private def stsCredentials(action: String, id: String, expirationMillis: Long): Response =
    Response
      .ok
      .withTextContent(s"""<${action}Response xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
         |<${action}Result><Credentials>
         |<AccessKeyId>${id}</AccessKeyId><SecretAccessKey>${id}-secret</SecretAccessKey>
         |<SessionToken>${id}-token</SessionToken>
         |<Expiration>${AwsTime.isoSeconds(expirationMillis)}</Expiration>
         |</Credentials></${action}Result></${action}Response>""".stripMargin)

  private def stsError(code: String, message: String): Response = Response(HttpStatus.Forbidden_403)
    .withTextContent(
      s"""<ErrorResponse><Error><Type>Sender</Type><Code>${code}</Code><Message>${message}</Message></Error></ErrorResponse>"""
    )

  private def handle(req: Request): Response =
    val body = req.content.toContentString
    req.path match
      case "/" =>
        val f = form(body)
        requests.add(("sts", f, req.header("Authorization")))
        val roleName = f.getOrElse("RoleArn", "").split('/').last
        f.get("Action") match
          case Some("AssumeRoleWithWebIdentity") =>
            if roleName == "denied" then
              stsError("AccessDenied", "Not authorized to perform sts:AssumeRoleWithWebIdentity")
            else
              // Short-lived, so the cache asks again on every call
              stsCredentials(
                "AssumeRoleWithWebIdentity",
                s"ASIAWEB${roleName.toUpperCase}",
                System.currentTimeMillis() + 60 * 1000L
              )
          case Some("AssumeRole") =>
            // The caller's access key is in the SigV4 Credential scope
            val caller = req
              .header("Authorization")
              .map(_.replaceAll(".*Credential=([^/]+)/.*", "$1"))
              .getOrElse("unsigned")
            stsCredentials(
              "AssumeRole",
              s"ASIA${roleName.toUpperCase}FROM${caller}",
              System.currentTimeMillis() + 3600 * 1000L
            )
          case _ =>
            Response.badRequest
      case "/federation/credentials" =>
        val bearer = req.header("x-amz-sso_bearer_token")
        requests.add(("sso", Map("query" -> req.query.getOrElse("")), bearer))
        if bearer.contains(ssoToken) || bearer.contains(ssoRenewed) then
          Response
            .ok
            .withJsonContent(
              s"""{"roleCredentials": {"accessKeyId": "ASIASSO", "secretAccessKey": "sso-secret",
                 | "sessionToken": "sso-session", "expiration": ${System.currentTimeMillis() +
                  3600 * 1000L}}}""".stripMargin
            )
        else
          Response.unauthorized
      case "/token" =>
        val o                = JSON.parse(body).asInstanceOf[JSON.JSONObject]
        def field(k: String) = o
          .get(k)
          .collect { case JSON.JSONString(s) =>
            s
          }
        requests.add(("oidc", Map("grantType" -> field("grantType").getOrElse("")), None))
        if field("refreshToken").contains("refresh-1") &&
          field("clientSecret").contains("client-secret")
        then
          Response
            .ok
            .withJsonContent(
              s"""{"accessToken": "${ssoRenewed}", "expiresIn": 3600, "refreshToken": "refresh-2",
                 | "tokenType": "Bearer"}""".stripMargin
            )
        else
          Response(HttpStatus.BadRequest_400).withJsonContent("""{"error": "invalid_grant"}""")
      case _ =>
        Response.notFound
    end match

  end handle

  private def withServer[A](body: String => A): A =
    requests.clear()
    val server = Http.server.withPort(0).withHandler(handle).start()
    try body(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  /** A temp home with `~/.aws/config`, and an environment pointing every endpoint at the server */
  private def withHome[A](
      endpoint: String,
      config: String,
      extraEnv: Map[String, String] = Map.empty
  )(body: (IOPath, AwsEnv) => A): A =
    val home = FileSystem.createTempDirectory("uni-aws-endpoints")
    try
      FileSystem.createDirectoryIfNotExists(home.resolve(".aws"))
      FileSystem.writeString(home.resolve(".aws/config"), config)
      val env = AwsEnv.of(
        Map(
          "HOME"                      -> home.path,
          "AWS_EC2_METADATA_DISABLED" -> "true",
          "AWS_ENDPOINT_URL_STS"      -> endpoint,
          "AWS_ENDPOINT_URL_SSO"      -> endpoint,
          "AWS_ENDPOINT_URL_SSO_OIDC" -> endpoint,
          "AWS_REGION"                -> "us-west-2"
        ) ++ extraEnv
      )
      body(home, env)
    finally
      FileSystem.deleteRecursively(home)

  private def stsCalls = requests.asScala.toSeq.filter(_._1 == "sts")

  test("web identity from the environment reads the token file again on each refresh") {
    withServer { endpoint =>
      val tokenFile = FileSystem.createTempFile("uni-web-identity", ".token")
      try
        FileSystem.writeString(tokenFile, s"${webToken}-1\n")
        withHome(
          endpoint,
          "",
          Map(
            "AWS_WEB_IDENTITY_TOKEN_FILE" -> tokenFile.path,
            "AWS_ROLE_ARN"                -> "arn:aws:iam::123456789012:role/irsa",
            "AWS_ROLE_SESSION_NAME"       -> "my-pod"
          )
        ) { (_, env) =>
          val chain = AwsCredentialsProvider.defaultChain(env)
          val c     = chain.credentials.get
          c.accessKeyId shouldBe "ASIAWEBIRSA"
          c.sessionToken shouldBe Some("ASIAWEBIRSA-token")
          (c.expirationMillis.get > System.currentTimeMillis()) shouldBe true

          // EKS rotates the token file; the next refresh must send the new token
          FileSystem.writeString(tokenFile, s"${webToken}-2\n")
          chain.credentials.get.accessKeyId shouldBe "ASIAWEBIRSA"

          val calls = stsCalls
          calls.map(_._2("WebIdentityToken")) shouldBe Seq(s"${webToken}-1", s"${webToken}-2")
          calls.head._2("RoleSessionName") shouldBe "my-pod"
          // Unsigned: the token is the proof
          calls.head._3 shouldBe None
        }
      finally
        FileSystem.deleteIfExists(tokenFile)
    }
  }

  test("a role_arn with web_identity_token_file comes before SSO settings in the same profile") {
    withServer { endpoint =>
      val tokenFile = FileSystem.createTempFile("uni-web-identity", ".token")
      try
        FileSystem.writeString(tokenFile, webToken)
        withHome(
          endpoint,
          s"""[profile ci]
             |role_arn = arn:aws:iam::123456789012:role/ci
             |web_identity_token_file = ${tokenFile.path}
             |role_session_name = ci-job
             |duration_seconds = 900
             |sso_session = corp
             |sso_account_id = 123456789012
             |sso_role_name = ReadOnly
             |""".stripMargin
        ) { (_, env) =>
          AwsCredentialsProvider.profileOf("ci", env).credentials.get.accessKeyId shouldBe
            "ASIAWEBCI"
          val call = stsCalls.head._2
          call("RoleSessionName") shouldBe "ci-job"
          call("DurationSeconds") shouldBe "900"
        }
      finally
        FileSystem.deleteIfExists(tokenFile)
    }
  }

  test("an STS rejection names the AWS error but not the token") {
    withServer { endpoint =>
      val tokenFile = FileSystem.createTempFile("uni-web-identity", ".token")
      try
        FileSystem.writeString(tokenFile, webToken)
        withHome(
          endpoint,
          "",
          Map(
            "AWS_WEB_IDENTITY_TOKEN_FILE" -> tokenFile.path,
            "AWS_ROLE_ARN"                -> "arn:aws:iam::123456789012:role/denied"
          )
        ) { (_, env) =>
          val e = intercept[StorageException] {
            AwsCredentialsProvider.defaultChain(env).credentials
          }
          e.getMessage shouldContain "web-identity: STS AssumeRoleWithWebIdentity"
          e.getMessage shouldContain "AccessDenied"
          e.getMessage.contains(webToken) shouldBe false
          // The default session name is generated
          stsCalls.head._2("RoleSessionName").startsWith("uni-") shouldBe true
        }
      finally
        FileSystem.deleteIfExists(tokenFile)
    }
  }

  test("source_profile chains AssumeRole calls, each signed by the previous credentials") {
    withServer { endpoint =>
      withHome(
        endpoint,
        """[profile base]
          |aws_access_key_id = AKIDBASE
          |aws_secret_access_key = base-secret
          |[profile middle]
          |role_arn = arn:aws:iam::123456789012:role/middle
          |source_profile = base
          |[profile top]
          |role_arn = arn:aws:iam::123456789012:role/top
          |source_profile = middle
          |external_id = ext-123
          |[profile self]
          |role_arn = arn:aws:iam::123456789012:role/self
          |source_profile = self
          |aws_access_key_id = AKIDSELF
          |aws_secret_access_key = self-secret
          |[profile fromenv]
          |role_arn = arn:aws:iam::123456789012:role/fromenv
          |credential_source = Environment
          |""".stripMargin,
        Map("AWS_ACCESS_KEY_ID" -> "AKIDENV", "AWS_SECRET_ACCESS_KEY" -> "env-secret")
      ) { (_, env) =>
        val top = AwsCredentialsProvider.profileOf("top", env).credentials.get
        top.accessKeyId shouldBe "ASIATOPFROMASIAMIDDLEFROMAKIDBASE"
        stsCalls.map(_._2("RoleArn").split('/').last) shouldBe Seq("middle", "top")
        stsCalls.last._2("ExternalId") shouldBe "ext-123"
        stsCalls.forall(_._3.exists(_.contains("/us-west-2/sts/aws4_request"))) shouldBe true

        AwsCredentialsProvider.profileOf("self", env).credentials.get.accessKeyId shouldBe
          "ASIASELFFROMAKIDSELF"
        AwsCredentialsProvider.profileOf("fromenv", env).credentials.get.accessKeyId shouldBe
          "ASIAFROMENVFROMAKIDENV"
      }
    }
  }

  private val ssoConfig =
    """[profile dev]
      |sso_session = corp
      |sso_account_id = 123456789012
      |sso_role_name = ReadOnly
      |[sso-session corp]
      |sso_region = us-east-1
      |sso_start_url = https://example.awsapps.com/start
      |[profile legacy]
      |sso_start_url = https://legacy.awsapps.com/start
      |sso_region = us-east-1
      |sso_account_id = 123456789012
      |sso_role_name = ReadOnly
      |""".stripMargin

  private def writeSsoCache(home: IOPath, key: String, expiresAt: Long, refresh: Boolean): IOPath =
    val dir = home.resolve(".aws/sso/cache")
    FileSystem.createDirectoryIfNotExists(home.resolve(".aws/sso"))
    FileSystem.createDirectoryIfNotExists(dir)
    val file  = dir.resolve(AwsSso.cacheFileName(key))
    val extra =
      if refresh then
        s""", "refreshToken": "refresh-1", "clientId": "client-1", "clientSecret": "client-secret",
           | "registrationExpiresAt": "${AwsTime.isoSeconds(
            System.currentTimeMillis() + 86400 * 1000L
          )}"""".stripMargin
      else
        ""
    FileSystem.writeString(
      file,
      s"""{"startUrl": "https://example.awsapps.com/start", "region": "us-east-1",
         | "accessToken": "${ssoToken}", "expiresAt": "${AwsTime.isoSeconds(expiresAt)}"${extra}}"""
        .stripMargin
    )
    file

  test("an SSO session profile exchanges the cached token for role credentials") {
    withServer { endpoint =>
      withHome(endpoint, ssoConfig) { (home, env) =>
        writeSsoCache(home, "corp", System.currentTimeMillis() + 3600 * 1000L, refresh = true)
        val c = AwsCredentialsProvider.profileOf("dev", env).credentials.get
        c.accessKeyId shouldBe "ASIASSO"
        c.sessionToken shouldBe Some("sso-session")
        val sso = requests.asScala.toSeq.filter(_._1 == "sso")
        sso.head._2("query") shouldBe "role_name=ReadOnly&account_id=123456789012"
        sso.head._3 shouldBe Some(ssoToken)
        // A fresh token is not refreshed
        requests.asScala.exists(_._1 == "oidc") shouldBe false
      }
    }
  }

  test("an expired SSO session token is refreshed and written back owner-only") {
    withServer { endpoint =>
      withHome(endpoint, ssoConfig) { (home, env) =>
        val file = writeSsoCache(home, "corp", System.currentTimeMillis() - 1000L, refresh = true)
        AwsCredentialsProvider.profileOf("dev", env).credentials.get.accessKeyId shouldBe "ASIASSO"
        requests.asScala.toSeq.filter(_._1 == "sso").head._3 shouldBe Some(ssoRenewed)

        val cache            = JSON.parse(FileSystem.readString(file)).asInstanceOf[JSON.JSONObject]
        def field(k: String) = cache
          .get(k)
          .collect { case JSON.JSONString(s) =>
            s
          }
        field("accessToken") shouldBe Some(ssoRenewed)
        field("refreshToken") shouldBe Some("refresh-2")
        // Other fields are kept
        field("clientId") shouldBe Some("client-1")
        (AwsSso.parseExpiry(field("expiresAt").get).get > System.currentTimeMillis()) shouldBe true
        if !StorageEnv.isWindows then
          FileSystem.permissions(file) shouldBe PermSet(AwsSso.OwnerOnly)
        // No temp file is left behind
        FileSystem.list(file.parent.get).map(_.fileName) shouldBe Seq(file.fileName)
      }
    }
  }

  test("an expired legacy SSO token asks for aws sso login") {
    withServer { endpoint =>
      withHome(endpoint, ssoConfig) { (home, env) =>
        writeSsoCache(
          home,
          "https://legacy.awsapps.com/start",
          System.currentTimeMillis() - 1000L,
          refresh = false
        )
        val e = intercept[StorageException] {
          AwsCredentialsProvider.profileOf("legacy", env).credentials
        }
        e.getMessage shouldContain "The SSO token has expired; run `aws sso login --profile legacy`"
        e.getMessage.contains(ssoToken) shouldBe false
      }
    }
  }

end AwsCredentialEndpointsTest
