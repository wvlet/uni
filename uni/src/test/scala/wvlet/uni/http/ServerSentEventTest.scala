/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.http

import wvlet.uni.test.UniTest

class ServerSentEventTest extends UniTest:

  test("create event with data only") {
    val event = ServerSentEvent.data("hello")
    event.data shouldBe "hello"
    event.id shouldBe None
    event.event shouldBe None
    event.retry shouldBe None
  }

  test("create event with data and event type") {
    val event = ServerSentEvent.withEventType("hello", "greeting")
    event.data shouldBe "hello"
    event.event shouldBe Some("greeting")
  }

  test("create event with all fields") {
    val event = ServerSentEvent(
      id = Some("123"),
      event = Some("message"),
      retry = Some(5000),
      data = "hello world"
    )
    event.id shouldBe Some("123")
    event.event shouldBe Some("message")
    event.retry shouldBe Some(5000)
    event.data shouldBe "hello world"
  }

  test("toContent generates correct wire format") {
    val event = ServerSentEvent(data = "hello")
    event.toContent shouldBe "data: hello\n\n"
  }

  test("toContent with all fields") {
    val event = ServerSentEvent(
      id = Some("42"),
      event = Some("update"),
      retry = Some(3000),
      data = "message"
    )
    event.toContent shouldBe "id: 42\nevent: update\nretry: 3000\ndata: message\n\n"
  }

  test("toContent handles multi-line data") {
    val event = ServerSentEvent(data = "line1\nline2\nline3")
    event.toContent shouldBe "data: line1\ndata: line2\ndata: line3\n\n"
  }

  test("toContent handles empty data") {
    val event = ServerSentEvent(data = "")
    event.toContent shouldBe "data: \n\n"
  }

  test("builder methods work correctly") {
    val event = ServerSentEvent
      .data("initial")
      .withId("123")
      .withEvent("test")
      .withRetry(1000)
      .withData("updated")

    event.id shouldBe Some("123")
    event.event shouldBe Some("test")
    event.retry shouldBe Some(1000)
    event.data shouldBe "updated"
  }

  test("no* methods clear optional fields") {
    val event =
      ServerSentEvent(id = Some("123"), event = Some("test"), retry = Some(1000), data = "data")
        .noId
        .noEvent
        .noRetry

    event.id shouldBe None
    event.event shouldBe None
    event.retry shouldBe None
    event.data shouldBe "data"
  }

end ServerSentEventTest

class ServerSentEventParserTest extends UniTest:

  test("parse simple event") {
    val content = "data: hello\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.data shouldBe "hello"
  }

  test("parse event with all fields") {
    val content = "id: 42\nevent: message\nretry: 3000\ndata: hello\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.id shouldBe Some("42")
    events.head.event shouldBe Some("message")
    events.head.retry shouldBe Some(3000)
    events.head.data shouldBe "hello"
  }

  test("parse multi-line data") {
    val content = "data: line1\ndata: line2\ndata: line3\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.data shouldBe "line1\nline2\nline3"
  }

  test("parse multiple events") {
    val content = "data: first\n\ndata: second\n\ndata: third\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 3
    events(0).data shouldBe "first"
    events(1).data shouldBe "second"
    events(2).data shouldBe "third"
  }

  test("ignore comments") {
    val content = ": this is a comment\ndata: hello\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.data shouldBe "hello"
  }

  test("handle empty lines between fields") {
    val content = "id: 1\n\ndata: after empty\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 2
    events(0).id shouldBe Some("1")
    events(0).data shouldBe ""
    events(1).data shouldBe "after empty"
  }

  test("handle CRLF line endings") {
    val content = "data: hello\r\n\r\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.data shouldBe "hello"
  }

  test("incremental parsing with feed") {
    val parser = ServerSentEventParser()

    // Feed partial data
    val events1 = parser.feed("data: hel")
    events1.isEmpty shouldBe true

    // Feed more data completing the event
    val events2 = parser.feed("lo\n\n")
    events2.size shouldBe 1
    events2.head.data shouldBe "hello"
  }

  test("incremental parsing across multiple chunks") {
    val parser = ServerSentEventParser()

    val events1 = parser.feed("data: ")
    val events2 = parser.feed("first\n")
    val events3 = parser.feed("\ndata: second\n\n")

    events1.isEmpty shouldBe true
    events2.isEmpty shouldBe true
    events3.size shouldBe 2
    events3(0).data shouldBe "first"
    events3(1).data shouldBe "second"
  }

  test("flush returns pending event") {
    val parser = ServerSentEventParser()
    parser.feed("data: pending")

    val flushed = parser.flush()
    flushed shouldBe Some(ServerSentEvent(data = "pending"))
  }

  test("reset clears parser state") {
    val parser = ServerSentEventParser()
    parser.feed("data: partial")
    parser.reset()

    val events = parser.feed("data: fresh\n\n")
    events.size shouldBe 1
    events.head.data shouldBe "fresh"
  }

  test("handle field without space after colon") {
    val content = "data:hello\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.data shouldBe "hello"
  }

  test("ignore unknown fields") {
    val content = "unknown: value\ndata: hello\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.data shouldBe "hello"
  }

  test("ignore invalid retry value") {
    val content = "retry: notanumber\ndata: hello\n\n"
    val events  = ServerSentEventParser.parse(content)
    events.size shouldBe 1
    events.head.retry shouldBe None
    events.head.data shouldBe "hello"
  }

  test("preserve id across events") {
    val parser = ServerSentEventParser()

    val events1 = parser.feed("id: 1\ndata: first\n\n")
    events1.head.id shouldBe Some("1")

    // Note: In SSE, the last event id should be preserved for reconnection,
    // but the parser keeps it for the next event until explicitly cleared
    val events2 = parser.feed("data: second\n\n")
    events2.head.id shouldBe Some("1")
  }

  test("parse lines split across chunks, including a CRLF split between chunks") {
    val parser = ServerSentEventParser()
    parser.feed("data: hel") shouldBe Seq.empty
    parser.feed("lo\r") shouldBe Seq.empty
    val events = parser.feed("\n\r\ndata: next\n\n")
    events.map(_.data) shouldBe Seq("hello", "next")
  }

  test("reject a line longer than the limit") {
    val parser = ServerSentEventParser(maxLineLength = 16)
    parser.feed("data: 0123456789") shouldBe Seq.empty
    intercept[ServerSentEventParser.EventTooLargeException] {
      parser.feed("abcdef")
    }
  }

  test("reject an event whose data exceeds the limit") {
    val parser = ServerSentEventParser(maxEventSize = 10)
    parser.feed("data: 01234\ndata: 5678\n\n").map(_.data) shouldBe Seq("01234\n5678")
    intercept[ServerSentEventParser.EventTooLargeException] {
      parser.feed("data: 01234\ndata: 567890\n")
    }
  }

end ServerSentEventParserTest

class ServerSentEventHandlerTest extends UniTest:

  test("empty handler does nothing") {
    val handler = ServerSentEventHandler.empty
    handler.onEvent(ServerSentEvent.data("test"))
    handler.onError(Exception("test"))
    handler.onCompletion()
  }

  test("create handler from function") {
    var received: Option[ServerSentEvent] = None
    val handler                           = ServerSentEventHandler { event =>
      received = Some(event)
    }

    handler.onEvent(ServerSentEvent.data("hello"))
    received shouldBe Some(ServerSentEvent.data("hello"))
  }

end ServerSentEventHandlerTest
