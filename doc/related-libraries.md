# Related libraries

supports-color builds on the work of these libraries.

## chalk/supports-color

The JavaScript library for Node.js that this library's color-level detection is modeled on, including its handling of `FORCE_COLOR` and color flags. See [chalk/supports-color](https://github.com/chalk/supports-color).

## charmbracelet/colorprofile

A Go library for detecting a terminal's color level, from Charm, the makers of the Bubble Tea and Lip Gloss terminal UI tools. supports-color's color-level detection also draws on its environment variable rules. See [charmbracelet/colorprofile](https://github.com/charmbracelet/colorprofile).

## charmbracelet/lipgloss

Charm's Go library for styling terminal output. Its `HasDarkBackground` function asks the terminal for its background color with an `OSC 11` query, sends DA1 right after it, and stops reading when the DA1 reply arrives. supports-color's `supports-color.core/background` uses the same approach, and adds the `CSI ? 996 n` theme query defined by [Contour](https://contour-terminal.org/vt-extensions/color-palette-update-notifications/). See [charmbracelet/lipgloss](https://github.com/charmbracelet/lipgloss).

## termbg

A Rust library that detects the terminal's background color and theme with an `OSC 11` query. It was another reference for the querying in `supports-color.core/background`. See [dalance/termbg](https://github.com/dalance/termbg).

## supports-color in other languages

Other libraries named supports-color, most of them ports of the chalk original:

| Language | Library | Notes |
|---|---|---|
| JavaScript (Node.js) | [chalk/supports-color](https://github.com/chalk/supports-color) | the original, on npm as `supports-color` |
| Python | [supports-color](https://pypi.org/project/supports-color/) ([source](https://github.com/shawwn/supports-color-python)) | a port of the npm package |
| Go | [go-supportscolor](https://github.com/jwalton/go-supportscolor) | a port of the npm package |
| Rust | [supports-color](https://github.com/zkat/supports-color) ([docs](https://docs.rs/supports-color)) | a port of the npm package |
| Deno | [supports_color](https://deno.land/x/supports_color@0.1.0/mod.ts) | a port of the npm package, from the Deno authors |
| Zig | [zig-supports-color](https://github.com/gurgeous/zig-supports-color) | named after it and does the same job, not described as a port |

These detect the color level only. None of them detect the background polarity or color, which supports-color does with `supports-color.core/background`.

## Full comparison matrix

|  | supports-color (this library) | [chalk/supports-color ↗](https://github.com/chalk/supports-color) | [colorprofile ↗](https://github.com/charmbracelet/colorprofile) | [Lip Gloss ↗](https://github.com/charmbracelet/lipgloss) | [termbg ↗](https://github.com/dalance/termbg) |
|---|---|---|---|---|---|
| Language | Clojure dialects | JavaScript, Node | Go | Go | Rust |
| Color level (16, 256, truecolor) | ✓ | ✓ | ✓ | ✓ through colorprofile | ✕ |
| Respects `NO_COLOR` | ✓ | ✕ | ✓ | ✓ through colorprofile | n/a |
| Respects `FORCE_COLOR` | ✓ | ✓ | ✕ | ✕ | n/a |
| `--color`, `--no-color` flags | ✓ | ✓ | ✕ | ✕ | n/a |
| Dark or light background | ✓ | ✕ | ✕ | ✓ | ✓ |
| Background RGB color | ✓ | ✕ | ✕ | ✓ | ✓ |
| `OSC 11` background color query | ✓ | ✕ | ✕ | ✓ | ✓ |
| `CSI ? 996 n` theme query | ✓ | ✕ | ✕ | ✕ | ✕ |
| `COLORFGBG` hint | ✓ | ✕ | ✕ | ✕ | ✓ |
| `CLITHEME` preference | ✓ | ✕ | ✕ | ✕ | ✕ |
| Background query on Windows | ✕ | n/a | n/a | ✓ | ✓ |

"n/a" means the library doesn't do that kind of detection at all. Checked against each library's source in November 2026.
