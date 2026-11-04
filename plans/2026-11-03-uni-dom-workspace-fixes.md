# Fix uni-dom pitfalls found while building uni-harness

## Goals

- Code that looks right in uni-dom should behave right: four bugs from uni-harness (#848) each look correct but fail silently.
- Once fixed, drop the uni-harness workarounds so the example shows the plain idiom.
- Collect the larger ergonomics ideas (testing helpers, widgets) as follow-ups, not in this PR.

## Background

- `Cancelable.merge(c1, c2)` takes its arguments by name. DomRenderer depends on that: its Rx-child and Rx-attribute cases pass a `var c1` that is reassigned on each update, so cancel must read the latest value. Callers that pass `rx.subscribe(...)` never subscribe at all.
- `value.bind` sets the `oninput` *property*, and so does a user's `oninput -> f`. Whichever is rendered last wins, with no warning.
- Rx children and Rx attributes clear their rendered output on every event, including `OnCompletion`. `Rx.const` emits `OnNext` then `OnCompletion`, so its content is drawn and then removed right away. Reproduced in uni-dom-test for both children and attributes.
- 21 boolean attributes (`disabled`, `hidden`, `readonly`, `required`, …) are plain `DomNode` values built with `attr(x).noValue`, so `disabled -> rx` does not compile.

## Design

Lane: Standard. It changes public API semantics (`Cancelable.merge`, the boolean attribute types), and the launch prompt asked for the design gate.

- **merge is strict.** `Cancelable.merge(c1, c2)` evaluates both arguments when called, matching the `Seq` overload. DomRenderer's reassigned-var call sites switch to an explicit `Cancelable(() => ...)` that reads the latest value at cancel time.
- **Bindings use addEventListener.** `value.bind` / `checked.bind` attach with `addEventListener` and remove the listener on cancel. A user's `oninput` / `onchange` then runs alongside the binding, in registration order.
- **Completion keeps content.** Rx children and Rx attributes ignore `OnCompletion` and keep the last rendered value. Only a new `OnNext` or an `OnError` replaces it. A uni-dom-test repro comes first.
- **Boolean attributes accept values.** A new `DomBooleanAttribute` is still a `DomNode`, so `button(disabled)` keeps working, and it also takes `disabled -> true` and `disabled -> rx`. All 21 `noValue` attributes in HtmlAttrs switch to it. `false` removes the attribute and clears the matching DOM property, as `checked -> false` already does.
- **uni-harness cleanup.** Shell/Chat go back to two-argument `merge`, plain `disabled -> rx`, and `Rx.const` in the Dock. The composer keeps growing from the draft rather than `oninput`, because that also shrinks it when submit clears the draft. No UI changes.
- **Docs.** Update `docs/dom/index.md` for `disabled -> rx`. `for-ai-agents.md` and the `uni` skill do not cover these APIs, so they stay unchanged.
- Tests cover strict merge, user `oninput` alongside `value.bind`, `Rx.const` children and attributes, and `disabled -> Rx[Boolean]` toggling both the attribute and the property.

### Follow-ups (separate PRs, proposed)

- **Testing kit:** publish `DomTestSupport` as a `uni-dom-test` artifact (#6) with a `settle()` that waits for `onMount` (#5), plus pointer events and `isComposing` in `fireEvent` (#7).
- **Widgets:** an IME-aware `onEnter` and a pointer-drag / resizable pane helper (#8).
- **Plumbing:** a derived `StorageCodec` for enums, `ElectronRenderer.installIfAvailable()`, and static file serving for `Http.server` (#8). Each is small and can ship on its own.

## Alternatives and Why Not?

- **Keep merge by-name and document it.** The mistake is easy to make and hard to see. A strict signature is what callers expect.
- **Make `value.bind` call the user's `oninput` itself.** This couples two unrelated modifiers. With `addEventListener` both run without either knowing about the other.
- **Overload `HtmlTags.disabled(rx)` instead of a new type.** `disabled -> rx` matches `checked -> rx` and every other attribute.
