---
description: Theme uni-ui with light and dark color schemes and --uni-* CSS variables, without touching Scala code.
---

# Theming

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

To change colors, set the variables after `uni-ui.css`:

```css
:root {
  --uni-accent: #6d28d9;
  --uni-accent-2: #8b5cf6;
  --uni-radius: 4px;
}
```

| Variable | Used for |
|----------|----------|
| `--uni-canvas`, `--uni-surface`, `--uni-panel`, `--uni-raised` | Backgrounds, from the window down to raised controls |
| `--uni-fg`, `--uni-muted`, `--uni-faint` | Text, from primary to placeholder |
| `--uni-line`, `--uni-hover` | Borders and hover fills |
| `--uni-accent`, `--uni-accent-2`, `--uni-accent-fg`, `--uni-accent-soft` | The primary button gradient, its text, and focus rings |
| `--uni-danger`, `--uni-danger-soft` | Danger buttons and invalid fields |
| `--uni-success`, `--uni-success-soft` | Success toasts |
| `--uni-overlay-shadow`, `--uni-backdrop` | Overlay shadows and the dialog backdrop |
| `--uni-tooltip-bg`, `--uni-tooltip-fg` | Tooltip colors |
| `--uni-z-popover`, `--uni-z-dialog`, `--uni-z-toast` | Stacking order of popovers and menus, dialogs, and toasts and tooltips |
| `--uni-radius-sm`, `--uni-radius`, `--uni-radius-lg` | Corner radii |
| `--uni-font-sans`, `--uni-font-mono`, `--uni-font-size`, `--uni-font-size-sm` | Fonts |

Every rule in `uni-ui.css` sits in the `uni` cascade layer, so your own CSS overrides it without
`!important` (see [Loading uni-ui.css](./#loading-uni-ui-css)). To style one part of a component,
select its [`data-slot`](./changes#parts) and `data-state` attributes.
