package example.gallery.stories

import example.gallery.{ComponentPage, Lane, Story, Timeline}
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.*
import wvlet.uni.ui.*

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.implicitConversions

/**
  * How Rx streams behave, one example per `// #region` block. Each example ends with a
  * [[Timeline]], the gallery's marble diagram, which subscribes to the streams it lists and draws
  * each event they emit: the Rx code above it is the part to copy.
  */
object RxStories:

  // #region rx-values/derived
  inline def rxDerived: RxElement =
    val n     = Rx.variable(1)
    val tens  = n.map(_ * 10)
    val evens = n.filter(_ % 2 == 0)
    val total = n.scan(0)(_ + _)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("n - 1").onClick(() => n.update(_ - 1)),
        Button("n + 1").primary.onClick(() => n.update(_ + 1)),
        span(cls -> "demo-note", n.map(v => s"n = ${v}"))
      ),
      // The gallery's marble diagram of each stream
      Timeline(
        Lane("n", n),
        Lane("n.map(_ * 10)", tens),
        Lane("n.filter(_ % 2 == 0)", evens),
        Lane("n.scan(0)(_ + _)", total)
      )
    )
  end rxDerived
  // #endregion

  // #region rx-values/join-zip
  inline def rxJoinZip: RxElement =
    val a = Rx.variable(1)
    val b = Rx.variable(10)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("a + 1").onClick(() => a.update(_ + 1)),
        Button("b + 10").onClick(() => b.update(_ + 10))
      ),
      Timeline(
        Lane("a", a),
        Lane("b", b),
        // The latest of each, whenever either changes
        Lane("Rx.join(a, b)", Rx.join(a, b)),
        // Pairs by position: the 2nd a with the 2nd b, and so on
        Lane("Rx.zip(a, b)", Rx.zip(a, b))
      )
    )
  end rxJoinZip
  // #endregion

  // #region rx-values/equal
  inline def rxEqual: RxElement =
    val name = Rx.variable("Ada")

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Input(name).withLabel("Name"),
        // := skips a value equal to the current one; forceSet always emits
        Button("name := name.get").onClick(() => name := name.get),
        Button("name.forceSet(name.get)").onClick(() => name.forceSet(name.get))
      ),
      Timeline(Lane("name", name), Lane("name.map(_.length)", name.map(_.length)))
    )
  end rxEqual
  // #endregion

  // #region rx-time/throttle
  inline def rxThrottle: RxElement =
    val clicks = Rx.variable(0)

    div(
      cls -> "demo-stack",
      div(cls -> "demo-row", Button("Click fast").primary.onClick(() => clicks.update(_ + 1))),
      Timeline(
        Lane("clicks", clicks),
        // The first click of each second; the rest of that second is dropped
        Lane("clicks.throttleFirst(1000)", clicks.throttleFirst(1000)),
        // Once a second, the latest click if there was one
        Lane("clicks.throttleLast(1000)", clicks.throttleLast(1000))
      )
    )
  end rxThrottle
  // #endregion

  // #region rx-time/interval
  inline def rxInterval: RxElement =
    val starts = Rx.variable(0)
    // Each start replaces the running interval: flatMap cancels the previous one
    val ticks = starts
      .filter(_ > 0)
      .flatMap(_ => Rx.intervalMillis(500).take(6).scan(0)((count, _) => count + 1))

    div(
      cls -> "demo-stack",
      div(cls -> "demo-row", Button("Start").primary.onClick(() => starts.update(_ + 1))),
      Timeline(Lane("starts", starts), Lane("ticks", ticks))
    )
  end rxInterval
  // #endregion

  // #region rx-time/timeout
  inline def rxTimeout: RxElement =
    val latency = Rx.variable(0)
    val replies = latency
      .filter(_ > 0)
      .flatMap(ms =>
        // A fake request that answers after `ms`, given 1 second for its first event
        Rx.delay(ms, TimeUnit.MILLISECONDS)
          .map(_ => s"reply in ${ms} ms")
          .timeout(1000)
          .recover { case _: Rx.TimeoutException =>
            "timed out"
          }
      )

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        // forceSet: the same latency twice is still a new request
        Button("Fast (300 ms)").onClick(() => latency.forceSet(300)),
        Button("Slow (2 s)").onClick(() => latency.forceSet(2000))
      ),
      Timeline(Lane("latency", latency), Lane("replies", replies))
    )
  end rxTimeout
  // #endregion

  // #region rx-async/switch
  inline def rxSwitch: RxElement =
    val query     = Rx.variable("")
    val cancelled = Rx.variable("")
    val results   = query
      .filter(_.nonEmpty)
      .flatMap { q =>
        // A fake search that answers after 600 ms. A newer query cancels it.
        Rx.delay(600, TimeUnit.MILLISECONDS).map(_ => s"hits for ${q}").onCancel(cancelled := q)
      }

    div(
      cls -> "demo-stack",
      div(cls -> "demo-narrow", Input(query).withLabel("Search").withPlaceholder("Type quickly")),
      Timeline(
        Lane("query", query),
        Lane("results", results),
        Lane("cancelled", cancelled.filter(_.nonEmpty))
      )
    )
  end rxSwitch
  // #endregion

  // #region rx-async/future
  inline def rxFuture: RxElement =
    val round = Rx.variable(0)

    def demo(): RxElement =
      val requests = Rx.variable(0)
      // The number of the request that fails
      val failing = Rx.variable(0)

      // A fake request: a Future that settles after 500 ms
      def fetch(n: Int): Future[String] =
        val reply = Promise[String]()
        dom
          .window
          .setTimeout(
            () =>
              if n == failing.get then
                reply.failure(Exception(s"request ${n} failed"))
              else
                reply.success(s"reply ${n}")
            ,
            500
          )
        reply.future

      given ExecutionContext = ExecutionContext.parasitic
      val sent               = requests.filter(_ > 0)
      // A failed Rx.future ends the flatMap, and with it the whole stream
      val plain = sent.flatMap(n => Rx.future(fetch(n)))
      // recover inside the inner stream turns the failure into a value and keeps going
      val guarded = sent.flatMap(n =>
        Rx.future(fetch(n))
          .recover { case _ =>
            s"no reply ${n}"
          }
      )

      div(
        cls -> "demo-stack",
        div(
          cls -> "demo-row",
          Button("Request").primary.onClick(() => requests.update(_ + 1)),
          Button("Fail the next request")
            .danger
            .withDisabled(Rx.join(requests, failing).map((r, f) => f > r))
            .onClick(() => failing := requests.get + 1),
          Button("Restart").ghost.onClick(() => round.update(_ + 1))
        ),
        Timeline(
          Lane("requests", sent),
          Lane("sent.flatMap(n => Rx.future(fetch(n)))", plain),
          Lane("… Rx.future(fetch(n)).recover { … }", guarded)
        )
      )
    end demo

    div(round.map(_ => demo()))
  end rxFuture
  // #endregion

  // #region rx-errors/resume
  inline def rxResume: RxElement =
    val n       = Rx.variable(1)
    val checked = n.map { v =>
      if v % 3 == 0 then
        throw IllegalArgumentException(s"${v} is a multiple of 3")
      v
    }

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("n + 1").primary.onClick(() => n.update(_ + 1)),
        // A DOM binding shows nothing for an OnError, then the next value
        span(cls -> "demo-note", "DOM binding: ", checked.map(v => s"${v}"))
      ),
      Timeline(
        Lane("n", n),
        // OnError doesn't end the stream: the next n comes through
        Lane("checked", checked),
        Lane(
          "checked.recover { case _ => -1 }",
          checked.recover { case _ =>
            -1
          }
        ),
        // run stops at the first error, which arrives as a failed end
        Lane.run("checked, with run", checked)
      )
    )
  end rxResume
  // #endregion

  // #region rx-errors/terminal
  inline def rxTerminal: RxElement =
    val round = Rx.variable(0)

    def demo(): RxElement =
      val feed  = Rx.variable("live 1")
      var count = 1

      div(
        cls -> "demo-stack",
        div(
          cls -> "demo-row",
          Button("Next value").onClick { () =>
            count += 1
            feed := s"live ${count}"
          },
          // A failed source ends: every subscriber gets OnFailure
          Button("setException").danger.onClick(() => feed.setException(Exception("down"))),
          Button("Restart").ghost.onClick(() => round.update(_ + 1))
        ),
        Timeline(
          Lane("feed", feed),
          // One value for the failure, then a successful end
          Lane(
            "feed.recover { … }",
            feed.recover { case e =>
              s"lost: ${e.getMessage}"
            }
          ),
          // Continue as another stream, here two cached values
          Lane(
            "feed.recoverWith { … }",
            feed.recoverWith { case _ =>
              Rx.fromSeq(Seq("cached 1", "cached 2"))
            }
          )
        )
      )
    end demo

    div(round.map(_ => demo()))
  end rxTerminal
  // #endregion

  // #region rx-lifecycle/tracker
  inline def rxTracker: RxElement =
    val counter = Rx.variable(0)
    val calls   = Rx.variable(0)
    val tracker = RxTracker(captureStackTrace = false)
    val active  = Rx.variable(Seq.empty[RxSubscription])
    var subs    = List.empty[Cancelable]

    def refresh(): Unit = active := tracker.activeSubscriptions.sortBy(_.id)

    div(
      cls -> "demo-stack",
      div(
        cls -> "demo-row",
        Button("subscribe")
          .primary
          .onClick { () =>
            // trace records the subscriptions its block starts
            subs ::= tracker.trace(counter.map(_ * 2).subscribe(_ => calls.update(_ + 1)))
            refresh()
          },
        Button("cancel the newest").onClick { () =>
          subs.headOption.foreach(_.cancel)
          subs = subs.drop(1)
          refresh()
        },
        Button("counter + 1").onClick(() => counter.update(_ + 1)),
        span(cls -> "demo-note", calls.map(c => s"handler calls: ${c}"))
      ),
      // Removing this example cancels what it left running
      active
        .onCancel(subs.foreach(_.cancel))
        .map { ss =>
          div(
            cls -> "demo-panel",
            if ss.isEmpty then
              "No active subscriptions"
            else
              ul(
                cls -> "demo-list",
                ss.map(s => li(code(s"#${s.id} ${s.mode} ${s.rx.getClass.getSimpleName}")))
              )
          )
        }
    )
  end rxTracker
  // #endregion

  // #region rx-lifecycle/end
  inline def rxEnd: RxElement =
    val round = Rx.variable(0)

    def demo(): RxElement =
      val n = Rx.variable(1)

      div(
        cls -> "demo-stack",
        div(
          cls -> "demo-row",
          Button("n + 1").primary.onClick(() => n.update(_ + 1)),
          // stop() ends the variable: OnCompletion to every subscriber
          Button("n.stop()").onClick(() => n.stop()),
          Button("Restart").ghost.onClick(() => round.update(_ + 1))
        ),
        Timeline(
          Lane("n", n),
          // take(3) completes after three values, while n goes on
          Lane("n.take(3)", n.take(3)),
          Lane("n.map(_ * 10)", n.map(_ * 10))
        )
      )
    end demo

    div(round.map(_ => demo()))
  end rxEnd
  // #endregion

  val pages: Seq[ComponentPage] = Seq(
    ComponentPage(
      "rx-values",
      "Variables & derived values",
      "An RxVar holds a value and tells its subscribers when it changes. map, filter, scan, join, and zip derive new streams from it, and each subscriber runs its own copy of the chain.",
      Seq(
        Story(
          "rx-values/derived",
          "map, filter, and scan",
          "Press n + 1 a few times: filter passes only even n, and scan keeps a running sum."
        )(rxDerived),
        Story(
          "rx-values/join-zip",
          "join and zip",
          "Press a + 1 twice, then b + 10: join emits on every change, zip waits for a pair."
        )(rxJoinZip),
        Story(
          "rx-values/equal",
          "Equal values don't emit",
          "Press name := name.get: nothing happens. forceSet emits the same value again."
        )(rxEqual)
      ),
      api = Nil,
      topic = true
    ),
    ComponentPage(
      "rx-time",
      "Time operators",
      "Operators that wait, drop, or sample by time read the clock from a given RxTimeProvider, so tests can move time by hand. Uni has throttleFirst, throttleLast (sample), interval, timer (delay), and timeout.",
      Seq(
        Story(
          "rx-time/throttle",
          "throttleFirst and throttleLast",
          "Click fast for a few seconds: throttleFirst keeps the first click of each second, throttleLast the latest."
        )(rxThrottle),
        Story(
          "rx-time/interval",
          "interval and take",
          "Press Start, then again before it ends: the running interval stops and a new one starts."
        )(rxInterval),
        Story(
          "rx-time/timeout",
          "delay and timeout",
          "Press Fast, then Slow: the slow reply misses the 1 second timeout and recover turns it into a value."
        )(rxTimeout)
      ),
      api = Nil,
      topic = true
    ),
    ComponentPage(
      "rx-async",
      "Async work & flatMap",
      "flatMap starts an inner stream for each value and cancels the one before it, so only the newest request answers. An inner stream that fails ends the flatMap.",
      Seq(
        Story(
          "rx-async/switch",
          "Cancel the previous request",
          "Type a word quickly: every keystroke but the last is cancelled, and only the last query answers."
        )(rxSwitch),
        Story(
          "rx-async/future",
          "Rx.future and failures",
          "Press Request, then Fail the next request and Request again: the plain lane ends, the guarded lane goes on."
        )(rxFuture)
      ),
      api = Nil,
      topic = true
    ),
    ComponentPage(
      "rx-errors",
      "Errors",
      "An OnError reports a failure and the stream goes on; a stream ends with OnCompletion, or with OnFailure when it failed. A throwing map gives an OnError, and a failed source an OnFailure.",
      Seq(
        Story(
          "rx-errors/resume",
          "An error that the stream goes past",
          "Press n + 1 until n is 3, then once more: the error marks one event, and only run stops."
        )(rxResume),
        Story(
          "rx-errors/terminal",
          "A failure that ends the stream",
          "Press Next value, then setException: every lane ends, and Next value does nothing until Restart."
        )(rxTerminal)
      ),
      api = Nil,
      topic = true
    ),
    ComponentPage(
      "rx-lifecycle",
      "Subscriptions",
      "A subscription runs until it is cancelled or its stream ends. RxTracker lists the ones still running, to find a subscription nobody cancels.",
      Seq(
        Story(
          "rx-lifecycle/tracker",
          "subscribe, cancel, and RxTracker",
          "Press subscribe twice, then counter + 1: each subscription runs the handler. Cancel one and press counter + 1 again."
        )(rxTracker),
        Story(
          "rx-lifecycle/end",
          "What ends a stream",
          "Press n + 1 three times: take(3) completes. Then press n.stop(): every lane completes."
        )(rxEnd)
      ),
      api = Nil,
      topic = true
    )
  )

end RxStories
