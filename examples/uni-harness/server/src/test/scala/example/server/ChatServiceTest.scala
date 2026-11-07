package example.server

import example.api.{ChatEvent, ChatReply}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

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

end ChatServiceTest
