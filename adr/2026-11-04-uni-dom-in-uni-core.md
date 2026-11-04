# The uni-dom core lives in uni-core on Scala.js

## Context

Apps test their `uni-dom` components with `mount`, `fireEvent`, and the query helpers, which already shipped in `uni.js`. The `UniTest` glue that unmounts each test's mounts (`DomTestSupport`) could not ship anywhere: `uni` can't depend on `uni-test` (the test framework would leak into production code), and `uni-test` can't depend on `uni` (`uni` uses `uni-test` for its own tests, a project cycle). So the trait lived in test scope and apps such as `examples/uni-harness` copied it. The repo has resolved this kind of cycle before by moving foundation code below `uni-test`: logging (#305), the Rx core (#319), and Design/Surface (`2026-10-27-design-surface-in-uni-core.md`).

## Decision

- Move the `wvlet.uni.dom` package (renderer, node types, bindings, observers, and the `wvlet.uni.dom.testing` toolkit) from `uni/.js` to `uni-core/.js`. It needs only `wvlet.uni.rx` and `wvlet.uni.log`, and `scalajs-dom` was already a uni-core.js dependency.
- Keep `Storage.scala` and `all.scala` in `uni`: `Storage` uses `wvlet.uni.config.LocalStorageBackend`, and `all` re-exports `Storage`. The package is split across the two artifacts, which Scala.js allows.
- Ship `DomTestSupport` in `uni-test/.js`, still in package `wvlet.uni.dom.testing`, so a UI test needs only `uni-test`.
- Package names do not change, so user code compiles unchanged; `uni` depends on `uni-core`, so `uni` users still get every type.

## Alternatives

- A separate `uni-dom-testkit` artifact holding only `DomTestSupport`: one more dependency for apps to find and keep in version with `uni`.
- A small lifecycle trait in `uni-core` that `UniTest` extends, so `DomTestSession` can hook `after`: a smaller diff, but apps still mix in two traits.
- Moving `Storage` too: it would pull `wvlet.uni.config` (`SettingStore` and the file-system backends) into uni-core on every platform.

## Consequences

- `uni-core` on Scala.js grows by about 7.5k lines, and every Scala.js `uni-test` user links against it. The Scala.js linker drops what a program doesn't use.
- DOM tests stay in `uni-dom-test`, which runs in headless Chromium; `uni-core`'s own tests cannot use `uni-test` without a cycle.
- Classes move between artifacts, so `uni`, `uni-core`, and `uni-test` must come from the same release. They are always released together.
- New code in `wvlet.uni.dom` must not import from `uni` (for example `config`, `weaver`, `http`); put such code next to `Storage` in `uni`.
