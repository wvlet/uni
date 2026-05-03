# #526 — `when` / `unless` reachable from `wvlet.uni.dom.all.*`

## Findings

The exports already work as documented:

- `wvlet/uni/dom/all.scala:155-156` exports `wvlet.uni.dom.when` and
  `wvlet.uni.dom.unless`.
- `uni-dom-test/.../DomElementTest.scala:90-98` already exercises `when` /
  `when false` through `import wvlet.uni.dom.all.*` and passes.
- Running `./sbt 'domTest/testOnly wvlet.uni.dom.DomElementTest'` is green
  (18/18) on a clean checkout.

So the issue's first option applies: "Confirm `when` / `unless` are in
scope through `import wvlet.uni.dom.all.*`".

## Approach

This is a "works as designed" fix:

1. Add an explicit regression test for `unless` (today only `when` is
   covered, so a future refactor of `all.scala` could silently drop the
   `unless` export with green tests).
2. Add a small doc snippet on `all.scala`'s `export` block that names
   `when` and `unless` as the airframe-rx-html migration path.
3. Close issue #526 from the PR.

## Files to change

- `uni-dom-test/src/test/scala/wvlet/uni/dom/DomElementTest.scala` — add
  two `unless` tests mirroring the existing `when` ones.
- `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` — replace the bare
  `Re-export helper functions` doc with a hint that names the
  airframe-rx-html → uni migration path.

## Out of scope

- Wholesale moving `when` / `unless` into the `all` object body (the
  issue suggested this only as a fallback if exports were broken; they
  aren't).
- Other migration helpers (`onClick`, `onChange`, etc.) — covered by
  separate sibling issues.
