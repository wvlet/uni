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

class NodeSyncHttpChannelTest extends UniTest:

  test("isNode returns true under Node") {
    NodeSyncHttpChannel.isNode.shouldBe(true)
  }

  test("HttpSyncClient round-trips GET against httpbin.org") {
    Http.setDefaultChannelFactory(JSHttpChannelFactory)
    val client = Http.client.withBaseUri("https://httpbin.org").newSyncClient
    try
      val resp = client.send(Request(method = HttpMethod.GET, uri = "/get"))
      info(s"status=${resp.status} bytes=${resp.content.toContentString.length}")
      resp.status.code.shouldBe(200)
      resp.content.toContentString.shouldContain("httpbin.org")
    finally
      client.close()
  }

  test("HttpSyncClient round-trips POST with a JSON body") {
    Http.setDefaultChannelFactory(JSHttpChannelFactory)
    val client = Http.client.withBaseUri("https://httpbin.org").newSyncClient
    try
      val body = """{"hello": "world"}"""
      val resp = client.send(Request(method = HttpMethod.POST, uri = "/post").withJsonContent(body))
      resp.status.code.shouldBe(200)
      resp.content.toContentString.shouldContain("\"hello\"")
    finally
      client.close()
  }

end NodeSyncHttpChannelTest
