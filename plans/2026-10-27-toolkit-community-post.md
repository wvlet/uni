# Community post draft: how Uni fits next to the Scala Toolkit

Drafts for the maintainer to review and post. Nothing here has been published.
Links to check before posting: the FAQ must be live after the PR merges.

- Comparison: https://wvlet.org/uni/guide/uni-vs-scala-toolkit
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

If anything on the page is inaccurate or unfair to the Toolkit or its libraries,
please tell me and I'll fix it.

## r/scala

**Title:** Uni and the Scala Toolkit: how they fit together

The [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html) (sttp,
uPickle, os-lib, MUnit) is the easiest way to start with Scala, and many of our
projects start as a Toolkit script. When a script becomes a service, you need
object wiring, logging, and an HTTP server. That's where
[Uni](https://github.com/wvlet/uni), a Scala 3 library (the successor of Airframe),
comes in.

We wrote a page about it:

- [Uni and the Scala Toolkit](https://wvlet.org/uni/guide/uni-vs-scala-toolkit): a
  feature table, when each one fits, side-by-side code, and an FAQ.

Thanks to the Toolkit maintainers and the authors of its libraries. Feedback is
welcome, especially corrections.

## X (≤ 280 characters)

The Scala Toolkit is the easiest way to start with Scala. When your script grows into
a service, Uni adds object wiring, logging, and an HTTP server, and sttp and uPickle
stay. How they fit together: https://wvlet.org/uni/guide/uni-vs-scala-toolkit #Scala

## Bluesky (≤ 300 characters)

Uni and the Scala Toolkit work well together. The Toolkit (sttp, uPickle, os-lib,
MUnit) is great for scripts. Uni adds object wiring, logging, HTTP servers, and RPC
when a script becomes a service. How they fit together:
https://wvlet.org/uni/guide/uni-vs-scala-toolkit

## LinkedIn

Many Scala projects start as a Scala Toolkit script, and that's a great place to
start. The Toolkit bundles sttp, uPickle, os-lib, and MUnit, with friendly tutorials
on docs.scala-lang.org.

When a script becomes a service, it needs object wiring, logging, and an HTTP
server. We wrote a guide to how Uni, our Scala 3 library, fits next to the Toolkit,
and how to keep the Toolkit libraries you already use:

https://wvlet.org/uni/guide/uni-vs-scala-toolkit

Thanks to the Scala Center, the Toolkit maintainers, and the library authors for
the Toolkit.
