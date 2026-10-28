package example.cli

import wvlet.uni.cli.launcher.Launcher
import wvlet.uni.test.*
import wvlet.uni.weaver.Weaver

class GitHubCliTest extends UniTest:
  test("decode a GitHub API response, skipping unknown fields") {
    val json =
      """{"id":1,"full_name":"wvlet/uni","description":"Scala 3 utilities","language":"Scala",
        |"stargazers_count":42,"forks_count":3,"html_url":"https://github.com/wvlet/uni",
        |"owner":{"login":"wvlet"}}""".stripMargin
    val repo = Weaver.fromJson[Repo](json)
    repo.fullName shouldBe "wvlet/uni"
    repo.stargazersCount shouldBe 42
    repo.language shouldBe Some("Scala")
    repo.summary shouldContain "stars: 42"
  }

  test("missing optional fields fall back to defaults") {
    val repo = Weaver.fromJson[Repo]("""{"full_name":"a/b"}""")
    repo.description shouldBe None
    repo.forksCount shouldBe 0
  }

  test("--help prints help without calling the API") {
    val out = java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      Launcher.of[GitHubCli].execute(Array("--help")).showedHelp shouldBe true
      Launcher.of[GitHubCli].execute(Array("repo", "--help")).showedHelp shouldBe true
    }
    out.toString shouldContain "usage: uni-gh"
    out.toString shouldContain "usage: repo"
  }
