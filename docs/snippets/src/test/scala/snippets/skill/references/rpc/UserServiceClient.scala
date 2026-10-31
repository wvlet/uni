/* Stands in for the client that sbt-uni generates from example.api.UserService (rpc.md). */
package example.client

import example.api.{User, UserService}
import wvlet.uni.http.HttpSyncClient
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.rx.Rx

object UserServiceClient:
  class SyncClient(http: HttpSyncClient) extends UserService:
    private val rpc                       = RPCClient.of[UserService].newSyncClient(http)
    def getUser(id: Long): User           = rpc.call("getUser", id)
    def createUser(name: String): User    = rpc.call("createUser", name)
    def listUsers(limit: Int): Seq[User]  = rpc.call("listUsers", limit)
    def findUserAsync(id: Long): Rx[User] = Rx.single(getUser(id))
