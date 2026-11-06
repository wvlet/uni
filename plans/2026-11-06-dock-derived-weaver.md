# Derive the Dock layout codec with `derives Weaver`

## Goals

- Replace the hand-written `Weaver[DockNode]` (about 90 lines of JSON reading and writing) with `derives Weaver`, now that #878 handles recursive enums with parameterized cases.
- Layouts that apps already saved (uni-harness keeps one in localStorage) keep loading.
- Load-time normalization stays: unknown panel ids and bad sizes never break a save.

## Background

- The saved v1 format is `{"version":1,"root":{"type":"split","direction":"row",...}}`. A derived codec writes `{"@type":"Split","direction":"Row",...}`.
- When decoding, the derived sum codec already matches the case name loosely (`"split"` finds `Split`), but the enum codec matches `SplitDirection` by its exact name (`"row"` fails). Both write the names as declared.
- `WeaverConfig` is passed per call, not per type. A derived `DockNode` inside an app's own derived state picks up that app's discriminator setting.
- Normalization runs in `Dock` on every layout it receives, not in the decoder. Sizes, unknown ids, and empty groups are fixed there whichever codec decoded the layout.
- The Dock ADR already says a format change needs a new `version` plus a migration in `fromJSONValue`.

## Design

Lane: Standard. It changes a persisted JSON format and adds a migration, which is an always-gate area.

- `DockNode` and `SplitDirection` use `derives Weaver`; `DockLayout` keeps a thin envelope given. The hand-written `toJSONValue`/`fromJSONValue` for nodes and the `JSONValueCodec` helper go away.
- Bump the format to `version: 2`, whose `root` is exactly the derived JSON: `{"@type":"Split","direction":"Row","children":[...],"sizes":[...]}` and `{"@type":"Group","panels":[...],"active":"a"}`.
- Keep the version envelope as a small hand-written step. It writes `{"version":2,"root":...}` and checks the version on read. Newer versions and unreadable saves still decode to None, so `Storage.local` falls back to the default.
- The envelope always encodes `root` with a fixed `WeaverConfig`, so an app's discriminator setting can't change what gets saved. It omits `None` fields, so a group without `active` stays compact.
- v1 migration rewrites the JSON tree and nothing else: `type` becomes `@type`, and `split`/`group` and `row`/`column` take their declared case names. The derived codec then decodes the result. It is a few lines, not a second codec.
- The next save writes v2. A downgraded app treats a v2 save as unknown and falls back to its default, as the ADR already expects.
- Decoding gets stricter in one place: a malformed node (such as an unknown `@type`) now fails the whole save, and the app falls back to its default. Before, the hand-written reader dropped just that node. Apps write saves themselves, so this only matters for hand-edited storage.
- Tests cover a v2 round trip, v1 saves (split, group, missing `active`, bad sizes) loading and then normalizing, and newer or unreadable saves returning None.
- Update the Dock ADR (alternative is now taken, format is v2), `docs/dom/ui.md` if it mentions the shape, and the AI-agent page and skill only if they show the JSON.

## Alternatives and Why Not?

- **Keep v1 through a case-name mapping option in `WeaverConfig`:** adds public Weaver API for one caller. The option is per call, so a Dock nested in app state would still follow the app's config, and the saved shape wouldn't be fixed.
- **Per-type name annotations (such as `@name("split")`):** a bigger Weaver and Surface feature than this change needs. It can come later without another format bump only if it reproduces v2's names.
- **Drop v1 saves and reset to the default:** users lose their arrangement on upgrade for the sake of a few lines.
