# How detection works

Color level (`supports-color.core/profile`) walks an ordered chain of checks, short-circuiting on a match:

`CLI` flags (`--color`, `--no-color`, `--color=256`, etc.)<br>
`NO_COLOR`<br>
`FORCE_COLOR`<br>
`CI` environment variables (`CI`, `GITHUB_ACTIONS`, `TRAVIS`, and similar),<br>
`CLICOLOR`/`CLICOLOR_FORCE`,<br>
`TERM` and `COLORTERM` patterns<br>

Falls back to whether the output stream is a TTY at all.

<br />

Background (`supports-color.core/background`) tries, in order:

1. `CLITHEME` env var - explicit user preference.
2. `CSI ? 996 n` terminal query - OS theme preference (Contour, Ghostty, Kitty, and similar).
3. `OSC 11` terminal query - actual background RGB, converted to luma.
4. `COLORFGBG` env var - coarse fallback.

`supports-color.core/theme` runs the same chain. When step 1 decides the polarity, it sends no terminal query.

Steps 2 and 3 use the same terminal query. `background` sends three queries together, in one write:

- `CSI ? 996 n` asks for the theme, dark or light.
- `OSC 11` asks for the background color.
- `ESC[c` (DA1, device attributes) asks what kind of terminal it is. Nearly every terminal answers it, and it goes last.

Terminals answer in the order they were asked, and ignore queries they don't support. So when the DA1 reply arrives, every other reply the terminal is going to send has arrived too, and the read stops there. The chain above then picks from the replies in hand: the theme answer if there is one, otherwise the color answer. `:background-color` is filled whenever the color answer came back, whichever step decided the polarity.

This design means:

- **A single exchange.** All three answers come back together. On a local terminal that takes a few milliseconds. Over SSH it costs the time of one exchange, not one per query. [Late replies over slow links](slow-links.md) has measured times.
- **One short window.** The terminal is switched to non-canonical, no-echo mode once, for the read, and restored afterward. A single short window leaves less chance to swallow keys the user types, or to overlap another program starting on the same terminal.
- **No waiting on a terminal that replies.** The read ends at the DA1 reply, so a long `:timeout-ms` costs nothing there. Only a terminal that answers nothing makes the call wait the full timeout.
- **No garbage left over.** Every reply is read before the terminal is restored, so no garbage strings, chars, or codes show up on screen or as input to the program.

The query is skipped when neither stdout nor stderr is a terminal (CI, daemons, `cli > log 2>&1`), so no subprocess starts and nothing blocks there. It still runs when only stdout is redirected, for example `cli | less`, because stderr is still the terminal. See [More details about how supports-color works](more-details.md) for every case.

The full list of environment variables, with their conventional values, provenance, and description, is documented as a Malli schema: `supports-color.common/MockEnv`(this library does not depend on Malli).
