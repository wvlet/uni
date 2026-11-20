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

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IO
import wvlet.uni.io.IOPath
import wvlet.uni.io.ProcessConfig
import wvlet.uni.json.JSON

/** Looks up an environment variable. Tests pass a map so they can point lookups at temp files. */
private[storage] type AwsEnv = String => Option[String]

private[storage] object AwsEnv:
  val system: AwsEnv = StorageEnv.get

  def of(vars: Map[String, String]): AwsEnv = name => vars.get(name).filter(_.nonEmpty)

/** Reads `~/.aws/credentials` and `~/.aws/config` */
private[storage] class AwsProfiles(env: AwsEnv):
  def currentProfile: String = env("AWS_PROFILE").getOrElse("default")

  def home: Option[IOPath] = env("HOME")
    .orElse(env("USERPROFILE"))
    .map(IOPath.parse)
    .orElse(
      if StorageEnv.isBrowser then
        None
      else
        Some(FileSystem.homeDirectory)
    )

  private def credentialsFile: Option[IOPath] = env("AWS_SHARED_CREDENTIALS_FILE")
    .map(IOPath.parse)
    .orElse(home.map(_.resolve(".aws/credentials")))

  private def configFile: Option[IOPath] = env("AWS_CONFIG_FILE")
    .map(IOPath.parse)
    .orElse(home.map(_.resolve(".aws/config")))

  private def read(path: Option[IOPath]): Map[String, Map[String, String]] =
    if StorageEnv.isBrowser then
      Map.empty
    else
      path
        .filter(FileSystem.isFile)
        .map(p => AwsProfiles.parseIni(FileSystem.readString(p)))
        .getOrElse(Map.empty)

  private lazy val config: Map[String, Map[String, String]] = read(configFile)

  /** Profiles from the config file, whose sections read `[profile name]` (`[default]` as is) */
  private def configProfile(profile: String): Map[String, String] = config.getOrElse(
    s"profile ${profile}",
    config.getOrElse(profile, Map.empty)
  )

  /**
    * A profile's settings from both files. The credentials file wins for a key set in both, as in
    * the AWS SDKs.
    */
  def profile(name: String): Map[String, String] =
    configProfile(name) ++ read(credentialsFile).getOrElse(name, Map.empty)

  /** The `region` of a profile in the config file */
  def configRegion(profile: String): Option[String] = configProfile(profile)
    .get("region")
    .filter(_.nonEmpty)

  /** An `[sso-session name]` section of the config file */
  def ssoSession(name: String): Option[Map[String, String]] = config.get(s"sso-session ${name}")

  /** The region for STS calls: `AWS_REGION`, `AWS_DEFAULT_REGION`, then the profile's `region` */
  def stsRegion(profile: String): Option[String] = env("AWS_REGION")
    .orElse(env("AWS_DEFAULT_REGION"))
    .orElse(configProfile(profile).get("region"))
    .filter(_.nonEmpty)

end AwsProfiles

private[storage] object AwsProfiles:
  def currentProfile: String = AwsProfiles(AwsEnv.system).currentProfile

  /** The region set for a profile in the config file */
  def region(profile: String = currentProfile): Option[String] = AwsProfiles(AwsEnv.system)
    .configRegion(profile)

  /** A minimal INI reader: `[section]` headers and `key = value` lines */
  def parseIni(text: String): Map[String, Map[String, String]] =
    val sections = scala.collection.mutable.LinkedHashMap.empty[String, Map[String, String]]
    var current  = ""
    text
      .linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#") && !l.startsWith(";"))
      .foreach { line =>
        if line.startsWith("[") && line.endsWith("]") then
          current = line.substring(1, line.length - 1).trim.replaceAll("\\s+", " ")
          sections.getOrElseUpdate(current, Map.empty)
        else
          val eq = line.indexOf('=')
          if eq > 0 then
            val key   = line.substring(0, eq).trim.toLowerCase
            val value = line.substring(eq + 1).trim
            sections(current) = sections.getOrElse(current, Map.empty) + (key -> value)
      }
    sections.toMap

