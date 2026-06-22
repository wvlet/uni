/*
 * Compile-checked examples from Book Chapter 3 — Wiring with Design.
 * These mirror the chapter's snippets so CI catches API drift. They are
 * compiled, not run; bodies that would do I/O are inert.
 */
package wvlet.uni.book

import wvlet.uni.design.Design

object Ch03Design:
  class Database:
    def query(sql: String): Seq[String] = Seq("alice", "bob")

  class UserService(db: Database):
    def listUsers(): Seq[String] = db.query("select name from users")

  trait UserRepo
  class InMemoryUserRepo extends UserRepo

  case class Config(url: String)

  class Server:
    def start(): Unit = ()
    def stop(): Unit  = ()

  class FakeDatabase extends Database

  def firstWiring(): Unit =
    val design = Design.newDesign
      .bindSingleton[Database]
      .bindSingleton[UserService]
    design.build[UserService] { users =>
      println(users.listUsers())
    }

  def theFourBindings(): Unit =
    Design.newDesign.bindSingleton[Database]
    Design.newDesign.bindInstance[Database](Database())
    Design.newDesign.bindImpl[UserRepo, InMemoryUserRepo]
    Design.newDesign
      .bindInstance[Config](Config("jdbc:db"))
      .bindProvider[Config, Database] { config => Database() }

  def sessionsAndLifecycle(): Unit =
    val design = Design.newDesign
      .bindSingleton[Server]
      .onStart(_.start())
      .onShutdown(_.stop())
    design.build[Server] { server => () }

  def overriding(): Unit =
    val appDesign = Design.newDesign
      .bindSingleton[Database]
      .bindSingleton[UserService]
    val testDesign = appDesign +
      Design.newDesign.bindInstance[Database](FakeDatabase())
    testDesign.build[UserService] { users => () }
