# supports-color glossary

Terms used in the supports-color README and docs, explained for someone new to terminals and color detection. Terms are grouped by topic. Links point to the specs and references that define them.

- [Terminals and processes](#terminals-and-processes)
- [Terminal settings](#terminal-settings)
- [Escape sequences and queries](#escape-sequences-and-queries)
- [Color](#color)
- [Environment variables and flags](#environment-variables-and-flags)
- [Specific to the supports-color library](#specific-to-the-supports-color-library)
- [Clojure runtimes](#clojure-runtimes)

<br>
<br>

## Terminals and processes

### Terminal emulator

The app that shows a text window you type commands into, such as Terminal.app, iTerm2, Ghostty, Kitty, Alacritty, WezTerm, or the terminal panel in VS Code. It draws the text and colors that programs send it, and it sends your keystrokes to them. Each terminal emulator has its own color theme, so it's the only thing that knows the actual background color. See [Terminal emulator](https://en.wikipedia.org/wiki/Terminal_emulator).

### Terminal, TTY

The connection between a terminal emulator and the programs running in it. "TTY" comes from "teletypewriter", the printing terminals early Unix systems used. A program can ask whether one of its standard streams is a terminal, and supports-color does this to decide whether color makes sense at all.

### Pseudo-terminal, pty

The kind of terminal a terminal emulator creates: a pair of connected endpoints in the operating system. The emulator holds one end, and the shell and the programs started from it hold the other. The supports-color tests create their own ptys to act as a fake terminal. See [Pseudoterminal](https://en.wikipedia.org/wiki/Pseudoterminal).

### `/dev/tty`

A special file that always refers to the process's own terminal, even when its standard streams are redirected to files or pipes. supports-color writes its queries to `/dev/tty` and reads the replies from it.

### Controlling terminal

The terminal a process belongs to. A program started from a shell in a terminal window has that window as its controlling terminal. Programs started by cron, systemd, or a CI service usually have none, and supports-color then never queries.

### Standard streams: stdin, stdout, stderr

Every program starts with three streams:

- **stdin**, standard input, where it reads input (usually your keyboard)
- **stdout**, standard output, where its normal output goes
- **stderr**, standard error, where its error and status messages go

Each one can be the terminal, or be redirected to a file or a pipe, independently of the others. See [Standard streams](https://en.wikipedia.org/wiki/Standard_streams).

### Redirect, pipe

Sending a stream somewhere other than the terminal. `tool > out.txt` redirects stdout to a file. `tool | less` pipes stdout into another program. `tool > log 2>&1` sends both stdout and stderr to a file. In the first two cases stderr is still the terminal, so supports-color can still query through it.

### Foreground process group, background job

A terminal belongs to one group of processes at a time, the foreground. Running `tool &` in a shell starts a background job instead. A background job that tries to change the terminal's settings is stopped by the operating system until you bring it back with `fg`. That's why supports-color checks that it's in the foreground before querying. See [Job control](https://en.wikipedia.org/wiki/Job_control_(Unix)).

### SSH

Secure Shell, a way to run a shell on another computer over the network. The programs run on the remote computer, but the terminal emulator showing them is still on yours. So a program on a server that wants to know the background color has to ask your terminal emulator across the network. See [Secure Shell](https://en.wikipedia.org/wiki/Secure_Shell) and [Late replies over slow links](slow-links.md).

### Round trip

The time for a message to travel to the other end and for an answer to come back. Over SSH, a query and its reply have to cross the network. In our measurements, one exchange took about two network round trips.

### Terminal multiplexer: tmux, GNU screen

A program that runs inside a terminal emulator and splits it into several sessions, windows or panes, which keep running after you disconnect. It sits between your programs and the terminal emulator, and answers some queries itself. See [tmux](https://github.com/tmux/tmux) and [GNU screen](https://www.gnu.org/software/screen/).

<br>
<br>

## Terminal settings

### Terminal settings, termios

The operating system keeps a set of settings for each terminal: whether typed characters are shown, whether input is delivered a line at a time, what Ctrl-C does, and so on. On Unix this structure is called termios. See [termios(3)](https://man7.org/linux/man-pages/man3/termios.3.html).

### `stty`

The standard Unix command for reading and changing terminal settings. `stty -g` prints the current settings as one line of text, which can be passed back to `stty` later to restore them exactly. supports-color uses `stty` to switch the terminal into the mode it needs, and to restore it afterward. See [stty(1)](https://man7.org/linux/man-pages/man1/stty.1.html).

### Canonical mode, non-canonical mode

In canonical mode, the normal mode for a shell prompt, input is delivered a whole line at a time, after you press Enter. In non-canonical mode, each byte is delivered as soon as it arrives. A terminal's reply to a query doesn't end with Enter, so supports-color switches to non-canonical mode to read it.

### Echo

The terminal setting that shows the characters you type. supports-color turns echo off while it reads a reply, so the reply isn't printed on screen, then turns it back on.

### Raw mode

A loose name for non-canonical mode with echo off, often with other processing switched off too. supports-color uses a lighter version: non-canonical, no echo, but Ctrl-C and Ctrl-Z still work.

### Typeahead

Keys you type before a program is ready to read them. They wait in the terminal's input queue. Keys typed while supports-color is reading a reply end up in the same queue, and are consumed along with the reply.

### `TERM`

An environment variable naming the terminal type, such as `xterm-256color`, `xterm-kitty` or `tmux-256color`. Programs use it to look up what the terminal supports in the terminfo database. See [terminfo(5)](https://man7.org/linux/man-pages/man5/terminfo.5.html).

### `TERM=dumb`

A terminal type that supports no escape sequences at all. Emacs sets it for its `M-x shell` buffer, for example. supports-color never queries a dumb terminal.

<br>
<br>

## Escape sequences and queries

### Escape sequence

A run of bytes starting with the ESC character (byte 27, often written `ESC` or `\e`) that a terminal treats as an instruction instead of text to show: change the color, move the cursor, answer a question. See [ANSI escape code](https://en.wikipedia.org/wiki/ANSI_escape_code).

### ECMA-48

The standard that defines the structure of escape sequences, including CSI, OSC and ST below. It's the same standard as ISO/IEC 6429 and the older ANSI X3.64, which is why people call these "ANSI escape codes". See [ECMA-48](https://ecma-international.org/publications-and-standards/standards/ecma-48/).

### xterm control sequences

The reference for the escape sequences supported by xterm, which most modern terminal emulators follow. It defines `OSC 11` and lists DA1. See [XTerm Control Sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html).

### CSI

Control Sequence Introducer, written `ESC [`. It starts most escape sequences, such as color changes and cursor movements, and some queries.

### OSC

Operating System Command, written `ESC ]`. It starts sequences that carry a text argument, such as setting the window title or asking for a color. It ends with ST.

### ST

String Terminator, written `ESC \`. It ends an OSC sequence. Many terminals also accept the BEL character (byte 7) instead.

### Query, reply

Some escape sequences ask the terminal a question. The terminal answers by sending bytes back, as if they had been typed. supports-color sends queries and reads the replies. A terminal ignores queries it doesn't support and sends nothing back for them.

### `OSC 11`

The query for the terminal's background color, written in full as `OSC 11 ; ? ST`. A terminal that supports it replies with the color, for example `OSC 11 ; rgb:1e1e/1e1e/2e2e ST`. Each of the three parts is 1 to 4 hex digits for red, green and blue. Defined in the xterm control sequences.

### `CSI ? 996 n`

The query for the terminal's color theme, dark or light. The reply is `CSI ? 997 ; 1 n` for dark or `CSI ? 997 ; 2 n` for light. It's newer than `OSC 11` and fewer terminals support it. Of those checked for supports-color, Ghostty, Kitty, the VS Code terminal and tmux 3.8 answer it. Defined in Contour's [dark and light mode detection spec](https://contour-terminal.org/vt-extensions/color-palette-update-notifications/).

### DA1, `ESC[c`

Primary Device Attributes, the query `CSI c`, which asks the terminal what kind of terminal it is. Nearly every terminal answers it, for example with `CSI ? 62 ; 22 c`. supports-color sends it last, after the other two queries. Terminals answer in order, so once the DA1 reply arrives, every other reply has arrived too, and the read can stop. See [DA1 in the VT510 manual](https://vt100.net/docs/vt510-rm/DA1.html).

### Timeout

How long supports-color waits for replies before giving up, set with the `:timeout-ms` option. A reply that arrives after the read has stopped is shown on screen and read as typed input instead. See [Late replies over slow links](slow-links.md).

<br>
<br>

## Color

### Color level, color profile

How many colors a terminal can show. supports-color reports it both as a number (`:level`) and a keyword (`:color-profile`):

| Level | Profile | Colors |
|---|---|---|
| 0 | `:no-color` | none, plain text only |
| 1 | `:basic` | 16 colors |
| 2 | `:ansi256` | 256 colors |
| 3 | `:truecolor` | about 16 million (24-bit RGB) |

See [ANSI escape code: colors](https://en.wikipedia.org/wiki/ANSI_escape_code#Colors).

### Truecolor, 24-bit color

Colors given as exact red, green and blue values from 0 to 255, about 16 million in all. Terminals that support it often set `COLORTERM=truecolor`. See [termstandard/colors](https://github.com/termstandard/colors).

### Polarity

Whether the terminal's background is dark or light. supports-color reports it as `"dark"`, `"light"`, or `nil` when it couldn't tell. Programs use it to pick readable colors, for example bright yellow on dark and dark amber on light.

### Luma

A number for how bright a color looks to the eye, from 0.0 (black) to 1.0 (white). Green counts most, red next, and blue least. supports-color computes it from the `OSC 11` background color with the standard BT.601 weights, and calls the background dark at 0.5 or below. See [Luma](https://en.wikipedia.org/wiki/Luma_(video)).

<br>
<br>

## Environment variables and flags

### Environment variable

A named text value that a program inherits from the process that started it, usually your shell. You set one for a single command with `NAME=value command`, or for the session with `export NAME=value`. Many color conventions are environment variables.

### `NO_COLOR`

A convention for turning color off. When `NO_COLOR` is set to any non-empty value, programs shouldn't add color to their output. A command-line flag like `--color` still overrides it. See [no-color.org](https://no-color.org/).

### `FORCE_COLOR`

A convention for turning color on even when output isn't a terminal, for example in CI logs. Any non-empty value forces color. supports-color also follows chalk's extra values: `0` and `false` turn color off, and `1`, `2` and `3` pick the color level. See [force-color.org](https://force-color.org/).

### `CLICOLOR`, `CLICOLOR_FORCE`

An older pair of conventions, used by many BSD and macOS tools. `CLICOLOR=0` turns color off. `CLICOLOR_FORCE` set to anything other than `0` turns color on even when output isn't a terminal. See [bixense.com/clicolors](https://bixense.com/clicolors/).

### `COLORTERM`

Set by many terminals to say they support more colors. `truecolor` or `24bit` means 24-bit color. See [termstandard/colors](https://github.com/termstandard/colors).

### `TERM_PROGRAM`, `TERM_PROGRAM_VERSION`

Set by some terminal emulators to name themselves and their version, for example `iTerm.app` and `3.6.11`, or `vscode`.

### `CLITHEME`

A proposed convention for telling command-line tools whether you want a dark or light style: `dark`, `light` or `auto`, optionally followed by modifiers such as `dark:high-contrast`. In supports-color, it decides the polarity before any query is made. See the [CLITHEME proposal](https://wiki.tau.garden/cli-theme/).

### `COLORFGBG`

An older hint from the rxvt terminal, also set by Konsole and a few others. It gives the foreground and background as palette numbers separated by `;`, for example `15;0`. supports-color reads the last number: below 7 means a dark background. It's the last fallback, used only when nothing else answered. See this [Neovim discussion](https://github.com/neovim/neovim/issues/2764).

### `CI`

Set by continuous integration services, such as GitHub Actions, GitLab CI and CircleCI, when they run your build. Their logs often show color even though output isn't a terminal, so supports-color has rules for them.

### `SUPPORTS_COLOR_NO_QUERY`

supports-color's off switch for terminal queries. Any non-empty value makes `supports-color.core/background` skip the queries entirely. Polarity then comes only from `CLITHEME` and `COLORFGBG`.

### Color flags: `--color`, `--no-color`

Command-line flags that many tools accept to turn color on or off for one run, such as `--color=256` or `--no-color`. `supports-color.core/profile` looks for them in the program's arguments, unless you pass `:sniff-flags false`.

<br>
<br>

## Specific to the supports-color library

### `supports-color.core/profile`

The function that returns the color level, from environment variables, flags, and whether the output is a terminal. It never touches the terminal itself.

### `supports-color.core/background`

The function that returns the polarity and the background color. It may query the terminal, as described under [Query, reply](#query-reply).

### `:detection-method`

Which source decided the polarity in a `supports-color.core/background` result: `:clitheme`, `:csi996`, `:osc11`, `:colorfgbg`, or `:none`.

### `:context`

An option for both functions that overrides what they read from the running process: environment variables (`:env`) and runtime facts such as whether each stream is a terminal (`:runtime`). It's used for testing, and for programs that already know these facts.

### `env-var-keys`

`supports-color.core/env-var-keys` is the list of every environment variable supports-color reads. Use it to build a complete `:env` override, or to see what's read.

### Sniffing

Looking at a program's command-line arguments for color flags. The `:sniff-flags` option turns it on or off.

<br>
<br>

## Clojure runtimes

supports-color runs on four Clojure runtimes:

### Clojure, JVM

Clojure running on the Java Virtual Machine, started with the `clojure` command. See [clojure.org](https://clojure.org/).

### ClojureScript

Clojure compiled to JavaScript, which runs on Node.js among others. See [clojurescript.org](https://clojurescript.org/).

### Babashka

A fast-starting Clojure interpreter for scripts, started with `bb`. See [babashka.org](https://babashka.org/).

### nbb

A ClojureScript interpreter for Node.js scripts, from the Babashka project. See [nbb](https://github.com/babashka/nbb).

### Jolt

Another Clojure runtime that supports-color is tested on. See [Jolt](https://github.com/jolt-lang/jolt).