end AwsProfiles

/**
  * Resolves a profile to credentials, choosing the source in the AWS Java SDK v2 order (see
  * [[AwsCredentialsProvider.profile]]). A configured source that fails throws a `StorageException`
  * whose message names the profile and the fix but never a token, secret, or command line.
  */
private[storage] class AwsProfileCredentials(env: AwsEnv):
  private val profiles = AwsProfiles(env)

  private def fail(msg: String): Nothing =
    throw StorageException(StorageErrorCode.NoCredentials, msg)

  def resolve(name: String): Option[AwsCredentials] = resolve(name, Nil)

  private def resolve(name: String, visited: List[String]): Option[AwsCredentials] =
    val p = profiles.profile(name)
    if p.isEmpty then
      None
    else if p.contains("role_arn") && p.contains("web_identity_token_file") then
      Some(webIdentity(name, p))
    else if isSso(p) then
      Some(AwsSso(env, profiles).credentials(name, p))
    else if p.contains("role_arn") then
      Some(assumeRole(name, p, visited))
    else if p.contains("credential_process") then
      Some(AwsCredentialProcess.run(name, p("credential_process")))
    else
      staticKeys(p)

  private def isSso(p: Map[String, String]): Boolean = Seq(
    "sso_session",
    "sso_start_url",
    "sso_account_id",
    "sso_role_name"
  ).exists(p.contains)

  private def staticKeys(p: Map[String, String]): Option[AwsCredentials] =
    for
      id     <- p.get("aws_access_key_id").filter(_.nonEmpty)
      secret <- p.get("aws_secret_access_key").filter(_.nonEmpty)
    yield AwsCredentials(id, secret, p.get("aws_session_token").filter(_.nonEmpty))

  private def sessionName(p: Map[String, String]): String = p
    .get("role_session_name")
    .orElse(env("AWS_ROLE_SESSION_NAME"))
    .getOrElse(AwsSts.defaultSessionName)

  private def durationSeconds(name: String, p: Map[String, String]): Option[Int] = p
    .get("duration_seconds")
    .map { d =>
      d.toIntOption
        .getOrElse(fail(s"Profile ${name} has a duration_seconds that is not a number: ${d}"))
    }

  private def sts(name: String) = AwsSts(env, profiles.stsRegion(name))

  private def webIdentity(name: String, p: Map[String, String]): AwsCredentials = sts(name)
    .assumeRoleWithWebIdentity(
      roleArn = p("role_arn"),
      sessionName = sessionName(p),
      token = AwsSts.readToken(p("web_identity_token_file")),
      durationSeconds = durationSeconds(name, p)
    )

  private def assumeRole(
      name: String,
      p: Map[String, String],
      visited: List[String]
  ): AwsCredentials =
    if p.contains("mfa_serial") then
      fail(
        s"Profile ${name} needs an MFA code (mfa_serial), which uni can't prompt for; " +
          "pass temporary credentials explicitly"
      )
    val source =
      (p.get("source_profile"), p.get("credential_source")) match
        case (Some(_), Some(_)) =>
          fail(s"Profile ${name} sets both source_profile and credential_source")
        case (Some(src), None) if src == name =>
          // A profile may source itself: its own static keys assume its role
          staticKeys(p).getOrElse(
            fail(s"Profile ${name} sources itself but has no aws_access_key_id")
          )
        case (Some(src), None) =>
          if visited.contains(src) then
            fail(
              s"Profile ${name} has a source_profile loop: ${(name :: visited)
                  .reverse
                  .mkString(" -> ")} -> ${src}"
            )
          resolve(src, name :: visited).getOrElse(
            fail(s"The source_profile ${src} of profile ${name} has no credentials")
          )
        case (None, Some(cs)) =>
          credentialSource(name, cs)
        case (None, None) =>
          fail(
            s"Profile ${name} sets role_arn without source_profile, credential_source, or " +
              "web_identity_token_file"
          )
    sts(name).assumeRole(
      source = source,
      roleArn = p("role_arn"),
      sessionName = sessionName(p),
      externalId = p.get("external_id"),
      durationSeconds = durationSeconds(name, p)
    )

  end assumeRole

  private def credentialSource(name: String, source: String): AwsCredentials =
    val provider =
      source match
        case "Environment" =>
          AwsCredentialsProvider.EnvironmentProvider(env)
        case "Ec2InstanceMetadata" =>
          AwsCredentialsProvider.InstanceMetadataProvider(env)
        case "EcsContainer" =>
          AwsCredentialsProvider.ContainerProvider(env)
        case other =>
          fail(
            s"Profile ${name} has an unknown credential_source: ${other} " +
              "(use Environment, Ec2InstanceMetadata, or EcsContainer)"
          )
    provider
      .credentials
      .getOrElse(fail(s"The credential_source ${source} of profile ${name} has no credentials"))

