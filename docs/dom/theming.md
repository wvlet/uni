---
description: Theme uni-ui with light and dark color schemes and --uni-* CSS variables, without touching Scala code.
---

# Theming

`uni-ui.css` draws every component from `--uni-*` CSS variables: colors, corner radii, and fonts.
Set the variables in your own CSS to restyle the whole app, with no Scala changes. The examples on
this page are live; open one in the [gallery](/gallery/index.html#/theming){target="_self"} to see
its code.

## Light and dark

`uni-ui.css` reads `data-theme` on `<html>`: `light` or `dark` forces a scheme, and no attribute
follows the OS setting. `ColorScheme` sets the attribute from an `Rx`:

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions

val scheme = Storage.local("app.colorScheme", ColorScheme.System)
ColorScheme.bind(scheme)
scheme := ColorScheme.Dark
```

## Your brand color

Set the accent variables after `uni-ui.css`. `color-mix` derives the soft halo and the focus ring
from the accent, so one color drives them all:

```css
:root {
  --uni-accent: #6d28d9;
  --uni-accent-2: #8b5cf6;
  --uni-accent-soft: color-mix(in srgb, var(--uni-accent) 14%, transparent);
  --uni-focus: color-mix(in srgb, var(--uni-accent) 45%, transparent);
}
```

The primary button's gradient runs from `--uni-accent` to `--uni-accent-2`. Accent badges and the
halo of a focused field follow the accent too:

<UiGallery story="theming/accent" embed height="360px" />

## Dark values

A plain `:root` rule applies in both schemes, because your CSS sits outside the `uni` layer and
wins over both of uni-ui's blocks. To give the dark scheme its own colors, repeat uni-ui.css's
selectors: one for the OS preference, one for `data-theme="dark"`:

```css
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) {
    --uni-accent: #a78bfa;
    --uni-accent-2: #c4b5fd;
    --uni-accent-fg: #1a0b2e;
  }
}

:root[data-theme='dark'] {
  --uni-accent: #a78bfa;
  --uni-accent-2: #c4b5fd;
  --uni-accent-fg: #1a0b2e;
}
```

## Themed regions

Custom properties inherit, so variables set on an element theme only the components inside it: a
dark sidebar in a light app, or a highlighted panel. Each card below sets its own surface, text,
and accent variables:

```css
.sidebar {
  color-scheme: dark;
  --uni-surface: #141826;
  --uni-fg: #e6e9f5;
  --uni-line: #2f3752;
  background: var(--uni-surface);
  color: var(--uni-fg);
}
```

<UiGallery story="theming/palettes" embed height="420px" />

A variable defined with `var()`, such as the `color-mix` halo above, is resolved where it is
declared. When a region changes `--uni-accent`, declare `--uni-accent-soft` and `--uni-focus` on
the region too.

## Corners and text size

Radii and font sizes are variables too:

```css
:root {
  --uni-radius-sm: 8px;
  --uni-radius: 14px;
  --uni-radius-lg: 20px;
  --uni-font-size: 14px;
}
```

<UiGallery story="theming/shape" embed height="360px" />

## Variables

The gallery's [Colors](/gallery/index.html#/colors){target="_self"} page shows each color variable
below as a swatch, in light and dark side by side, with its value; click one to copy its `var()`.

| Variable | Used for |
|----------|----------|
| `--uni-canvas`, `--uni-surface`, `--uni-panel`, `--uni-raised` | Backgrounds, from the window down to raised controls |
| `--uni-fg`, `--uni-muted`, `--uni-faint` | Text, from primary to placeholder |
| `--uni-line`, `--uni-hover`, `--uni-focus` | Borders, hover fills, and focus rings |
| `--uni-accent`, `--uni-accent-2`, `--uni-accent-fg`, `--uni-accent-soft` | The primary button gradient, its text, and the halo of focused fields and selected rows |
| `--uni-glow` | The dot of an accent badge, in both themes |
| `--uni-danger`, `--uni-danger-soft` | Danger buttons and invalid fields |
| `--uni-success`, `--uni-success-soft` | Success toasts |
| `--uni-overlay-shadow`, `--uni-backdrop` | Overlay shadows and the dialog backdrop |
| `--uni-tooltip-bg`, `--uni-tooltip-fg` | Tooltip colors |
| `--uni-z-popover`, `--uni-z-dialog`, `--uni-z-toast` | Stacking order of popovers and menus, dialogs, and toasts and tooltips |
| `--uni-radius-sm`, `--uni-radius`, `--uni-radius-lg` | Corner radii |
| `--uni-font-sans`, `--uni-font-mono`, `--uni-font-size`, `--uni-font-size-sm` | Fonts |

## Style one part

Every rule in `uni-ui.css` sits in the `uni` cascade layer, so your own CSS overrides it without
`!important` (see [Loading uni-ui.css](./#loading-uni-ui-css)). To style one part of a component,
select its [`data-slot`](./changes#parts), and its state through ARIA or `data-state` attributes:

```css
[data-slot='button'] {
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
[data-slot='badge'] {
  border-radius: 999px;
}
[data-slot='tab'][aria-selected='true'] {
  color: var(--uni-accent);
  font-weight: 600;
}
```

<UiGallery story="theming/parts" embed height="300px" />
