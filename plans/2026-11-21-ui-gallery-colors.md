# Colors page for the uni-ui gallery

## Goals

- Show every uni-ui color token on one page, the way Tailwind's colors page shows its palettes: named rows of swatches, each with its name and value, one click to copy.
- Let an app author see, before overriding a variable, what it looks like in light and dark and which components use it.

## Background

- uni-ui's look is the `--uni-*` variables in `uni-ui.css` (the `uni` layer); Scala code never names a color. `data-theme` on `<html>` picks light or dark, and no attribute follows the OS.
- There are 23 color tokens (backgrounds, text, lines, accent, status, overlays), each a literal per theme, except `--uni-glow`, which has one value for both. The status colors match Tailwind's orange-700/500 and green-700/400. `docs/dom/theming.md` lists them in one table without their values.
- The light and dark values are not steps of one ramp: light text is `#10272c`/`#5b7176`/`#93a5a9`, dark text `#e3eff1`/`#8aa4a9`/`#56706f`; the accent is `#2e7685` in light and `#25a7c1` in dark, with `#3c99ad`, `#4ec5dd`, and `#08bdcb` beside them.
- Every gallery page today is a `ComponentPage` (examples, API, accessibility). The tests require each to have examples and an API.

## Design

- Lane: UI. A new page in the example gallery app and a docs link; no change to uni-ui's library code or CSS values. The launch prompt already gives the full design and merge authorization (after a quick-review pass), so the work proceeds without a design card.
- **User flow.** From the sidebar's new **Foundations** group (or ⌘K "Colors", or the link on the docs' Theming page), open `#/colors`. Each role is a row of swatches; each swatch is split into a light half and a dark half, labeled with the variable name, both values, and what uses it. Click a swatch to copy `var(--uni-accent)`; click a value to copy the value. The swatch says "Copied" for a moment.
- **Groups**, in order: Backgrounds (canvas, surface, panel, raised), Text (fg, muted, faint), Lines & fills (line, hover, focus), Accent (accent, accent-2, accent-fg, accent-soft, glow), Status (danger, danger-soft, success, success-soft), Overlays (backdrop, overlay-shadow, tooltip-bg, tooltip-fg).
- **Live values in both themes at once.** A hidden probe frame loads the page's own stylesheets twice, once with `data-theme="light"` and once with `"dark"`, and reads each token with `getComputedStyle`. So the page shows what the cascade gives, including an app's overrides, without flipping the theme of the page the user is looking at.
- **Translucent tokens** (panel, hover, line, soft fills, backdrop) are painted over that theme's canvas, so the swatch looks like it does in use. `--uni-overlay-shadow` is a shadow, so its swatch is a small raised card casting it.
- **Drift check.** A test source generator embeds `uni-ui.css` and the `--uni-*` names it declares; a test fails when the page is missing a color variable (anything but `z-`, `radius`, `font`, `duration`, `overlay-shift`) or lists one that no longer exists. The same embedded CSS lets a test check the resolved light and dark values.
- **Gallery model.** A page is either a component page (as today) or a guide page with its own view. Routing, the sidebar, the rail (with its own palette icon), ⌘K, and embed mode (`#/colors?embed` shows the page) take both; the component-only checks (examples, API, accessibility) stay on component pages.
- **No shade scales, for now.** Tailwind-style 50–950 ramps (`--uni-color-teal-500`, …) would not pay for themselves here:
  - Keeping every semantic value identical would make the ramps uneven (the light and dark neutrals and the five teals don't sit on one ramp), and evening them out changes how every component looks.
  - Each step becomes public API that apps start to depend on, beside the 23 semantic tokens that already re-brand uni-ui (four variables change the accent).
  - uni-ui is a component library, not a utility-class system; apps that want a full palette already have one (Tailwind's, Open Props), and uni-ui's layer lets it sit alongside.
  - Revisit if uni-ui grows components that need more than the current roles, such as charts or more badge hues than neutral, accent, and danger.
- **Docs.** `docs/dom/theming.md` links to the page from its variable table, and the table gains the missing `--uni-focus` and `--uni-glow` rows (focus rings come from `--uni-focus`, not the accent). No snippet changes; `docSnippetsJS/test` still runs.
- Tests cover: the page lists every token from `uni-ui.css`, swatches show the resolved light and dark values, the route, sidebar, and embed open it, and a swatch click reports its copy (headless Chromium may refuse a synthetic click's clipboard write, so the test accepts either message; the real copy is checked in a Playwright session with clipboard permission).

## Alternatives and Why Not?

- Read the values by switching `<html data-theme>` back and forth inside one task: no flicker in theory, but it restarts CSS transitions across the whole page and fires attribute observers.
- Parse the `uni-ui.css` rules through CSSOM: misses app overrides and has to reimplement the cascade (unlayered app CSS beats the `uni` layer's dark rule).
- Show the page in the current theme only, with a toggle: fails the "light and dark side by side" goal and makes comparing the two harder.
