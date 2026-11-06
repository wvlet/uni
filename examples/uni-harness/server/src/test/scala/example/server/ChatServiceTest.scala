package example.server

import example.api.ChatReply
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import scala.concurrent.Future

class ChatServiceTest extends UniTest:
  // An instant model and a fixed clock keep the tests fast and deterministic
  private def service(): ChatService = ChatService(
    respond = prompt => Future.successful(s"echo: ${prompt}"),
    now = () => 1000L
  )

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
        s.sendMessage(c.id, "Hello\nsecond line")
          .flatMap { (reply: ChatReply) =>
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
      .flatMap(c => s.sendMessage(c.id, "first").flatMap(_ => s.sendMessage(c.id, "second")))
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
      respond = p => Future.successful(p),
      now =
        () =>
          t += 1;
          t
    )
    s.createConversation()
      .flatMap(c => s.sendMessage(c.id, "newest").map(_ => c.id))
      .flatMap(id => s.listConversations().map(cs => cs.head.id shouldBe id))
  }

end ChatServiceTest
