# Manual terminal testing of background queries

The `supports-color.core/background` function detects whether the terminal's background is dark or light, by sending the terminal `CSI ? 996 n` and `OSC 11` queries and reading the replies. It talks to the terminal directly, so the automated tests, which run against a simulated terminal, can't fully cover it. Because of this, manual tests in real terminal emulators are required. This page records the checks run by hand in 7 terminal emulators, plus tmux, GNU screen and SSH sessions: what was run, where, when, and what came back.

All manual tests were done on macOS 15.5, between 2026-11-10 and 2026-11-11.

## Test criteria

Each run calls `supports-color.core/background` once and prints four values:

- `:polarity`: `"dark"`, `"light"`, or `nil`
- `:background-color`: the RGB color the terminal reported
- `:detection-method`: which answer decided the polarity
  - `:csi996`: the terminal reported its theme
  - `:osc11`: the terminal reported its background color
  - `:none`: no answer was used
- `:elapsed-ms`: how long the `supports-color.core/background` call took

This is an example result, from Ghostty with a dark theme:

```clojure
{:polarity         "dark"
 :background-color {:r 33 :g 37 :b 43}
 :detection-method :csi996
 :elapsed-ms       50.3}
```

A run passes when:

- **Result:** the polarity matches the terminal's theme, and the color is plausible.
- **Screen:** nothing other than the printed result appeared on screen.
- **Prompt:** after the run, the next command line was empty and typing showed as normal.
- **Settings:** the terminal settings were the same before and after the run (`stty -g` compared).

In the tables, "yes" means the check passed, and "-" means it wasn't checked on that run.

Runtimes:

| Name | What it means |
|---|---|
| bb | Babashka |
| nbb | Node, through nbb |
| jvm | the Clojure CLI |
| jolt | Jolt |

## Summary

| Terminal | Version | Answers | Themes tested | Runtimes | Date | Result |
|---|---|---|---|---|---|---|
| Terminal.app | 2.14 | background color | dark, light | bb, nbb | 2026-11-10 | pass |
| iTerm2 | 3.6.11 | background color | dark, light | bb, nbb | 2026-11-10 | pass |
| VS Code terminal | 1.142.0 | theme and color | dark, light | bb, nbb | 2026-11-10 | pass |
| Alacritty | 0.14.0 | background color | dark | bb, nbb | 2026-11-10 | pass |
| Ghostty | 1.3.1 | theme and color | dark, light | bb, nbb, jvm, jolt | 2026-11-10 | pass |
| Kitty | 0.50.0 | theme and color | dark, light | bb, nbb, jvm | 2026-11-11 | pass |
| WezTerm | 20240203-110809-5046fc22 | background color | dark | bb, nbb | 2026-11-10 | pass |
| tmux, inside Ghostty | 3.8 | theme and color | light | bb, nbb | 2026-11-11 | pass |
| GNU screen, inside Terminal.app | 4.00.03 | neither, polarity `nil` | dark | bb, nbb | 2026-11-10 | pass |
| SSH, local link | OpenSSH to a Debian 13 VM | same as the local terminal | dark | bb, nbb | 2026-11-10 | pass |
| SSH, simulated slow link | as above | same as the local terminal | light, dark | bb, nbb | 2026-11-11 | pass with the 2000 ms default |
| `TERM=dumb` | in Terminal.app | query skipped | dark | bb, nbb | 2026-11-10 | pass |

"Theme" means the terminal answers the CSI ?996 theme query.

"Background color" means it answers the OSC 11 color query.

When a terminal answers both, the theme answer decides the polarity, and the color is still reported.

## How to run a manual check

The check is the script [test/supports_color/manual_background_check.cljc](../test/supports_color/manual_background_check.cljc). It prints only the four values above, not the whole result map, which also describes your environment. Run it from the repository root, in the terminal you're testing:

```sh
bb --classpath src test/supports_color/manual_background_check.cljc
npx --yes nbb -cp src test/supports_color/manual_background_check.cljc
clojure -M test/supports_color/manual_background_check.cljc
```

An optional integer argument sets `:timeout-ms`, for example `manual_background_check.cljc 3000`.

To check that the terminal settings are unchanged, wrap a run like this:

```sh
a=$(stty -g); bb --classpath src test/supports_color/manual_background_check.cljc; b=$(stty -g); [ "$a" = "$b" ] && echo same || echo DIFFERENT
```

Then press Enter at the empty prompt to confirm nothing was left on the command line.

## Results by terminal

Times are for the `supports-color.core/background` call only, not for starting the runtime.

The checks on 2026-11-10 ran with the library's default `:timeout-ms` of 100 ms.

