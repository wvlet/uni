/* Names the unitest.md snippets use without defining them. */
package snippets.core.unitest

// Fragments that use names an earlier `test` fence imported: a test fence is its own class, so its
// imports do not reach later fences
export wvlet.uni.test.{defined, empty}
export wvlet.uni.test.check.{Arbitrary, Gen}

// Test control
def fetchFromNetwork(): String = "expected"
def computeLocally(): Int      = 42
val setupComplete: Boolean     = true
def runTest(): Unit            = ()

// Design Integration
case class User(name: String)

trait UserRepository:
  def save(user: User): Unit
  def findByName(name: String): Option[User]

class InMemoryUserRepository extends UserRepository:
  private val users                          = scala.collection.mutable.Map.empty[String, User]
  def save(user: User): Unit                 = users(user.name) = user
  def findByName(name: String): Option[User] = users.get(name)

trait UserService:
  def createUser(name: String): User

class UserServiceImpl(repo: UserRepository) extends UserService:
  def createUser(name: String): User =
    val user = User(name)
    repo.save(user)
    user

// Property-based testing: the Shrink fence refers to the Point of an earlier test fence
case class Point(x: Int, y: Int)

// A round trip that the derived Shape generator exercises
object Renderer:
  private val shown        = scala.collection.mutable.Map.empty[String, Any]
  def show(a: Any): String =
    val s = a.toString
    shown(s) = a
    s

  def parse(s: String): Any = shown(s)
