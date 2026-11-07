package example.renderer

import example.api.{ChatApi, ChatEvent, ChatMessage}
import example.server.ChatService
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxStream
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
    override def streamMessage(id: String, text: String)        = service.streamMessage(id, text)
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

  /** A model whose reply streams the parts the test pushes, until the test ends or fails it. */
  class HeldStream:
    private val parts            = Rx.variable(Option.empty[String])
    def model(prompt: String)    = parts.filter(_.isDefined).map(_.get)
    def emit(part: String): Unit = parts := Some(part)
    def finish(): Unit           = parts.stop()
    def fail(e: Throwable): Unit = parts.setException(e)

  test("load opens the most recent conversation") {
    val store = ChatStore(ChatService(respond = ChatService.atOnce(instant)))
    store
      .load()
      .map { _ =>
        store.conversations.get.size shouldBe 1
        store.selected.get shouldBe store.conversations.get.headOption.map(_.id)
        store.messages.get shouldBe Seq.empty
      }
  }

  test("send stores the reply and renames the conversation") {
    val store = ChatStore(ChatService(respond = ChatService.atOnce(instant)))
    store
      .load()
      .flatMap(_ => store.send("Hello Uni"))
      .map { _ =>
        store.messages.get.map(m => (m.role, m.text)) shouldBe
          Seq("user" -> "Hello Uni", "assistant" -> "echo: Hello Uni")
        store.conversations.get.head.title shouldBe "Hello Uni"
        store.sending.get shouldBe Set.empty
        store.thinking.get shouldBe Set.empty
      }
  }

  test("a late listMessages response does not replace the selected thread") {
    val service = ChatService(respond = ChatService.atOnce(instant))
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
    val store = ChatStore(ChatService(respond = ChatService.atOnce(heldModel)))
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
    val store = ChatStore(ChatService(respond = ChatService.atOnce(heldModel)))
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
    val store = ChatStore(ChatService(respond = ChatService.atOnce(heldModel)))
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
        private val service                   = ChatService(respond = ChatService.atOnce(instant))
        override def listConversations()      = service.listConversations()
        override def createConversation()     = service.createConversation()
        override def listMessages(id: String) = service.listMessages(id)
        override def streamMessage(id: String, text: String) = RxStream(
          Rx.exception(IllegalStateException("server down"))
        )
    val store = ChatStore(failing)
    store
      .load()
      .flatMap(_ => store.send("lost"))
      .map { _ =>
        store.messages.get shouldBe Seq.empty
        store.sending.get shouldBe Set.empty
        store.error.get shouldBe Some("streamMessage failed: server down")
      }
  }

  test("a streamed reply grows in one message, then the stored reply takes its key") {
    val held  = HeldStream()
    val store = ChatStore(ChatService(respond = held.model))
    def texts = store.messages.get.map(_.text)
    for
      _ <- store.load()
      a       = store.selected.get.get
      sending = store.send("hi")
      _       = store.thinking.get shouldBe Set(a)
      _       = held.emit("Hello ")
      _       = store.thinking.get shouldBe Set.empty
      _       = texts shouldBe Seq("hi", "Hello ")
      live    = store.messages.get.last
      _       = ChatStore.isDraft(live) shouldBe true
      _       = held.emit("Uni")
      _       = texts shouldBe Seq("hi", "Hello Uni")
      // Still one reply, under the same key
      _ = store.messages.get.last.id shouldBe live.id
      _ = held.finish()
      _ <- sending
    yield
      val stored = store.messages.get.last
      ChatStore.isDraft(stored) shouldBe false
      stored.text shouldBe "Hello Uni"
      store.keyOf(stored) shouldBe live.id
      store.messages.get.count(m => !ChatStore.isDraft(m)) shouldBe 2
      store.sending.get shouldBe Set.empty
  }

  test("a failure mid-stream keeps the prompt and the partial reply, and reports the error") {
    val held  = HeldStream()
    val store = ChatStore(ChatService(respond = held.model))
    for
      _ <- store.load()
      sending = store.send("hi")
      _       = held.emit("partial")
      _       = held.fail(IllegalStateException("model crashed"))
      _ <- sending
    yield
      store.messages.get.map(_.text) shouldBe Seq("hi", "partial")
      store.error.get shouldBe Some("streamMessage failed: model crashed")
      store.sending.get shouldBe Set.empty
      store.thinking.get shouldBe Set.empty
  }

  test("a stream that ends before Done unlocks the conversation and reports it") {
    val service = ChatService(respond = p => Rx.fromSeq(Seq("cut ", "off")))
    // The connection closes after the first part, as when the server restarts mid-reply
    val cut =
      new ChatApi:
        export service.{listConversations, createConversation, listMessages}
        override def streamMessage(id: String, text: String) = RxStream(
          service.streamMessage(id, text).take(2)
        )
    val store = ChatStore(cut)
    for
      _ <- store.load()
      _ <- store.send("hi")
    yield
      store.messages.get.map(_.text) shouldBe Seq("hi", "cut ")
      store.sending.get shouldBe Set.empty
      store.error.get shouldBe Some("streamMessage failed: the reply ended before it was complete")
  }

  test("a thread opened again mid-stream shows the reply so far, then the rest") {
    val held  = HeldStream()
    val store = ChatStore(ChatService(respond = held.model))
    def texts = store.messages.get.map(_.text)
    for
      _ <- store.load()
      a       = store.selected.get.get
      sending = store.send("hi")
      _       = held.emit("one ")
      _ <- store.newChat()
      // Parts that arrive while another thread is open don't show there
      _ = held.emit("two ")
      _ = texts shouldBe Seq.empty
      _ <- store.select(a)
      _ = texts shouldBe Seq("hi", "one two ")
      _ = held.emit("three")
      _ = texts shouldBe Seq("hi", "one two three")
      _ = held.finish()
      _ <- sending
    yield
      texts shouldBe Seq("hi", "one two three")
      store.messages.get.exists(ChatStore.isDraft) shouldBe false
  }

end ChatStoreTest
