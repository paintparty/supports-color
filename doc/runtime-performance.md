# Runtime Performance and Caching

The recommended integration is to avoid detection at namespace load. Cache
color support lazily or during explicit application initialization, and query
the background only if the consuming ANSI SGR library actually needs it. The
consumer should expose a manual refresh or override interface.

```text
                 ANSI-SGR RUNTIME PERFORMANCE

 Namespace load
      |
      +--> create state atom only
           no environment scan
           no processes
           no terminal mutation

 First formatting request / explicit init!
      |
      +--> Need color profile?
      |       |
      |       +-- cached --> atom deref + map lookup
      |       |             effectively negligible
      |       |
      |       `-- missing --> profile
      |                      environment + argv + TTY checks
      |                      0 child processes
      |                      normally very cheap
      |
      `--> Need background awareness?
              |
              +-- no --> finished
              |
              `-- yes --> cached?
                           |
                           +-- yes --> atom lookup
                           |
                           `-- no --> background chain
                                      |
                                      +-- CLITHEME set
                                      |      0 child processes
                                      |
                                      `-- CSI ?996 query
                                             3 x stty processes
                                             terminal round trip
                                             up to timeout
                                                  |
                                                  +-- success --> finished
                                                  |
                                                  `-- unsupported
                                                        |
                                                        `-- OSC 11 query
                                                            3 more stty processes
                                                            terminal round trip
                                                            up to timeout
```

## Relative Cost

| Operation | Process launches | Intended blocking time |
|---|---:|---:|
| Cached atom lookup | 0 | Negligible |
| `profile` | 0 | Normally very cheap; not benchmarked |
| Background via `CLITHEME` | 0 | Negligible |
| CSI succeeds | 3 | Process startup plus terminal response |
| `query-background-color` via OSC | 3 | Process startup plus up to `timeout-ms` |
| CSI misses and OSC follows | 6 | Process startup plus up to `2 * timeout-ms` |

With the default 100 ms timeout, the intended worst path is approximately:

```text
6 process startups + 100 ms CSI wait + 100 ms OSC wait
```

The implementation does not currently provide a hard upper bound because the
`stty` calls use unbounded `waitFor` operations.

Each terminal query launches three processes:

```text
stty -g              save settings
stty raw -echo       enter query mode
stty <saved-state>   restore settings
```

This work should not happen per styled string, log event, or render frame.

## Ownership Seam

The clean ownership seam is:

```text
supports-color module
    owns discovery and terminal interaction
             |
             | returns immutable capability snapshot
             v
ansi-sgr module
    owns caching, overrides, and formatting policy
```

A reasonable consumer-side state shape would be:

```clojure
(defonce capabilities
  (atom {:profiles   {}       ; keyed by :stdout / :stderr
         :background nil      ; detected background snapshot
         :overrides  {}}))
```

Profile and background state should remain separate:

```text
:profiles
  Stable for most of the process lifetime.
  Detect once per output stream.

:background
  Can change when the user switches terminal themes.
  Detect only when background-aware styling is requested.
```

The ANSI SGR interface could conceptually provide:

```clojure
(capabilities)              ; cheap cached read
(ensure-capabilities!)      ; lazily detect missing values
(refresh-capabilities!)     ; explicitly rerun detection
(set-capabilities! value)   ; manual/application override
(clear-capabilities!)       ; mainly useful for REPLs and tests
```

Detection should not run inside an atom `swap!` function because `swap!` may
retry and repeat the terminal side effect. Serialize detection with a lock, run
it once, and then update the atom.

## Why Not Detect at Library Load?

Namespace-load detection creates several undesirable behaviors:

- Requiring the ANSI SGR library may unexpectedly launch processes and mutate
  `/dev/tty`.
- It adds startup latency even when the caller never emits ANSI.
- It can also happen during tests, AOT compilation, build tooling, and REPL
  reloads.
- The caller may not have configured overrides yet.
- The eventual output destination might not be known yet.
- A TUI may already own raw terminal mode, creating interference.

A `defonce` atom at namespace load is fine. Filling it should be lazy or
explicit.

## Practical Policies

For an ordinary CLI:

```text
Application startup
    |
    +-- profile once
    |
    `-- query background once only if adaptive colors need it
```

For a long-running TUI:

```text
Before input loop starts
    |
    `-- initial detection

During rendering
    |
    `-- cached reads only

Theme changed / user requests refresh
    |
    `-- serialized background refresh
```

For a logging or formatting library that only needs to decide whether ANSI is
supported, call only `profile`. Do not call the combined `detect`,
because `detect` always enters the background chain.

If the consumer needs only the background mood (dark versus light), cache the
result of `background`. If it needs the actual RGB value, use
`query-background-color`. Manual refresh is sufficient by default; automatic
polling would repeatedly launch processes, manipulate terminal mode, and
potentially consume input.

In short: one cached profile detection is cheap; one background query is
tolerable during controlled initialization; repeated background detection has
material runtime and terminal-safety costs.