end AwsProfileCredentials

/**
  * Runs a profile's `credential_process` through the platform shell (`sh -c`, or `cmd.exe /C` on
  * Windows), as the AWS Java SDK v2 and Go SDK do, and reads the `Version: 1` JSON it prints.
  */
private[storage] object AwsCredentialProcess:
  def run(profile: String, command: String): AwsCredentials =
    def fail(msg: String): Nothing =
      throw StorageException(
        StorageErrorCode.NoCredentials,
        s"credential_process of profile ${profile} ${msg}"
      )
    val shell =
      if StorageEnv.isWindows then
        Seq("cmd.exe", "/C", command)
      else
        Seq("sh", "-c", command)
    // IO.run, not IO.call: call's exception carries the command line and stderr, which may hold
    // secrets
    val result =
      try
        IO.run(shell, ProcessConfig.default)
      catch
        case e: UnsupportedOperationException =>
          fail("can't run on this platform")
        case e: Exception =>
          fail(s"could not start (${e.getClass.getSimpleName})")
    if !result.isSuccess then
      fail(s"exited with code ${result.exitCode}")
    parse(result.stdout).getOrElse(fail("printed no valid credentials JSON"))

  /** Parses the process output. Without `Expiration`, the credentials never refresh. */
  def parse(json: String): Option[AwsCredentials] = AwsJson
    .parseObject(json)
    .filter(o => AwsJson.long(o, "Version").contains(1L))
    .flatMap { o =>
      for
        id     <- AwsJson.str(o, "AccessKeyId")
        secret <- AwsJson.str(o, "SecretAccessKey")
      yield AwsCredentials(
        id,
        secret,
        AwsJson.str(o, "SessionToken"),
        AwsJson.str(o, "Expiration").flatMap(AwsTime.parseIso)
      )
    }

end AwsCredentialProcess

/**
  * Reads credential JSON. A parse error's message may quote its input, which holds secrets, so
  * every credential source parses through here and a parse failure becomes `None`.
  */
private[storage] object AwsJson:
  def parseObject(json: String): Option[JSON.JSONObject] =
    try
      JSON.parse(json) match
        case o: JSON.JSONObject =>
          Some(o)
        case _ =>
          None
    catch
      case e: Exception =>
        None

  /** A non-empty string field */
  def str(o: JSON.JSONObject, key: String): Option[String] = o
    .get(key)
    .collect { case JSON.JSONString(s) =>
      s
    }
    .filter(_.nonEmpty)

  def long(o: JSON.JSONObject, key: String): Option[Long] = o
    .get(key)
    .collect {
      case JSON.JSONLong(v) =>
        v
      case JSON.JSONDouble(v) =>
        v.toLong
    }

end AwsJson
