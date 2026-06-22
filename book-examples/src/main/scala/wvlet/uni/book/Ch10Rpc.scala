/* Compile-checked examples from Book Chapter 10 — Typed RPC.
 * The router/handler are built but no server is started. */
package wvlet.uni.book

import wvlet.uni.weaver.Weaver
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.http.netty.{NettyServer, RPCHandler}

object Ch10Rpc:
  case class User(id: Long, name: String, email: String) derives Weaver

  trait UserService:
    def getUser(id: Long): User
    def createUser(name: String, email: String): User

  class UserServiceImpl extends UserService:
    def getUser(id: Long): User                       = User(id, "Alice", "alice@example.com")
    def createUser(name: String, email: String): User = User(1L, name, email)

  def serverConfig =
    val router = RPCRouter.of[UserService](UserServiceImpl())
    NettyServer.withPort(8080).withRxHandler(RPCHandler(router))
