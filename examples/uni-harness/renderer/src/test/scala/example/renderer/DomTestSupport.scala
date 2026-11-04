package example.renderer

import wvlet.uni.dom.testing.DomTestSession
import wvlet.uni.test.UniTest

/** Render components into the headless browser; each test's mounts are removed after it. */
trait DomTestSupport extends UniTest with DomTestSession:
  override protected def after: Unit =
    cleanupMounted()
    super.after
