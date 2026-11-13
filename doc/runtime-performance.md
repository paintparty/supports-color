# Runtime performance and caching

This doc lists what each supports-color call costs in processes and blocking time, and shows how a consuming library, such as an ANSI SGR styling library, should cache the results. supports-color caches nothing itself, so every call does its work again.

The recommended integration is to avoid detection at namespace load. Detect lazily, on the first formatting request, or during explicit application initialization. Call `theme` or `background` only if the consumer needs the background. The consumer should expose a manual refresh or override interface.

```text
                 CONSUMER RUNTIME PERFORMANCE

 Namespace load
      |
      +--> create state atom or delay only
           no environment scan
           no processes
           no terminal mutation

 First formatting request / explicit init!
      |
      +--> Need color profile?
      |       |
      |       +-- cached --> atom deref + map lookup
      |       |
      |       `-- missing --> profile
      |                      env vars, argv, TTY checks
      |                      JVM, Babashka, Jolt: up to 3 `test -t` processes,
      |                        once per descriptor per process
      |                      Node: 0 processes
      |
      `--> Need dark or light?
              |
              +-- no --> finished
              |
              `-- yes --> cached?
                           |
                           +-- yes --> atom lookup
                           |
                           `-- no --> theme
                                      |
                                      +-- CLITHEME dark or light
                                      |      0 processes, no query
                                      |
                                      `-- one combined terminal query
                                             (CSI ?996, OSC 11, DA1)
                                             3 stty processes
                                             + 1 ps where there is no /proc
                                             ends at the DA1 reply,
                                             or at :timeout-ms
```

## Relative cost

| Operation | Process launches | Blocking time |
|---|---:|---|
| Cached atom lookup | 0 | Negligible |
| `profile` on Node | 0 | Negligible |
| `profile` on the JVM, Babashka, Jolt | Up to 3, the first time only | Process startup |
| `theme` with `CLITHEME` dark or light | 0 | Negligible |
| `theme` or `background`, terminal answers | 3, or 4 without `/proc` | Process startup plus one terminal round trip |
| `theme` or `background`, terminal answers nothing | 3, or 4 without `/proc` | Process startup plus `:timeout-ms` |
| `theme` or `background`, query skipped | 0 | Negligible |

`background` always sends the query when it can, because it reads `:background-color` from the reply, even when `CLITHEME` decides the polarity. Use `theme` when you need only dark or light.

The query is skipped on Windows, when neither stdout nor stderr is a terminal, when `TERM` is unset or `dumb`, and when `SUPPORTS_COLOR_NO_QUERY` is set. [More details about how supports-color works](more-details.md) lists every case.

The default `:timeout-ms` is 2000. The read ends as soon as the DA1 reply arrives, which takes a few milliseconds on a local terminal, so the full timeout only passes on a terminal that answers nothing. If part of a reply has arrived when the timeout runs out, the read waits one more `:timeout-ms`. The `stty` and `ps` steps have their own budgets of up to 2 s each, and the restore up to 3 s, so a call always ends.

Each query launches these processes:

```text
ps -o stat= -p <pid>               foreground check, only where there is no /proc
stty -g                            save settings
stty -icanon -echo min 1 time 0    enter query mode
stty <saved settings>              restore settings
```

This work should not happen per styled string, log event, or render frame.

## Ownership

supports-color and the consumer split the work like this:

```text
supports-color
    owns discovery and terminal interaction
             |
             | returns an immutable map
             v
consumer (an ANSI SGR library, for example)
    owns caching, overrides, and formatting policy
```

A reasonable consumer-side state shape would be:

```clojure
(defonce capabilities
  (atom {:profiles  {}     ; keyed by :stdout / :stderr
         :theme     nil    ; result of theme or background
         :overrides {}}))
```

Profile and theme state should remain separate:

```text
:profiles
  Stable for most of the process lifetime.
  Detect once per output stream.

:theme
  Can change when the user switches terminal themes.
  Detect only when theme-aware styling is requested.
```

The consumer's interface could provide:

```clojure
(capabilities)              ; cheap cached read
(ensure-capabilities!)      ; lazily detect missing values
(refresh-capabilities!)     ; explicitly rerun detection
(set-capabilities! value)   ; manual/application override
(clear-capabilities!)       ; mainly useful for REPLs and tests
```

Detection should not run inside an atom `swap!` function, because `swap!` may retry and repeat the terminal query. Run it once, under a lock or in a `delay`, and then update the atom.

## Why not detect at library load?

Namespace-load detection creates several undesirable behaviors:

- Requiring the library may unexpectedly launch processes and change the settings of `/dev/tty`.
- It adds startup latency even when the caller never emits ANSI.
- It also happens during tests, AOT compilation, build tooling, and REPL reloads.
- The caller may not have configured overrides yet.
- The eventual output destination might not be known yet.
- A TUI may already hold the terminal in raw mode.
- Keys the user types during the query are consumed.

A `defonce` atom or a `delay` at namespace load is fine. Filling it should be lazy or explicit.

## Practical policies

For an ordinary CLI:

```text
Application startup
    |
    +-- profile once
    |
    `-- theme once, only if adaptive colors need it
```

For a long-running TUI:

```text
Before the input loop starts
    |
    `-- initial detection

During rendering
    |
    `-- cached reads only

Theme changed / user requests refresh
    |
    `-- serialized theme refresh
```

Which call to cache:

- Only deciding whether to emit ANSI, and at which level: call only `profile`.
- Needs dark or light: cache the result of `theme`.
- Needs the actual background RGB: cache the result of `background`.

Manual refresh is enough by default. Automatic polling would repeatedly launch processes, change the terminal mode, and consume typed input.

In short: one cached `profile` is cheap, one `theme` or `background` query is fine during controlled initialization, and repeated queries have real runtime and terminal-safety costs.
