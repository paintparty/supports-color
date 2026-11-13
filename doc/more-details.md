# More details about how supports-color works

## Relation your program and terminal

**In ClojureScript, Node's builtins load at run time.** supports-color calls `process.getBuiltinModule` for `process`, `os`, `tty`, `child_process` and `fs`, and only when Node APIs exist, so a browser build never includes them. This needs Node 20.16+ or 22.3+. In a browser, nothing below applies: see [In browsers](api.md#in-browsers).

**`profile` never touches the terminal.** It reads environment variables, the program's command-line arguments, and whether stdout, stderr and stdin are terminals.

**`background` may talk to the terminal.** On macOS and Linux, when stdout or stderr is a terminal, it:

1. checks that the process is in the terminal's foreground process group,
2. saves the terminal settings (`stty -g`),
3. switches the terminal to non-canonical, no-echo mode (`stty -icanon -echo min 1 time 0`),
4. writes `ESC[?996n ESC]11;? ESC\ ESC[c` to `/dev/tty`,
5. reads until the reply to `ESC[c` arrives, or `:timeout-ms` passes (one more `:timeout-ms` if part of a reply has arrived),
6. restores the saved settings.

It sends one query per call. Ctrl-C and Ctrl-Z typed during the read still interrupt or suspend the program. On the JVM, Babashka and Jolt a shutdown hook restores the terminal if the process exits, or is ended by SIGTERM or SIGINT, mid-query. SIGKILL cannot be handled, so it can leave the terminal in non-canonical mode. `stty sane` fixes it.

## Subprocesses

| Runtime             | When                                                                             | Command                                                                                                                                                            | How the binary is found                                                                                   |
| ------------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------- |
| JVM, Babashka, Jolt | the first time a tty fact is needed, at most once per descriptor per process     | `sh -c 'test -t 0'` (or `1`, `2`), with the process's stdio inherited and an empty environment                                                                     | `/bin/sh`, `/usr/bin/sh`, or Termux's `sh`. If none exists, the descriptor is reported as not a terminal. |
| JVM, Babashka, Jolt | each `background` call that queries, where there is no `/proc` (macOS, the BSDs) | `sh -c 'exec ps -o stat= -p $$'` with an empty environment, to check the foreground process group. On Linux it reads `/proc/self/stat` instead and starts nothing. | `ps` from the fixed list below. If it is missing, the query runs as if in the foreground.                 |
| Node                | each `background` call that queries, where there is no `/proc`                   | `ps -o stat= -p <pid>`, empty environment. On Linux it reads `/proc/self/stat` instead.                                                                            | same                                                                                                      |
| all except Windows  | each `background` call that queries                                              | `stty -g`, `stty -icanon -echo min 1 time 0`, `stty <saved settings>`, with `/dev/tty` as stdin                                                                    | `stty` from the fixed list below. If it is missing, the query is skipped.                                 |
| Windows             | never                                                                            | none                                                                                                                                                               |                                                                                                           |

Both tools are found only at fixed absolute paths, tried in this order: `/bin/`, `/usr/bin/`, `/run/current-system/sw/bin/` (NixOS), `/run/current-system/profile/bin/` (Guix System), `/data/data/com.termux/files/usr/bin/` (Termux). Nothing is looked up on `PATH`, and no environment variable can change the path. On Node the query runs in the host process and `stty` gets an empty environment. On Jolt each `stty` call goes through `/bin/sh -c 'exec …'`, because that is how Jolt applies a file redirect.

On Node the call is synchronous and blocks the event loop until it returns.

## Environment variables it reads