Before the checks on 2026-11-11, the default was raised to 1000 ms. Over a slow link such as SSH, a terminal's answer can take longer than 100 ms to arrive, and an answer that arrives after the library stops reading shows up on screen. The slow-link checks below ran with 1000 ms and showed that it still wasn't enough over SSH, so the default was raised again, to 2000 ms, before the Kitty and tmux checks.

On a terminal that answers, the read ends as soon as the answer arrives, so the default doesn't change any of the times on this page except where a check waited out the full timeout.

### Terminal.app

Version 2.14. Tested 2026-11-10.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 40, 40, 40 | `:osc11` | 51.0 | yes | yes | yes |
| bb | light | light | 248, 248, 248 | `:osc11` | 47.3 | yes | yes | yes |
| nbb | dark | dark | 40, 40, 40 | `:osc11` | 38.5 | yes | yes | yes |
| nbb | light | light | 248, 248, 248 | `:osc11` | 40.3 | yes | yes | yes |

### iTerm2

Version 3.6.11. Tested 2026-11-10.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 15, 19, 20 | `:osc11` | 77.9 | yes | yes | yes |
| bb | light | light | 246, 247, 246 | `:osc11` | 69.8 | yes | yes | yes |
| nbb | dark | dark | 15, 19, 20 | `:osc11` | 70.9 | yes | yes | yes |
| nbb | light | light | 246, 247, 246 | `:osc11` | 62.0 | yes | yes | yes |

### VS Code integrated terminal

Version 1.142.0. Tested 2026-11-10. It answers the theme query as well as the color query.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 14, 20, 21 | `:csi996` | 54.7 | yes | yes | yes |
| bb | light | light | 240, 240, 240 | `:csi996` | 58.8 | yes | yes | yes |
| nbb | dark | dark | 14, 20, 21 | `:csi996` | 45.3 | yes | yes | yes |
| nbb | light | light | 240, 240, 240 | `:csi996` | 45.5 | yes | yes | yes |

### Alacritty

Version 0.14.0. Tested 2026-11-10, dark only, because Alacritty has no theme menu. The light theme was not tested.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 24, 24, 24 | `:osc11` | 49.8 | yes | yes | yes |
| nbb | dark | dark | 24, 24, 24 | `:osc11` | 39.3 | yes | yes | yes |

### Ghostty

Version 1.3.1. Tested 2026-11-10. The theme was switched through the macOS Appearance setting, which Ghostty follows.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 33, 37, 43 | `:csi996` | 50.3 | yes | yes | yes |
| bb | light | light | 247, 247, 247 | `:csi996` | 52.6 | yes | yes | yes |
| nbb | dark | dark | 33, 37, 43 | `:csi996` | 36.8 | yes | yes | yes |
| nbb | light | light | 247, 247, 247 | `:csi996` | 37.7 | yes | yes | yes |
| jvm | light | light | 247, 247, 247 | `:csi996` | 107.7 | yes | yes | yes |
| jolt | light | light | 247, 247, 247 | `:csi996` | 103.9 | yes | yes | yes |

The JVM and Jolt runs are slower because each helper process (`stty`, the terminal check) costs more to start from the JVM. The answer still arrived well inside the timeout.

### Kitty

Version 0.50.0, `TERM=xterm-kitty`. Tested 2026-11-11, dark (the default theme) and light (chosen with `kitty +kitten themes`).

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 0, 0, 0 | `:csi996` | 51.3 | yes | - | - |
| bb | light | light | 228, 229, 245 | `:csi996` | 57.0 | yes | yes | yes |
| nbb | dark | dark | 0, 0, 0 | `:csi996` | 42.3 | yes | - | - |
| jvm | dark | dark | 0, 0, 0 | `:csi996` | 102.6 | yes | - | - |

### WezTerm

Version 20240203-110809-5046fc22. Tested 2026-11-10, dark only. The light theme was not tested.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | dark | 0, 0, 0 | `:osc11` | 52.3 | yes | yes | yes |
| nbb | dark | dark | 0, 0, 0 | `:osc11` | 47.6 | yes | yes | yes |

### tmux

tmux 3.8 inside Ghostty 1.3.1, `TERM=tmux-256color`. Tested 2026-11-11, light only. tmux 3.8 answers both queries itself, with the outer terminal's background. Older tmux versions weren't tested and may answer neither.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | light | light | 247, 247, 247 | `:csi996` | 62.6 | yes | yes | yes |
| nbb | light | light | 247, 247, 247 | `:csi996` | 46.5 | yes | yes | yes |

### GNU screen

