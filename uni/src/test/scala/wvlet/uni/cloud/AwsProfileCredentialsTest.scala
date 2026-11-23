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

import wvlet.uni.cloud.storage.*

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.test.UniTest

/**
  * Profile resolution that needs no network: static keys, `credential_process`, and the errors of
  * misconfigured profiles. The STS and SSO paths are tested against local servers in
  * `AwsCredentialEndpointsTest` (JVM and Native).
  */
class AwsProfileCredentialsTest extends UniTest:
  FileSystemInit.init()

  private def withHome[A](config: String, credentials: String = "")(
      body: (IOPath, AwsEnv) => A
  ): A =
    val home = FileSystem.createTempDirectory("uni-aws-home")
    try
      FileSystem.createDirectoryIfNotExists(home.resolve(".aws"))
      FileSystem.writeString(home.resolve(".aws/config"), config)
      FileSystem.writeString(home.resolve(".aws/credentials"), credentials)
      body(home, AwsEnv.of(Map("HOME" -> home.path, "AWS_EC2_METADATA_DISABLED" -> "true")))
    finally
      FileSystem.deleteRecursively(home)

  private def resolve(env: AwsEnv, profile: String): Option[AwsCredentials] = AwsProfileCredentials(
    env
  ).resolve(profile)

  private def failure(env: AwsEnv, profile: String): String =
    intercept[CloudException] {
      resolve(env, profile)
    }.getMessage

  /** A shell command that prints the file, for `sh -c` and `cmd.exe /C` alike */
  private def printFile(path: IOPath): String =
    if CloudEnv.isWindows then
      s"type \"${path.path}\""
    else
      s"cat '${path.path}'"

  test("static keys merge both files, the credentials file winning") {
    withHome(
      config =
        """[profile dev]
                 |aws_access_key_id = AKIDCONFIG
                 |aws_secret_access_key = config-secret
                 |region = us-west-2
                 |""".stripMargin,
      credentials =
        """[dev]
                      |aws_access_key_id = AKIDCREDS
                      |""".stripMargin
    ) { (_, env) =>
      val c = resolve(env, "dev").get
      c.accessKeyId shouldBe "AKIDCREDS"
      c.secretAccessKey shouldBe "config-secret"
      resolve(env, "missing") shouldBe None
    }
  }

  test("credential_process wins over static keys and keeps the expiration") {
    val out = FileSystem.createTempFile("uni-cred-process", ".json")
    try
      FileSystem.writeString(
        out,
        """{"Version": 1, "AccessKeyId": "AKIDPROC", "SecretAccessKey": "proc-secret",
          | "SessionToken": "proc-token", "Expiration": "2030-01-02T03:04:05Z"}""".stripMargin
      )
      withHome(s"""[profile tool]
                  |credential_process = ${printFile(out)}
                  |aws_access_key_id = AKIDSTATIC
                  |aws_secret_access_key = static-secret
                  |""".stripMargin) { (_, env) =>
        val c = resolve(env, "tool").get
        c.accessKeyId shouldBe "AKIDPROC"
        c.sessionToken shouldBe Some("proc-token")
        c.expirationMillis shouldBe AwsTime.parseIso("2030-01-02T03:04:05Z")
        c.toString.contains("proc-secret") shouldBe false
        c.toString.contains("proc-token") shouldBe false
      }
    finally
      FileSystem.deleteIfExists(out)
  }

  test("a failing credential_process reports the exit code, not the command or its output") {
    withHome("""[profile tool]
               |credential_process = echo leaked-secret-value && exit 3
               |""".stripMargin) { (_, env) =>
      val msg = failure(env, "tool")
      msg shouldContain "profile tool"
      msg shouldContain "exited with code 3"
      msg.contains("leaked-secret-value") shouldBe false
    }
  }

  test("credential_process output must be Version 1 JSON") {
    AwsCredentialProcess.parse(
      """{"Version": 2, "AccessKeyId": "a", "SecretAccessKey": "b"}"""
    ) shouldBe None
    AwsCredentialProcess.parse("not json") shouldBe None
    AwsCredentialProcess.parse(
      """{"Version": 1, "AccessKeyId": "a", "SecretAccessKey": "b"}"""
    ) shouldBe Some(AwsCredentials("a", "b"))
  }

  test("misconfigured role profiles fail with a clear message") {
    withHome("""[profile a]
               |role_arn = arn:aws:iam::123456789012:role/a
               |source_profile = b
               |[profile b]
               |role_arn = arn:aws:iam::123456789012:role/b
               |source_profile = a
               |[profile mfa]
               |role_arn = arn:aws:iam::123456789012:role/mfa
               |source_profile = a
               |mfa_serial = arn:aws:iam::123456789012:mfa/me
               |[profile nosource]
               |role_arn = arn:aws:iam::123456789012:role/x
               |[profile badsource]
               |role_arn = arn:aws:iam::123456789012:role/x
               |credential_source = Somewhere
               |[profile emptysource]
               |role_arn = arn:aws:iam::123456789012:role/x
               |source_profile = nothing
               |""".stripMargin) { (_, env) =>
      failure(env, "a") shouldContain "source_profile loop: a -> b -> a"
      failure(env, "mfa") shouldContain "mfa_serial"
      failure(env, "nosource") shouldContain "without source_profile"
      failure(env, "badsource") shouldContain "unknown credential_source: Somewhere"
      failure(env, "emptysource") shouldContain "source_profile nothing of profile emptysource"
    }
  }

  test("an SSO profile without a cached token asks for aws sso login") {
    withHome("""[profile sso]
               |sso_session = corp
               |sso_account_id = 123456789012
               |sso_role_name = ReadOnly
               |[sso-session corp]
               |sso_region = us-east-1
               |sso_start_url = https://example.awsapps.com/start
               |[profile nosession]
               |sso_session = undefined
               |sso_account_id = 123456789012
               |sso_role_name = ReadOnly
               |""".stripMargin) { (_, env) =>
      failure(env, "sso") shouldContain "run `aws sso login --profile sso`"
      failure(env, "nosession") shouldContain "sso_session undefined, which is not defined"
    }
  }

  test("the chain's error lists why each configured source failed") {
    withHome("""[profile broken]
               |role_arn = arn:aws:iam::123456789012:role/x
               |""".stripMargin) { (_, env) =>
      val chainEnv: AwsEnv =
        name =>
          if name == "AWS_PROFILE" then
            Some("broken")
          else
            env(name)
      val e = intercept[CloudException] {
        AwsCredentialsProvider.defaultChain(chainEnv).credentials
      }
      e.code shouldBe CloudErrorCode.NoCredentials
      e.getMessage shouldContain "Checked: environment, web-identity, profile broken"
      e.getMessage shouldContain "profile broken: Profile broken sets role_arn without"
    }
  }

  test("SSO cache files are named by the SHA-1 of the session name") {
    AwsSso.cacheFileName("my-sso") shouldBe "0ad374308c5a4e22f723adf10145eafad7c4031c.json"
    AwsSso.parseExpiry("2026-11-20T18:00:00UTC") shouldBe AwsTime.parseIso("2026-11-20T18:00:00Z")
    AwsTime.isoSeconds(AwsTime.parseIso("2026-11-20T18:00:00Z").get) shouldBe "2026-11-20T18:00:00Z"
  }

  test("SSO role credentials carry their epoch-millis expiration") {
    AwsSso.parseRoleCredentials(
      """{"roleCredentials": {"accessKeyId": "AKIDSSO", "secretAccessKey": "s",
        | "sessionToken": "t", "expiration": 1900000000000}}""".stripMargin
    ) shouldBe Some(AwsCredentials("AKIDSSO", "s", Some("t"), Some(1900000000000L)))
  }

end AwsProfileCredentialsTest
