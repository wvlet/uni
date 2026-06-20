# The Uni Book

*A guided tour of Scala 3 application development with Uni.*

This book teaches you to build real applications with
[Uni](https://github.com/wvlet/uni) on Scala 3, across the JVM,
Scala.js, and Scala Native. It is written for developers who already
read Scala comfortably and want to understand not only *how* Uni works
but *why* its pieces are shaped the way they are.

The Book is narrative. It is meant to be read front to back at first,
and used as a jumping-off point afterwards. The short-form API
documentation under [Guide](/guide/) and the module references
(e.g. [Core](/core/), [HTTP](/http/)) stay where they
are, and the Book links into them when you need depth.

## How to read it

- Work from Part I to the end of Part II in order. Everything that
  follows assumes the mental model built in those two parts.
- Each part opens with a short intro page; each chapter ends with a
  *"what you have, what comes next"* recap. If the recap doesn't feel
  true yet, re-read the chapter before moving on.
- Type the examples. Reading them is not the same exercise.

## Table of contents

### [Foreword](./foreword)

### Part I — Getting Started

- [Chapter 1: Getting Started](./ch01-00-getting-started)
  - [1.1 Installation](./ch01-01-installation)
  - [1.2 Hello, Uni!](./ch01-02-hello-uni)

### Part II — Building a CLI Application

- [Chapter 2: A URL Fetcher](./ch02-00-cli-app)

### Part III — Core Concepts

- [Chapter 3: Wiring with Design](./ch03-00-design)
- [Chapter 4: Logging That Finds You](./ch04-00-logging)
- [Chapter 5: Data In, Data Out — JSON & MessagePack](./ch05-00-data)

### Part IV — Async & Control Flow

- [Chapter 6: Rx, the Composable Stream](./ch06-00-rx)
- [Chapter 7: Living With Failure — Retry, Circuit Breakers, Resources](./ch07-00-control)

### Part V — HTTP & RPC

- [Chapter 8: HTTP Clients and Servers](./ch08-00-http)
- [Chapter 9: Typed RPC](./ch09-00-rpc)

### Part VI — Cross-Platform Development

- [Chapter 10: One Codebase, Three Runtimes](./ch10-00-cross-platform)

### Part VII — Testing

- [Chapter 11: Testing with UniTest](./ch11-00-testing)

### Appendices

- [Appendix A: Scala 3 Syntax Notes for This Book](./appendix-a-scala3)
- [Appendix B: Uni and Airframe — History and Relationship](./appendix-b-airframe)
- [Appendix C: Glossary](./appendix-c-glossary)

## Status

Parts I through III are drafted. Parts IV through VII are stubs with
outlines; they will ship in follow-up pull requests. If a link leads to
a "Coming soon" page, that is working as intended.