Version 4.00.03 (the copy at `/usr/bin/screen` on macOS), inside Terminal.app 2.14. `COLORFGBG` was not set. Tested 2026-11-10, dark only.

screen answers neither the theme nor the color query, so `nil` with `:none` is the expected result. The short times show that screen does answer the device-attributes query (`ESC[c`) the library sends last, so the read ended early instead of waiting out the timeout.

| Runtime | Theme | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | dark | nil | nil | `:none` | 49.3 | yes | yes | yes |
| nbb | dark | nil | nil | `:none` | 40.9 | yes | yes | yes |

## SSH

### Local link

Terminal.app 2.14 (dark) on the Mac, SSH into a Debian 13 virtual machine on the same Mac, so the link adds almost no delay. Tested 2026-11-10, with `:timeout-ms` 100 and 500.

| Runtime | Timeout | Polarity | Background | Method | ms | Screen | Prompt | Settings |
|---|---|---|---|---|---|---|---|---|
| bb | 100 | dark | 40, 40, 40 | `:osc11` | 55.3 | yes | yes | yes |
| bb | 500 | dark | 40, 40, 40 | `:osc11` | 28.3 | yes | yes | yes |
| nbb | 100 | dark | 40, 40, 40 | `:osc11` | 85.5 | yes | yes | yes |
| nbb | 500 | dark | 40, 40, 40 | `:osc11` | 59.5 | yes | yes | yes |

### Simulated slow link

Ghostty 1.3.1 on the Mac, SSH into the same Debian VM, then a second SSH hop to `localhost` inside it. Delay was added on the VM's loopback interface with `tc qdisc add dev lo root netem delay <ms>`. The delay applies in both directions, so 150 ms gives a 300 ms round trip, which `ping` confirmed. Tested 2026-11-11. Ghostty's theme followed the macOS Appearance setting, which changed between some runs.

| Round trip | Runtime | Timeout | Polarity | Method | ms | Result |
|---|---|---|---|---|---|---|
| none | bb | 1000 | light | `:csi996` | 60.5 | clean |
| 300 ms | bb | 1000 | light | `:csi996` | 761.0 | clean |
| 300 ms | bb | 1000 | dark | `:csi996` | 748.6 | clean |
| 300 ms | bb | 1000 | dark | `:csi996` | 659.2 | clean |
| 300 ms | bb | 100 | nil | `:none` | 123.3 | leaked (expected): `997;2n11;rgb:f7f7/f7f7/f7f762;22;52c` on the next command line |
| 300 ms | nbb | 1000 | light | `:csi996` | 709.9 | clean |
| 500 ms | bb | 1000 | nil | `:none` | 1029.2 | leaked (expected): `997;1n11;rgb:2121/2525/2b2b62;22;52c` on the next command line |

The same link, with the device-attributes query sent by hand from the shell and no library involved:

| Round trip | Time for the answer |
|---|---|
| 300 ms | 637 ms |

Over this SSH link an answer took about two network round trips, with or without the library. That's why the default `:timeout-ms` was raised to 2000 ms after these runs. It covers round trips up to about 900 ms. A timeout of 100 ms leaked at a 300 ms round trip, and 1000 ms leaked at 500 ms. The leaked text is the terminal's late answer, which arrives after the library has stopped reading.

## Miscellaneous situations

All in Terminal.app 2.14, dark, with bb, on 2026-11-10.

| Situation | How it was run | Polarity | Method | ms | Result |
|---|---|---|---|---|---|
| `TERM=dumb`, as in Emacs `M-x shell` | `TERM=dumb bb ...` | nil | `:none` | 23.3 | query skipped, as intended. Settings same, prompt clean |
| `TERM=dumb`, Node | `TERM=dumb npx nbb ...` | nil | `:none` | 15.6 | query skipped, as intended |
| Background job | `bb ... &` | nil | `:none` | 35.2 | query skipped. The job finished normally instead of being stopped. Prompt clean |
| Output piped to a pager | `bb ... \| less` | nil | `:none` | 38.9 | query skipped, because `less` already held the terminal |
| Output to a file | `bb ... > /tmp/out` | dark | `:osc11` | 48.0 | same as a normal run. The query went through stderr |
| Off switch | `SUPPORTS_COLOR_NO_QUERY=1 bb ...` | nil | `:none` | 22.8 | query skipped |

## Not manually tested

- terminals on Linux or Windows desktops: all checks ran on macOS (the automated tests do run on Debian, Ubuntu and Alpine)
- mosh
- tmux versions older than 3.8
- Contour
- an actually slow network link, as opposed to the simulated one above
