# Community post draft: how Uni fits next to the Scala Toolkit

Drafts for the maintainer to review and post. Nothing here has been published.
Links to check before posting: both guide pages must be live on wvlet.org after the PR merges.

- Comparison: https://wvlet.org/uni/guide/uni-vs-scala-toolkit
- Tutorial: https://wvlet.org/uni/guide/toolkit-to-uni
- Scala Toolkit: https://docs.scala-lang.org/toolkit/introduction.html
- Uni: https://github.com/wvlet/uni

## Scala Users forum (users.scala-lang.org, category: Announcements or Libraries)

**Title:** How Uni fits next to the Scala Toolkit

Hi all,

I maintain [Uni](https://github.com/wvlet/uni), a Scala 3 library that grew out of
Airframe. People often ask how it relates to the
[Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html), so we wrote a
page about it, and I'd like to share it here and hear your thoughts.

First, credit where it's due: the Toolkit is a great on-ramp to Scala. `//> using toolkit latest`
gives you sttp, uPickle, os-lib, and MUnit in one line, and its tutorials on
docs.scala-lang.org are some of the friendliest Scala material around. Thanks to the
Scala Center, the Toolkit maintainers, and the authors of sttp (SoftwareMill),
uPickle and os-lib (Li Haoyi and contributors), and MUnit (Scalameta) for it.

Uni covers a different part of the stack. It's one library for building Scala 3
applications and services: object wiring with lifecycle hooks (Design), logging, JSON
and MessagePack, an HTTP client and server, typed RPC, MCP servers, Rx, and a test
framework, with the same API on the JVM, Scala.js, and Scala Native.

The two work together, so the page focuses on how to choose:

- **Toolkit:** scripts, teaching, Scala 2.13 or Scala 3 before 3.9, running
  subprocesses with os-lib, or when you need just one or two of these libraries.
- **Uni:** a Scala 3.9+ service that needs object wiring, logging, an HTTP server or
  RPC, or the same code on all three platforms.
- **Both:** the packages don't overlap. A MUnit suite can test Uni code, and you can
  keep os-lib for subprocesses.

Page: https://wvlet.org/uni/guide/uni-vs-scala-toolkit

We also wrote a tutorial: [From a Scala Toolkit script to a Uni service](https://wvlet.org/uni/guide/toolkit-to-uni).
It starts with a small sttp + uPickle script that reads a GitHub repository. Step by
step, it becomes a service with Design wiring, logging, a REST endpoint, and tests.
sttp and uPickle keep doing the GitHub calls and decoding. The code is an
[example project](https://github.com/wvlet/uni/tree/main/examples/toolkit-service)
that CI builds and tests with the library versions Toolkit 0.9.2 uses, so the
tutorial can't drift out of date.

If anything on the page is inaccurate or unfair to the Toolkit or its libraries,
please tell me and I'll fix it.

## r/scala

**Title:** Uni and the Scala Toolkit: how they fit together (with a script-to-service tutorial)

The [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html) (sttp,
uPickle, os-lib, MUnit) is the easiest way to start with Scala, and many of our
projects start as a Toolkit script. When a script becomes a service, you need
object wiring, logging, and an HTTP server. That's where
[Uni](https://github.com/wvlet/uni), a Scala 3 library (the successor of Airframe),
comes in.

We wrote two pages:

- [Uni and the Scala Toolkit](https://wvlet.org/uni/guide/uni-vs-scala-toolkit): a
  feature table, when each one fits, side-by-side code, and an FAQ.
- [From a Scala Toolkit script to a Uni service](https://wvlet.org/uni/guide/toolkit-to-uni):
  an sttp + uPickle script becomes a service with Design wiring, LogSupport, a REST
  endpoint, and UniTest. sttp and uPickle stay. The code is a CI-tested example.

Thanks to the Toolkit maintainers and the authors of its libraries. Feedback is
welcome, especially corrections.

## X (≤ 280 characters)

The Scala Toolkit is the easiest way to start with Scala. When your script grows into
a service, Uni adds object wiring, logging, and an HTTP server, and sttp and uPickle
stay. How they fit together: https://wvlet.org/uni/guide/uni-vs-scala-toolkit #Scala

## Bluesky (≤ 300 characters)

Uni and the Scala Toolkit work well together. The Toolkit (sttp, uPickle, os-lib,
MUnit) is great for scripts. Uni adds object wiring, logging, HTTP servers, and RPC
when a script becomes a service. A guide and a CI-tested tutorial:
https://wvlet.org/uni/guide/uni-vs-scala-toolkit

## LinkedIn

Many Scala projects start as a Scala Toolkit script, and that's a great place to
start. The Toolkit bundles sttp, uPickle, os-lib, and MUnit, with friendly tutorials
on docs.scala-lang.org.

When a script becomes a service, it needs object wiring, logging, and an HTTP
server. We wrote a guide to how Uni, our Scala 3 library, fits next to the Toolkit,
plus a tutorial that grows a Toolkit script into a service while keeping sttp and
uPickle:

- https://wvlet.org/uni/guide/uni-vs-scala-toolkit
- https://wvlet.org/uni/guide/toolkit-to-uni

Thanks to the Scala Center, the Toolkit maintainers, and the library authors for
the Toolkit.
