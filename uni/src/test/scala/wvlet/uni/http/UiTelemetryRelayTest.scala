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

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.{OnNext, Rx, RxRunner}
import wvlet.uni.telemetry.{EventExporter, Telemetry}
import wvlet.uni.telemetry.otlp.OtlpJson
import wvlet.uni.test.UniTest

class UiTelemetryRelayTest extends UniTest:

  private def run(rx: Rx[Response]): Response =
    var result: Response = null
    RxRunner.runOnce(rx) {
      case OnNext(r: Response) =>
        result = r
      case _ =>
    }
    result

  private def setup() =
    val events = EventExporter.inMemory()
    val relay  = UiTelemetryRelay(Telemetry().withServiceName("web").withEventExporter(events))
    (events, relay)

  private val app: RxHttpHandler = _ => Rx.single(Response.ok("app"))

  private def post(body: String): Request = Request
    .post(UiTelemetryRelay.DefaultPath)
    .withJsonContent(body)
    .addHeader(HttpHeader.UserAgent, "test-browser")

  test("pass UI events to the service's telemetry") {
    val (events, relay) = setup()
    val resp            = run(
      relay(
        post(
          """{"session":"0a1b2c","events":[
            |{"name":"ui.press","time":1700000000000,"attributes":{"ui.ref":"button:save","duration.ms":12,"ok":true}},
            |{"name":"command.run","time":1700000000001,"attributes":{"command.id":"toggle-dock"}}]}"""
            .stripMargin
        ),
        app
      )
    )
    resp.status shouldBe HttpStatus.NoContent_204
    val Seq(press, command) = events.events
    press.name shouldBe "ui.press"
    press.epochNanos shouldBe 1700000000000L * 1000000L
    press.attributes("ui.ref") shouldBe "button:save"
    press.attributes("duration.ms") shouldBe 12L
    press.attributes("ok") shouldBe true
    press.attributes("session.id") shouldBe "0a1b2c"
    press.attributes("user_agent.original") shouldBe "test-browser"
    press.resource("service.name") shouldBe "web"
    command.attributes("command.id") shouldBe "toggle-dock"
  }

  test("drop event names, keys, and values the browser shouldn't send") {
    val (events, relay) = setup()
    val long            = "x" * 1000
    run(
      relay(
        post(
          s"""{"session":"NOT-HEX","events":[
             |{"name":"evil.event","attributes":{}},
             |{"name":"ui.change","time":-5,"attributes":{"ui.ref":"${long}","Bad-Key":"x","nested":{"a":1},"ui.kind":"button"}}]}"""
            .stripMargin
        ),
        app
      )
    )
    val Seq(e) = events.events
    e.name shouldBe "ui.change"
    e.attributes.contains("session.id") shouldBe false
    e.attributes.contains("Bad-Key") shouldBe false
    e.attributes.contains("nested") shouldBe false
    e.attributes("ui.ref").asInstanceOf[String].length shouldBe 256
    e.attributes("ui.kind") shouldBe "button"
    // An implausible browser time falls back to the server's clock
    (e.epochNanos > 0L) shouldBe true
  }

  test("reject what isn't a UiTelemetry batch") {
    val (events, relay) = setup()
    run(relay(post("not json"), app)).status shouldBe HttpStatus.BadRequest_400
    run(relay(post("""{"events":"x"}"""), app)).status shouldBe HttpStatus.BadRequest_400
    val tooMany = (1 to 201).map(_ => """{"name":"ui.press"}""").mkString(",")
    run(relay(post(s"""{"events":[${tooMany}]}"""), app)).status shouldBe HttpStatus.BadRequest_400
    run(relay(post("x" * (64 * 1024 + 1)), app)).status shouldBe HttpStatus.PayloadTooLarge_413
    run(relay(Request.get(UiTelemetryRelay.DefaultPath), app)).status shouldBe
      HttpStatus.MethodNotAllowed_405
    events.events shouldBe Seq.empty
  }

  test("accept only JSON from the page's own origin") {
    val (events, relay) = setup()
    val body            = """{"events":[{"name":"ui.press"}]}"""
    val plain           = Request.post(UiTelemetryRelay.DefaultPath).withTextContent(body)
    run(relay(plain, app)).status shouldBe HttpStatus.UnsupportedMediaType_415
    val crossSite = post(body)
      .addHeader(HttpHeader.Host, "app.example.com")
      .addHeader(HttpHeader.Origin, "https://evil.example.net")
    run(relay(crossSite, app)).status shouldBe HttpStatus.Forbidden_403
    events.events shouldBe Seq.empty
    val sameSite = post(body)
      .addHeader(HttpHeader.Host, "app.example.com")
      .addHeader(HttpHeader.Origin, "https://app.example.com")
    run(relay(sameSite, app)).status shouldBe HttpStatus.NoContent_204
    events.events.size shouldBe 1
  }

  test("pass other requests through") {
    val (_, relay) = setup()
    run(relay(Request.get("/index.html"), app)).contentAsString shouldBe Some("app")
  }

  test("encode events as OTLP log records") {
    val (events, relay) = setup()
    run(
      relay(
        post("""{"events":[{"name":"ui.press","time":5,"attributes":{"ui.ref":"button:save"}}]}"""),
        app
      )
    )
    val json  = JSON.parse(OtlpJson.logs(events.events).toJSON)
    val rl    = json.asInstanceOf[JSONObject].get("resourceLogs").get.asInstanceOf[JSONArray].v.head
    val scope = rl.asInstanceOf[JSONObject].get("scopeLogs").get.asInstanceOf[JSONArray].v.head
    val record = scope
      .asInstanceOf[JSONObject]
      .get("logRecords")
      .get
      .asInstanceOf[JSONArray]
      .v
      .head
      .asInstanceOf[JSONObject]
    record.get("eventName") shouldBe Some(JSONString("ui.press"))
    record.get("timeUnixNano") shouldBe Some(JSONString("5000000"))
    record.get("severityNumber") shouldBe Some(JSONLong(9))
    record.toJSON.contains("\"event.name\"") shouldBe true
  }

end UiTelemetryRelayTest
