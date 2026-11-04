# Draw block and line characters to fill the terminal cell

## Goals

- Make block art, such as Claude Code's mascot (`▐▛███▜▌`), and box lines draw as solid shapes in `TerminalView`, with no gaps between rows or columns.

## Background

- Rows are `fontSize * lineHeight` tall (1.25 by default), but a font draws `█` or `│` only as tall as its own glyph box. The extra line spacing shows as horizontal stripes across block art and gaps in vertical lines.
- Lowering the line height to 1.0 narrows the gaps but does not remove them: many fonts' block glyphs stop short of the line box, and 1.0 makes ordinary text cramped. xterm.js has the same problem and solves it by drawing these characters itself ("custom glyphs").

## Design

Lane: UI. This fixes a visual bug on the existing Terminal pane, with no new setting or flow.

- Draw block elements (U+2580–U+259F: halves, eighths, quadrants, and the `░▒▓` shades) from rectangles in cell fractions, as CSS gradient layers in the cell's foreground color, sized to the exact cell in px.
- Draw the light and heavy straight lines, corners, tees, and crosses of box drawing the same way: segments from the cell center to each edge, 1 px light and 2 px heavy. Rounded corners (`╭╮╰╯`), dashes, and double lines keep the font's glyph.
- The character stays in the DOM with a transparent text fill, so selection and copy still give `█`, and the cursor, inverse, and colors work as before.
- Keep the default line height at 1.25, so ordinary text keeps its spacing. Block art no longer depends on it.
- Tests check that a block character fills the cell (its size equals the cell) and still copies as text.

## Alternatives and Why Not?

- **Default `lineHeight` to 1.0.** Text gets cramped, and the gaps stay in fonts whose block glyphs are shorter than the em box.
- **A canvas renderer.** It is out of scope (no WebGL or canvas renderer here), and it is a large change for a few hundred characters.