- Detection: `NO_COLOR`, `FORCE_COLOR`, `CLICOLOR`, `CLICOLOR_FORCE`, `TERM`, `COLORTERM`, `TERM_PROGRAM`, `TERM_PROGRAM_VERSION`, `CLITHEME`, `COLORFGBG`, `CI`, `CI_NAME`, `GITHUB_ACTIONS`, `GITEA_ACTIONS`, `CIRCLECI`, `TRAVIS`, `APPVEYOR`, `GITLAB_CI`, `BUILDKITE`, `DRONE`, `TF_BUILD`, `AGENT_NAME`, `TEAMCITY_VERSION`.
- `SUPPORTS_COLOR_NO_QUERY`: any non-empty value makes `background` skip the terminal query.
- Read and returned in `:env`, but not used for any decision: `COLOR_BACKGROUND`, `COLOR_FOREGROUND`, `COLOR_THEME`, `COLOR_CONTRAST`.
- JVM system properties: `os.name`, `os.version`.

For `NO_COLOR`, `CI`, `CLICOLOR_FORCE`, `TERM_PROGRAM` and the CI provider flags, any non-empty value counts as set, and an empty value counts as unset. So `NO_COLOR=""` has no effect, and the other checks decide the level. [no-color.org](https://no-color.org) asks software to turn color off when NO_COLOR is "present and not an empty string", and this library reads that as "empty means unset". `FORCE_COLOR` follows [force-color.org](https://force-color.org) with two exceptions taken from chalk:

| `FORCE_COLOR`   | Level                                                         |
| --------------- | ------------------------------------------------------------- |
| unset or `""`   | no effect                                                     |
| `0`, `false`    | 0, color off (chalk, where force-color.org would force color) |
| `1`, `true`     | 1                                                             |
| `2`             | 2                                                             |
| `3`             | 3                                                             |
| any other value | 1 (force-color.org: any non-empty value forces color)         |

Command-line color flags override `NO_COLOR`, as no-color.org asks, so `NO_COLOR=1 tool --color=256` gives 256 colors. `NO_COLOR` overrides `FORCE_COLOR`, so `NO_COLOR=1 FORCE_COLOR=3 tool` gives no color. Neither spec says which of the two variables wins. This library treats `NO_COLOR` as the user's preference and `FORCE_COLOR` as something tooling often sets.

## Command-line flags it reads

Unless you pass `:sniff-flags false`, `profile` scans the program's arguments, up to a `--`, for `--color`, `--colors`, `--color=true|always|256|16m|full|truecolor|false|never`, `--no-color` and `--no-colors`. It does this even if your program uses those names for something else.

On the JVM the arguments come from `*command-line-args*`, which only `clojure.main` sets (`clj -M -m`, `bb`). In a `:gen-class` main, such as an uberjar, pass them in yourself, or flags like `--no-color` are ignored:

```clojure
(defn -main [& args]
  (sc/profile {:context {:runtime {:argv args}}}))
```

## Timeouts

- `:timeout-ms` bounds the read of the terminal's reply. Default 2000, clamped to 0 to 30000. A non-number or NaN falls back to 2000 and prints a warning to stderr. `nil` means the default, with no warning.
- The read stops when the reply to `ESC[c` arrives. Every terminal we checked answers it within a few milliseconds, so a long timeout costs nothing there. A terminal that answers nothing makes each `background` call wait the full `:timeout-ms`.
- If part of the reply has arrived when `:timeout-ms` runs out, the read waits one more `:timeout-ms` for the rest. A call can therefore take up to twice `:timeout-ms`.
- The setup steps have their own budget, so a tiny `:timeout-ms` never cuts them short. On the JVM, Babashka and Jolt, `stty -g`, the mode switch and `ps` (where it runs) each wait up to 2 s, and so does each `test -t` probe. On Node each step waits up to 2 s. On every runtime the restore waits up to 3 s.
- Over a slow link (SSH) a reply that arrives after the read has stopped shows up on screen and in the program's input. Over SSH an answer takes about two network round trips, so with the default that takes a round trip of more than about 900 ms (satellite links, for example). Raise `:timeout-ms`, or set `SUPPORTS_COLOR_NO_QUERY`. [Late replies over slow links](slow-links.md) explains this step by step.

## Disabling the `background` detection command

- As an end user: set `SUPPORTS_COLOR_NO_QUERY=1`. Polarity then comes only from `CLITHEME` and `COLORFGBG`, and `:background-color` is `nil`.
- As a program author: pass `{:context {:runtime {:tty {:stdout false :stderr false}}}}`, or call only `profile`.
- `CLITHEME=dark` or `light` fixes the polarity. `background` still runs the query to fill `:background-color`. `theme` sends no query.

## When the query runs

| Situation                                                                      | Query runs?                                                                                                  |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------ |
| Interactive terminal                                                           | yes                                                                                                          |
| `tool \| less`, `tool > out.txt` (stderr is still the terminal)                | yes, through stderr. If the pager already holds the terminal in raw mode, the query declines.                |
| `tool > out.txt 2>&1`, cron, systemd, most CI                                  | no                                                                                                           |
| `tool < input.txt`, output on a terminal                                       | yes                                                                                                          |
| `tool &` in a shell with job control                                           | no, the process is not in the foreground                                                                     |
| Two programs that use this library start a moment apart on one terminal        | the second one declines while the first holds the terminal                                                   |
| Echo is off, for example while another program reads a password (`stty -echo`) | no. The library treats any terminal that is not in normal line mode with echo on as held by another program. |
| `TERM` unset or `dumb` (Emacs `M-x shell`)                                     | no                                                                                                           |
| `SUPPORTS_COLOR_NO_QUERY` set                                                  | no                                                                                                           |
| Windows                                                                        | no                                                                                                           |

## Typeahead

Keys typed while the query reads, and keys typed before it that are still waiting to be read, are consumed and lost. Call `background` at startup, before you prompt the user. Ctrl-C and Ctrl-Z still work during the read.

## Programs that start during the query

Some programs save the terminal settings when they start and write them back when they exit. If one of them starts on the same terminal while the query holds it, it saves the query's no-echo mode, and when it exits it puts that mode back after this library has already restored the terminal. The terminal then stops echoing until you run `stty sane` or `reset`.

Full-screen programs such as editors and pagers can do this. Some Node builds do it for every Node program: it was seen with Ubuntu 26.04's `nodejs` 22.22.1, and not with Debian's Node 20.19, Alpine's Node 24.22 or Node 25.2 on macOS.

The library cannot prevent it, because the write comes from the other program. Both programs have to start on one terminal within the query window. That window usually lasts a few milliseconds. It lasts the full `:timeout-ms` when the terminal does not answer, and up to twice that when a reply stops part way. Two Node programs started together, such as `a | b`, are the likely case. Set `SUPPORTS_COLOR_NO_QUERY=1` to avoid it.

## What the result maps contain

Both functions return `:env` and `:runtime`. `:env` holds only the keys in `sc/env-var-keys`, even if you pass your whole environment as `:context :env`. `:runtime :argv` holds only the color flags that detection recognized, before any `--`, in the order given. Other arguments, such as `--token=...`, file paths, and on Node the `node` binary and script paths, are left out. Fed back as `:context`, a result gives the same level.

The values of the documented variables are still there, for example `TERM_PROGRAM` and `COLORFGBG`. They describe the terminal, not the user, but check them against your own logging rules.

## Malformed `:context`

A malformed piece of `:context` is ignored, and that fact is read from the live process instead. For example, an `:env` that is not a map is ignored, as are a `:tty` value that is not `true` or `false` and an `:argv` that is not a sequence. On Node, `process.env` is accepted as `:env`.

## Debug output

`:debug true` writes the trace to stdout, not stderr, so it mixes with your program's output. Control characters in values are escaped. The trace prints only the documented keys of `:context :env`.

## Internal vars

Vars marked `^:no-doc` in `supports-color.common`, `register-platform!` among them, are internal and can change in any release. `register-platform!` replaces the platform adapter for the whole process. The tests use it to fake a platform.
