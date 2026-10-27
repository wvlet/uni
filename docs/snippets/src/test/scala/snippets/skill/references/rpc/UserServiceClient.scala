/* Stands in for the client that sbt-uni generates from example.api.UserService (rpc.md). */
package example.client

import example.api.{User, UserService}
import wvlet.uni.http.HttpSyncClient
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.rx.Rx
import wvlet.uni.surface.Surface

object UserServiceClient:
  class SyncClient(http: HttpSyncClient) extends UserService:
    private val rpc = RPCClient.build(Surface.of[UserService], Surface.methodsOf[UserService])
    def getUser(id: Long): User           = rpc.callSync(http, "getUser", Seq(id))
    def createUser(name: String): User    = rpc.callSync(http, "createUser", Seq(name))
    def listUsers(limit: Int): Seq[User]  = rpc.callSync(http, "listUsers", Seq(limit))
    def findUserAsync(id: Long): Rx[User] = Rx.single(getUser(id))
