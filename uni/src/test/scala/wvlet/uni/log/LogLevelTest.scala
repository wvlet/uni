package wvlet.uni.log

import wvlet.uni.log.LogLevel
import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined

import java.util.logging.Level as JLevel

class LogLevelTest extends UniTest:

  test("parse known log levels from string") {
    LogLevel("off") shouldBe LogLevel.OFF
    LogLevel("error") shouldBe LogLevel.ERROR
    LogLevel("warn") shouldBe LogLevel.WARN
    LogLevel("info") shouldBe LogLevel.INFO
    LogLevel("debug") shouldBe LogLevel.DEBUG
    LogLevel("trace") shouldBe LogLevel.TRACE
    LogLevel("all") shouldBe LogLevel.ALL

    LogLevel("OFF") shouldBe LogLevel.OFF
    LogLevel("ERROR") shouldBe LogLevel.ERROR
    LogLevel("WARN") shouldBe LogLevel.WARN
    LogLevel("INFO") shouldBe LogLevel.INFO
    LogLevel("DEBUG") shouldBe LogLevel.DEBUG
    LogLevel("TRACE") shouldBe LogLevel.TRACE
    LogLevel("ALL") shouldBe LogLevel.ALL
  }

  test("handle unknown log level string") {
    // Defaults to INFO and reports the unknown name on stderr
    val err = captureStderr {
      LogLevel("unknown") shouldBe LogLevel.INFO
    }
    err shouldContain "Unknown log level [unknown]"
  }

  test("convert from java.util.logging.Level") {
    LogLevel(JLevel.OFF) shouldBe LogLevel.OFF
    LogLevel(JLevel.SEVERE) shouldBe LogLevel.ERROR
    LogLevel(JLevel.WARNING) shouldBe LogLevel.WARN
    LogLevel(JLevel.INFO) shouldBe LogLevel.INFO
    LogLevel(JLevel.CONFIG) shouldBe LogLevel.INFO // Special case
    LogLevel(JLevel.FINE) shouldBe LogLevel.DEBUG
    LogLevel(JLevel.FINER) shouldBe LogLevel.TRACE
    LogLevel(JLevel.FINEST) shouldBe LogLevel.TRACE // Special case
    LogLevel(JLevel.ALL) shouldBe LogLevel.ALL
  }

  test("compare log levels") {
    LogLevel.OFF < LogLevel.ERROR shouldBe true
    LogLevel.ERROR < LogLevel.WARN shouldBe true
    LogLevel.WARN < LogLevel.INFO shouldBe true
    LogLevel.INFO < LogLevel.DEBUG shouldBe true
    LogLevel.DEBUG < LogLevel.TRACE shouldBe true
    LogLevel.TRACE < LogLevel.ALL shouldBe true

    LogLevel.ERROR > LogLevel.OFF shouldBe true
    LogLevel.INFO >= LogLevel.INFO shouldBe true
    LogLevel.INFO <= LogLevel.INFO shouldBe true
    LogLevel.DEBUG <= LogLevel.TRACE shouldBe true
  }

  test("unapply log level string") {
    "info" shouldMatch { case LogLevel(level) =>
      level shouldBe LogLevel.INFO
    }

    "DEBUG" shouldMatch { case LogLevel(level) =>
      level shouldBe LogLevel.DEBUG
    }

    "non_existent_level" match
      case LogLevel(level) =>
        fail("Should not have matched")
      case _ => // Expected
  }

  test("ordering") {
    val levels = Seq(
      LogLevel.INFO,
      LogLevel.ERROR,
      LogLevel.DEBUG,
      LogLevel.OFF,
      LogLevel.ALL,
      LogLevel.TRACE,
      LogLevel.WARN
    )
    val sortedLevels = levels.sorted
    sortedLevels shouldBe
      Seq(
        LogLevel.OFF,
        LogLevel.ERROR,
        LogLevel.WARN,
        LogLevel.INFO,
        LogLevel.DEBUG,
        LogLevel.TRACE,
        LogLevel.ALL
      )
  }

  test("properties") {
    LogLevel.OFF.name shouldBe "off"
    LogLevel.OFF.jlLevel shouldBe JLevel.OFF

    LogLevel.ERROR.name shouldBe "error"
    LogLevel.ERROR.jlLevel shouldBe JLevel.SEVERE

    LogLevel.WARN.name shouldBe "warn"
    LogLevel.WARN.jlLevel shouldBe JLevel.WARNING

    LogLevel.INFO.name shouldBe "info"
    LogLevel.INFO.jlLevel shouldBe JLevel.INFO

    LogLevel.DEBUG.name shouldBe "debug"
    LogLevel.DEBUG.jlLevel shouldBe JLevel.FINE

    LogLevel.TRACE.name shouldBe "trace"
    LogLevel.TRACE.jlLevel shouldBe JLevel.FINER

    LogLevel.ALL.name shouldBe "all"
    LogLevel.ALL.jlLevel shouldBe JLevel.ALL
  }

end LogLevelTest
