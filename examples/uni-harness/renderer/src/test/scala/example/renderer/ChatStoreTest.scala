package example.renderer

import example.api.{ChatApi, ChatMessage, ChatReply, Conversation}
import example.server.ChatService
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

/**
  * ChatStore against the real ChatService, in-process. A gate holds chosen responses back, so the
  * tests can make RPC results arrive late or out of order, as they can over IPC or HTTP.
  */
class ChatStoreTest extends UniTest:

  /** Releases each listMessages response, and the model's replies, only when the test says so. */
  class Gated(service: ChatService) extends ChatApi:
    val listGates = mutable.Map.empty[String, Promise[Unit]]

    def holdList(id: String): Unit                              = listGates(id) = Promise[Unit]()
    def releaseList(id: String): Unit                           = listGates(id).trySuccess(())
    override def listConversations()                            = service.listConversations()
    override def createConversation()                           = service.createConversation()
    override def sendMessage(id: String, text: String)          = service.sendMessage(id, text)
    override def listMessages(id: String): Rx[Seq[ChatMessage]] =
      listGates.get(id) match
        case Some(gate) =>
          // Read the thread when the gate opens, as a slow server would
          Rx.future(gate.future).flatMap(_ => service.listMessages(id))
        case None =>
          service.listMessages(id)

  private val replies = mutable.Queue.empty[Promise[String]]

  /** A model whose replies wait until the test completes them. */
  private def heldModel(prompt: String): Future[String] =
    val p = Promise[String]()
    replies.enqueue(p)
    p.future

  private def instant(prompt: String): Future[String] = Future.successful(s"echo: ${prompt}")

  test("load opens the most recent conversation") {
    val store = ChatStore(ChatService(respond = instant))
    store
      .load()
      .map { _ =>
        store.conversations.get.size shouldBe 1
        store.selected.get shouldBe store.conversations.get.headOption.map(_.id)
        store.messages.get shouldBe Seq.empty
      }
  }

  test("send stores the reply, renames the conversation, and marks the reply fresh once") {
    val store = ChatStore(ChatService(respond = instant))
    store
      .load()
      .flatMap(_ => store.send("Hello Uni"))
      .map { _ =>
        store.messages.get.map(m => (m.role, m.text)) shouldBe
          Seq("user" -> "Hello Uni", "assistant" -> "echo: Hello Uni")
        store.conversations.get.head.title shouldBe "Hello Uni"
        store.sending.get shouldBe Set.empty
        val reply = store.messages.get.last.id
        store.takeFresh(reply) shouldBe true
        store.takeFresh(reply) shouldBe false
      }
  }

  test("a late listMessages response does not replace the selected thread") {
    val service = ChatService(respond = instant)
    val api     = Gated(service)
    val store   = ChatStore(api)
    for
      _ <- store.load()
      a = store.selected.get.get
      _ <- store.send("in A")
      _ <- store.newChat()
      b = store.selected.get.get
      // Open A, but hold its response; switch to B, whose response arrives first
      _     = api.holdList(a)
      slowA = store.select(a)
      _ <- store.select(b)
      _ = api.releaseList(a)
      _ <- slowA
    yield
      store.selected.get shouldBe Some(b)
      store.messages.get shouldBe Seq.empty
  }

  test("a reply that arrives after the thread was reloaded shows the prompt once") {
    replies.clear()
    val store = ChatStore(ChatService(respond = heldModel))
    for
      _ <- store.load()
      a       = store.selected.get.get
      sending = store.send("only once")
      // Leave and come back while the reply is on its way: the reload already holds the prompt
      _ <- store.newChat()
      _ <- store.select(a)
      _ = replies.dequeue().success("done")
      _ <- sending
    yield store.messages.get.map(_.text) shouldBe Seq("only once", "done")
  }

  test("a second send while a reply is pending is ignored") {
    replies.clear()
    val store = ChatStore(ChatService(respond = heldModel))
    for
      _ <- store.load()
      first = store.send("first")
      _ <- store.send("second")
      _ = replies.dequeue().success("reply")
      _ <- first
    yield
      store.messages.get.map(_.text) shouldBe Seq("first", "reply")
      replies.isEmpty shouldBe true
  }

  test("another conversation can send while one waits for its reply") {
    replies.clear()
    val store = ChatStore(ChatService(respond = heldModel))
    for
      _ <- store.load()
      a   = store.selected.get.get
      inA = store.send("in A")
      _ <- store.newChat()
      b   = store.selected.get.get
      inB = store.send("in B")
      _   = store.sending.get shouldBe Set(a, b)
      _   = replies.foreach(_.success("ok"))
      _ <- inA
      _ <- inB
    yield
      store.sending.get shouldBe Set.empty
      store.messages.get.map(_.text) shouldBe Seq("in B", "ok")
  }

  test("a failed send removes the draft and reports the error") {
    val failing =
      new ChatApi:
        private val service                   = ChatService(respond = instant)
        override def listConversations()      = service.listConversations()
        override def createConversation()     = service.createConversation()
        override def listMessages(id: String) = service.listMessages(id)
        override def sendMessage(id: String, text: String): Rx[ChatReply] = Rx.exception(
          IllegalStateException("server down")
        )
    val store = ChatStore(failing)
    store
      .load()
      .flatMap(_ => store.send("lost"))
      .map { _ =>
        store.messages.get shouldBe Seq.empty
        store.sending.get shouldBe Set.empty
        store.error.get shouldBe Some("sendMessage failed: server down")
      }
  }

end ChatStoreTest
