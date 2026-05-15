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

/**
  * Touching `Http` must be enough to register the platform-specific default channel factory. Before
  * this contract existed, downstream cross-platform code had to call
  * `Http.setDefaultChannelFactory(...)` itself once per platform (.jvm/.js/.native) because the
  * side-effect lived in `HttpCompat`, which only loaded via the error-classifier path.
  */
class HttpDefaultChannelFactoryTest extends UniTest:

  test("Http object registers a platform default channel factory at load time") {
    (Http.defaultChannelFactory ne HttpClientConfig.NoOpChannelFactory) shouldBe true
  }

end HttpDefaultChannelFactoryTest
