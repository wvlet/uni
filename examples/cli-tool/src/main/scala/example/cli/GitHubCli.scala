package example.cli

import wvlet.uni.cli.launcher.*
import wvlet.uni.http.{Http, HttpHeader, HttpSyncClient, Request}
import wvlet.uni.log.{LogLevel, LogSupport, Logger}
import wvlet.uni.weaver.Weaver

/**
  * A subset of the GitHub REST API repository response. Weaver matches JSON keys flexibly, so
  * `stargazers_count` fills `stargazersCount`, and fields not listed here are skipped.
  */
case class Repo(
    fullName: String,
    description: Option[String] = None,
    language: Option[String] = None,
    stargazersCount: Int = 0,
    forksCount: Int = 0,
    htmlUrl: String = ""
) derives Weaver:
  def summary: String =
    s"""${fullName} — ${description.getOrElse("(no description)")}
       |  language: ${language.getOrElse("-")}, stars: ${stargazersCount}, forks: ${forksCount}
       |  ${htmlUrl}""".stripMargin

/** Calls the GitHub API with uni's HTTP client. Non-2xx responses throw HttpException. */
class GitHubClient(baseUri: String) extends LogSupport with AutoCloseable:
  private val client: HttpSyncClient = Http.client.withBaseUri(baseUri).newSyncClient

  def getRepo(ownerAndName: String): Repo =
    debug(s"GET /repos/${ownerAndName}")
    val response = client.send(
      Request
        .get(s"/repos/${ownerAndName}")
        .withAccept("application/vnd.github+json")
        .withUserAgent("uni-example-cli")
        // Ask for an uncompressed body: the Scala Native client does not yet decode gzip
        // (https://github.com/wvlet/uni/issues/769)
        .addHeader(HttpHeader.AcceptEncoding, "identity")
    )
    Weaver.fromJson[Repo](response.contentAsString.getOrElse("{}"))

  override def close(): Unit = client.close()

@command(description = "Look up GitHub repositories")
class GitHubCli(
    @option(prefix = "-v,--verbose", description = "Print debug logs")
    verbose: Boolean = false,
    @option(prefix = "--api", description = "GitHub API base URI")
    api: String = "https://api.github.com"
) extends LogSupport:
  if verbose then
    Logger.setDefaultLogLevel(LogLevel.DEBUG)

  @command(description = "Show a repository summary")
  def repo(
      @argument(name = "owner/name", description = "Repository, e.g. wvlet/uni")
      ownerAndName: String,
      @option(prefix = "--json", description = "Print the result as JSON")
      json: Boolean = false
  ): Unit =
    val repo = withClient(_.getRepo(ownerAndName))
    if json then
      println(Weaver.toJson(repo))
    else
      println(repo.summary)

  @command(description = "Compare stars of several repositories")
  def stars(
      @argument(name = "repos", description = "Repositories, e.g. wvlet/uni wvlet/airframe")
      repos: Seq[String]
  ): Unit = withClient { gh =>
    repos
      .map(gh.getRepo)
      .sortBy(-_.stargazersCount)
      .foreach { r =>
        println(f"${r.stargazersCount}%7d  ${r.fullName}")
      }
  }

  private def withClient[A](body: GitHubClient => A): A =
    val gh = GitHubClient(api)
    try body(gh)
    finally gh.close()

end GitHubCli

object GitHubCli:
  def main(args: Array[String]): Unit = Launcher
    .of[GitHubCli]
    .withShowHelpOnNoArgs(true)
    .execute(args)
