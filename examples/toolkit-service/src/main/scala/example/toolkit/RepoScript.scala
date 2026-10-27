// #region script
//> using toolkit 0.9.2
package example.toolkit

import sttp.client4.quick.*
import upickle.default.*

case class GitHubRepo(full_name: String, stargazers_count: Int, forks_count: Int) derives ReadWriter

@main
def repoScript(owner: String, name: String): Unit =
  val response = quickRequest.get(uri"https://api.github.com/repos/${owner}/${name}").send()
  val repo     = read[GitHubRepo](response.body)
  println(s"${repo.full_name}: ${repo.stargazers_count} stars, ${repo.forks_count} forks")

// #endregion script
