// #region test
package example.toolkit

import wvlet.uni.http.{Http, Request}
import wvlet.uni.test.*
import wvlet.uni.weaver.Weaver

/** A fixed RepoSource, so the test needs no network. */
class InMemoryRepoSource extends RepoSource:
  var calls                                          = 0
  def fetch(owner: String, name: String): GitHubRepo =
    calls += 1
    GitHubRepo(s"${owner}/${name}", stargazers_count = 42, forks_count = 7)

class RepoServerTest extends UniTest:
  test("serve a repository summary from the wired service") {
    val source = InMemoryRepoSource()
    // A later binding replaces the GitHubRepoSource binding of the production design
    val testDesign = RepoServer.design(ServerConfig(port = 0)).bindInstance[RepoSource](source)

    testDesign.build[RepoServer] { server =>
      val http = Http.client.withBaseUri(s"http://localhost:${server.port}").newSyncClient
      try
        val json = http.send(Request.get("/api/repos/scala/toolkit")).contentAsString.get
        Weaver.fromJson[RepoSummary](json) shouldBe RepoSummary("scala/toolkit", 42, 7)

        // The second request is served from RepoService's cache
        http.send(Request.get("/api/repos/scala/toolkit"))
        source.calls shouldBe 1
      finally
        http.close()
    }
  }

  test("decode GitHub JSON with uPickle and encode the summary with Weaver") {
    val repo = upickle
      .default
      .read[GitHubRepo](
        """{"full_name":"scala/toolkit","stargazers_count":1,"forks_count":2,"private":false}"""
      )
    repo shouldBe GitHubRepo("scala/toolkit", 1, 2)
    Weaver.toJson(RepoSummary(repo.full_name, 1, 2)) shouldContain "\"stars\":1"
  }

end RepoServerTest

// #endregion test
