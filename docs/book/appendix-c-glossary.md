# Appendix C: Glossary

::: warning Coming soon
This appendix is an outline. The full draft ships in a follow-up PR.
:::

## What this appendix will cover

A short glossary of terms as this book uses them. The intent is to
make cross-references precise, not to define every word in Scala.

Terms to include (non-exhaustive):

- **Design** — an immutable description of how types are wired.
- **Session** — a runtime context built from a Design; owns
  singletons and lifecycle hooks.
- **Binding** — one entry in a Design (`bindSingleton`, `bindImpl`,
  etc.).
- **LogSupport** — the trait mixed in to give a class a `logger`.
- **Rx** — Uni's asynchronous / streaming abstraction.
- **Surface** — compile-time type reflection used by the codec layer.
- **Launcher** — the annotation-driven CLI argument parser.
- **Codec** — a bidirectional translator between bytes (JSON /
  MessagePack) and Scala values.

[← Appendix B: Uni and Airframe](./appendix-b-airframe) | [Back to the Book](./)
