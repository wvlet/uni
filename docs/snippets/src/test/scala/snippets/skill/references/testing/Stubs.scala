/* Names the testing.md snippets use without defining them. */
package snippets.skill.references.testing

import wvlet.uni.http.HttpSyncClient
import wvlet.uni.http.rpc.RPCClient

case class User(id: Long, name: String)

// Assertions
val x: Int                = 1
val y: Int                = 1
val n: Int                = 1
val opt: Option[Int]      = Some(1)
val seq: Seq[Int]         = Seq(1)
val elem: Int             = 1
val str: String           = "substring"
val v: Option[User]       = Some(User(1, "alice"))
val a: AnyRef             = Seq(1)
val b: AnyRef             = a
def parse(s: String): Int = s.toInt

// Wiring with Design
trait UserRepository:
  def save(name: String): User

class InMemoryUserRepository extends UserRepository:
  def save(name: String): User = User(1, name)

class SignupService(repo: UserRepository):
  def signup(name: String): User = repo.save(name)

// Testing an RPC server: the service of rpc.md and the client sbt-uni generates for it
trait UserService:
  def getUser(id: Long): User

class UserServiceImpl extends UserService:
  def getUser(id: Long): User = User(id, "alice")

object UserServiceClient:
  class SyncClient(http: HttpSyncClient) extends UserService:
    private val rpc             = RPCClient.of[UserService]
    def getUser(id: Long): User = rpc.callSync(http, "getUser", Seq(id))
