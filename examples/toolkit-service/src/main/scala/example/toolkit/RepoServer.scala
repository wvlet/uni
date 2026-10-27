package example.toolkit

// #region imports
import sttp.client4.quick.*
import upickle.default.read
import wvlet.uni.design.Design
import wvlet.uni.http.{HttpMethod, HttpServer}
import wvlet.uni.http.netty.{NettyServer, RouterHandler}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

import scala.collection.concurrent.TrieMap
// #endregion imports

// #region source
/** Where repository data comes from. The service depends on this trait, not on sttp. */
trait RepoSource:
  def fetch(owner: String, name: String): GitHubRepo

/** The script's sttp + uPickle code, now behind the trait. */
class GitHubRepoSource extends RepoSource with LogSupport:
  def fetch(owner: String, name: String): GitHubRepo =
    debug(s"GET https://api.github.com/repos/${owner}/${name}")
    val response = quickRequest.get(uri"https://api.github.com/repos/${owner}/${name}").send()
    if !response.code.isSuccess then
      throw IllegalStateException(s"GitHub returned ${response.code} for ${owner}/${name}")
    read[GitHubRepo](response.body)

// #endregion source

// #region service
/** What our own API returns. Uni's server encodes it with Weaver. */
case class RepoSummary(name: String, stars: Int, forks: Int) derives Weaver

class RepoService(source: RepoSource) extends LogSupport:
  private val cache = TrieMap.empty[String, RepoSummary]

  def summary(owner: String, name: String): RepoSummary = cache.getOrElseUpdate(
    s"${owner}/${name}", {
      val repo = source.fetch(owner, name)
      info(s"Fetched ${repo.full_name}")
      RepoSummary(repo.full_name, repo.stargazers_count, repo.forks_count)
    }
  )

// #endregion service

// #region controller
class RepoController(service: RepoService):
  @Endpoint(HttpMethod.GET, "/api/repos/:owner/:name")
  def get(owner: String, name: String): RepoSummary = service.summary(owner, name)

// #endregion controller

// #region server
case class ServerConfig(port: Int = 8080)

class RepoServer(config: ServerConfig, controller: RepoController) extends LogSupport:
  private val handler = RouterHandler.withControllers(Router.of[RepoController], controller)
  private var server: Option[HttpServer] = None

  def start(): Unit =
    val s = NettyServer.withPort(config.port).withRxHandler(handler).start()
    info(s"Listening on http://localhost:${s.localPort}")
    server = Some(s)

  def port: Int                = server.map(_.localPort).getOrElse(config.port)
  def awaitTermination(): Unit = server.foreach(_.awaitTermination())
  def stop(): Unit             = server.foreach(_.stop())

object RepoServer:
  def design(config: ServerConfig = ServerConfig()): Design = Design
    .newDesign
    .bindInstance[ServerConfig](config)
    .bindImpl[RepoSource, GitHubRepoSource]
    .bindSingleton[RepoService]
    .bindSingleton[RepoController]
    .bindSingleton[RepoServer]
    .onStart(_.start())
    .onShutdown(_.stop())

  def main(args: Array[String]): Unit =
    val port = args.headOption.map(_.toInt).getOrElse(8080)
    design(ServerConfig(port)).build[RepoServer] { server =>
      server.awaitTermination()
    }

// #endregion server
