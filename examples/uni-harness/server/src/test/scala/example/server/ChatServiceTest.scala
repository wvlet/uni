package example.server

import example.api.{ChatEvent, ChatReply}
import wvlet.uni.util.settings.InMemoryStoreBackend
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import scala.scalajs.js

class ChatServiceTest extends UniTest:
  // An instant model and a fixed clock keep the tests fast and deterministic
  private def service(): ChatService = ChatService(
    respond = prompt => Rx.single(s"echo: ${prompt}"),
    now = () => 1000L
  )

  /** Send a prompt and keep only the stored reply at the end of the stream. */
  private def send(s: ChatService, id: String, text: String): Rx[ChatReply] = s
    .streamMessage(id, text)
    .flatMap {
      case ChatEvent.Done(reply) =>
        Rx.single(reply)
      case _ =>
        Rx.empty
    }

  test("streams the reply as Started, its parts, then Done with the whole reply") {
    val s = ChatService(respond = p => Rx.fromSeq(Seq("Hello ", "from ", p)), now = () => 1000L)
    s.createConversation()
      .flatMap(c => s.streamMessage(c.id, "Uni").scan(Seq.empty[ChatEvent])(_ :+ _).lastOption)
      .map { events =>
        events shouldMatch {
          case Some(
                Seq(
                  ChatEvent.Started(user),
                  ChatEvent.Delta("Hello "),
                  ChatEvent.Delta("from "),
                  ChatEvent.Delta("Uni"),
                  ChatEvent.Done(reply)
                )
              ) =>
            user.text shouldBe "Uni"
            reply.user shouldBe user
            reply.assistant.text shouldBe "Hello from Uni"
        }
      }
  }

  test("splits a reply into words with their spacing") {
    ChatService.parts("a b\n\nc") shouldBe Seq("a ", "b\n", "\n", "c")
    ChatService.parts("") shouldBe Seq.empty
  }

  test("starts with one empty conversation") {
    val s = service()
    s.listConversations()
      .map { cs =>
        cs.map(_.title) shouldBe Seq(ChatService.untitled)
      }
  }

  test("stores the prompt and the reply, and names the conversation after the prompt") {
    val s = service()
    s.createConversation()
      .flatMap { c =>
        send(s, c.id, "Hello\nsecond line").flatMap { (reply: ChatReply) =>
          reply.assistant.text shouldBe "echo: Hello\nsecond line"
          reply.conversation.title shouldBe "Hello"
          s.listMessages(c.id)
        }
      }
      .map { ms =>
        ms.map(_.role) shouldBe Seq("user", "assistant")
      }
  }

  test("keeps the first title on later prompts") {
    val s = service()
    s.createConversation()
      .flatMap(c => send(s, c.id, "first").flatMap(_ => send(s, c.id, "second")))
      .map(reply => reply.conversation.title shouldBe "first")
  }

  test("names a conversation only after a non-blank prompt") {
    ChatService.titleFor(ChatService.untitled, "") shouldBe ChatService.untitled
    ChatService.titleFor(ChatService.untitled, "\n  \n  hi there") shouldBe "hi there"
    ChatService.titleFor(ChatService.untitled, "x" * 100).length shouldBe 40
  }

  test("lists the most recently updated conversation first") {
    var t = 0L
    val s = ChatService(
      respond = p => Rx.single(p),
      now =
        () =>
          t += 1;
          t
    )
    s.createConversation()
      .flatMap(c => send(s, c.id, "newest").map(_ => c.id))
      .flatMap(id => s.listConversations().map(cs => cs.head.id shouldBe id))
  }

  test("a restarted service keeps the conversations, their messages, and new ids") {
    val backend = InMemoryStoreBackend()
    val first   = ChatService(
      respond = p => Rx.single(s"echo: ${p}"),
      now = () => 1000L,
      backend = backend
    )
    first
      .listConversations()
      .flatMap(cs => send(first, cs.head.id, "Hello Uni"))
      .flatMap { reply =>
        val second = ChatService(respond = p => Rx.single(p), now = () => 2000L, backend = backend)
        second
          .listConversations()
          .flatMap { cs =>
            // The used chat is kept, and an empty one with a new id opens for the next prompt
            cs.map(_.title) shouldBe Seq("New chat", "Hello Uni")
            Seq(reply.conversation.id, reply.user.id, reply.assistant.id).contains(
              cs.head.id
            ) shouldBe false
            second.listMessages(reply.conversation.id)
          }
          .map { ms =>
            ms.map(m => (m.role, m.text)) shouldBe
              Seq("user" -> "Hello Uni", "assistant" -> "echo: Hello Uni")
          }
      }
  }

  test("a restart reuses an empty chat that is not the most recent one") {
    val backend = InMemoryStoreBackend()
    var clock   = 1000L
    val first   = ChatService(respond = p => Rx.single(p), now = () => clock, backend = backend)
    first
      .listConversations()
      .flatMap { cs =>
        val used = cs.head.id
        clock = 2000L
        // A blank chat, then a newer prompt in the first one
        first
          .createConversation()
          .flatMap { blank =>
            clock = 3000L
            send(first, used, "later").map(_ => blank.id)
          }
      }
      .flatMap { blank =>
        clock = 4000L
        val again = ChatService(backend = backend, now = () => clock)
        again.listConversations().map(cs => cs.map(_.id) shouldBe Seq(blank, "c-1"))
      }
  }

  test("a restart reuses the empty chat left from the last run") {
    val backend = InMemoryStoreBackend()
    ChatService(backend = backend)
    val again = ChatService(backend = backend)
    again.listConversations().map(_.size shouldBe 1)
  }

  test("saves conversations to conversations.json in a directory") {
    val fs  = js.Dynamic.global.require("fs")
    val os  = js.Dynamic.global.require("os")
    val dir = fs.mkdtempSync(s"${os.tmpdir()}/uni-harness-").toString
    try
      ChatService(backend = ChatService.inDirectory(s"${dir}/data"))
      val saved = fs.readFileSync(s"${dir}/data/${ChatService.fileName}", "utf8").toString
      saved shouldContain "\"conversations\""
      ChatService(backend = ChatService.inDirectory(s"${dir}/data"))
        .listConversations()
        .map(_.size shouldBe 1)
    finally
      fs.rmSync(dir, js.Dynamic.literal(recursive = true, force = true))
  }

end ChatServiceTest
