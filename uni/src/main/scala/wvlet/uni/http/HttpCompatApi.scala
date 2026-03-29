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

import wvlet.uni.control.ResultClass.Failed

/**
  * Platform-specific compatibility layer for HTTP exception handling.
  *
  * JVM provides full exception handling for SSL and java.net exceptions, while JS and Native
  * provide stub implementations since these classes are not available.
  */
private[http] trait HttpCompatApi:
  /**
    * Platform-specific SSL exception classifier for retry logic. JVM provides full SSL exception
    * handling; Native/JS return empty classifier since javax.net.ssl is not available.
    */
  def sslExceptionClassifier: PartialFunction[Throwable, Failed]

  /**
    * Platform-specific connection exception classifier for retry logic. JVM provides full java.net
    * exception handling; Native/JS return empty classifier since these classes may not be fully
    * available.
    */
  def connectionExceptionClassifier: PartialFunction[Throwable, Failed]

  /**
    * Platform-specific root cause exception classifier for retry logic. JVM provides
    * java.lang.reflect handling (ExecutionException, InvocationTargetException); Native/JS use
    * simpler implementation.
    */
  def rootCauseExceptionClassifier: PartialFunction[Throwable, Failed]
