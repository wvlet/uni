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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, Rx}

import scala.scalajs.js

/**
  * Reactive media query bindings for responsive design.
  *
  * Usage:
  * {{{
  *   // Responsive layout
  *   val isMobile = MediaQuery.isMobile
  *   div(
  *     cls := isMobile.map(m => if m then "mobile-layout" else "desktop-layout"),
  *     isMobile.map(m => if m then span("Mobile View") else span("Desktop View"))
  *   )
  *
  *   // Custom media query
  *   val isLargeScreen = MediaQuery.matches("(min-width: 1200px)")
  *   div(
  *     isLargeScreen.map(large => if large then "Show sidebar" else "Hide sidebar")
  *   )
  *
  *   // Dark mode detection
  *   val prefersDark = MediaQuery.prefersDarkMode
  *   div(
  *     cls := prefersDark.map(d => if d then "dark-theme" else "light-theme")
  *   )
  * }}}
  */
object MediaQuery:

  /**
    * A reactive media query matcher that updates when the media query match state changes.
    */
  class MediaQueryMatcher(query: String) extends Cancelable:
    private val mql        = dom.window.matchMedia(query)
    private val underlying = Rx.variable(mql.matches)

    private val listener: js.Function1[dom.Event, Unit] =
      (e: dom.Event) => underlying := mql.matches

    mql.addEventListener("change", listener)

    /**
      * Get the current match state.
      */
    def get: Boolean = underlying.get

    /**
      * Get the reactive stream of match states.
      */
    def rx: Rx[Boolean] = underlying

    /**
      * Map over the match state.
      */
    def map[B](f: Boolean => B): Rx[B] = underlying.map(f)

    /**
      * Stop listening to media query changes.
      */
    override def cancel: Unit = mql.removeEventListener("change", listener)

  end MediaQueryMatcher

  /**
    * Create a reactive media query matcher for the given CSS media query string.
    *
    * @param query
    *   A CSS media query string, e.g., "(max-width: 768px)"
    * @return
    *   A MediaQueryMatcher that emits true when the query matches
    */
  def matches(query: String): MediaQueryMatcher = MediaQueryMatcher(query)

  /**
    * Reactive check for mobile screens (max-width: 767px).
    */
  lazy val isMobile: Rx[Boolean] = matches("(max-width: 767px)").rx

  /**
    * Reactive check for tablet screens (768px to 1023px).
    */
  lazy val isTablet: Rx[Boolean] = matches("(min-width: 768px) and (max-width: 1023px)").rx

  /**
    * Reactive check for desktop screens (min-width: 1024px).
    */
  lazy val isDesktop: Rx[Boolean] = matches("(min-width: 1024px)").rx

  /**
    * Reactive check for user's dark mode preference.
    */
  lazy val prefersDarkMode: Rx[Boolean] = matches("(prefers-color-scheme: dark)").rx

  /**
    * Reactive check for user's reduced motion preference.
    */
  lazy val prefersReducedMotion: Rx[Boolean] = matches("(prefers-reduced-motion: reduce)").rx

  /**
    * Reactive check for high contrast mode preference.
    */
  lazy val prefersHighContrast: Rx[Boolean] = matches("(prefers-contrast: more)").rx

  /**
    * Reactive check for portrait orientation.
    */
  lazy val isPortrait: Rx[Boolean] = matches("(orientation: portrait)").rx

  /**
    * Reactive check for landscape orientation.
    */
  lazy val isLandscape: Rx[Boolean] = matches("(orientation: landscape)").rx

end MediaQuery
