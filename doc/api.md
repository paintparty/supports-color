# API

### `supports-color.core/profile`

Reads the stdout stream, by default.

<br>

**Example**: Resolving the color level, which is `0`, `1`, `2`, or `3`:

```clojure
(let [{:keys [level]} (sc/profile)]
  (when (pos? level)
    (println "Terminal stdout supports color"))
  (when (>= level 2)
    (println "Terminal stdout supports 256 colors"))
  (when (= level 3)
    (println "Terminal stdout supports 16 million colors (truecolor)")))
```

<br>

**Example**: Getting a named color profile, which corresponds to `:color-level`:<br>

```clojure
;; 0 -> :no-color
;; 1 -> :basic
;; 2 -> :ansi256
;; 3 -> :truecolor

(let [{:keys [color-profile]} (sc/profile)]
  (case color-profile
    :truecolor
    (println "Terminal stdout supports 16 million colors (truecolor)")
    :ansi256
    (println "Terminal stdout supports 256 colors")
    :basic
    (println "Terminal stdout supports 16 colors")
    (println "Terminal stdout does not support color")))
```

<br>

To instead read the stderr stream (instead of default stdout), use the `:stream` option:

```
(sc/profile {:stream :stderr})
```

<br />

### `supports-color.core/background`

`supports-color.core/background` returns a map with `:polarity`, `:background-color`, and a [bunch of other diagnostics](doc/full-output-reference.md).

`:polarity` is `"dark"`, `"light"`, or `nil` when no signal was available (an
undetected background stays distinct from a detected one).

`:background color`, if detected, is returned as hash-map representing an rgb color, e.g. `{:r 21 :g 42 :b 69}`.

```clojure
(let [{:keys [polarity background-color]} (sc/background)]
 (println "Terminal stdout polarity is " polarity)
 (println "Terminal stdout background rgb is " background-color))
```

`background` queries the terminal: it switches it to non-canonical, no-echo mode, writes one escape-sequence query to `/dev/tty`, and reads the reply. It does this whenever stdout or stderr is a terminal, so `cli | less` still queries, through stderr. It skips the query when neither is a terminal (CI, daemons, `cli > log 2>&1`), and in the other cases listed under [More details about how supports-color works](doc/more-details.md). It may block up to `:timeout-ms` (see the [Options section](###Options) below).

The background query is slightly more expensive, hence a dedicated public
function. If you need both in a single map, you can just merge them:

```clojure
(merge (sc/profile) (sc/background))
=> 
{:level            3 
 :color-profile    :ansi256 
 :polarity         "dark"
 :background-color {:r 33 :g 22 :b 12}
 ...}
```

<br />

### `supports-color.core/theme`

`supports-color.core/theme` returns the same map as `background`, without `:background-color`. Use it when you need the polarity and not the color.

It runs the same chain as `background`, with one difference: when `CLITHEME` is `dark` or `light`, it sends no terminal query.

```clojure
(:polarity (sc/theme))
=> "dark"
```

<br />

In JavaScript runtimes without Node APIs, such as Cloudflare Workers without `nodejs_compat`, `profile` returns level 0 and `:runtime :host :js`, and the polarity is `nil`.


### In browsers

In a browser there is no environment, terminal, or command line to read.

- `profile` returns level 3 (`:truecolor`), because browser dev consoles render truecolor, and `:runtime :host :browser`, so a styling library knows to convert to the `%c`-based CSS syntax.
- `theme` and `background` get the polarity from `prefers-color-scheme`, with `:detection-method :prefers-color-scheme`. That is the operating system's or browser's preference. A page cannot read the DevTools theme setting, which follows the system by default. `:background-color` is always `nil`.
- Nothing queries a terminal.

<br />

### Options

`supports-color/profile`, `supports-color/background`, and `supports-color/theme` take an (optional) options map:

- `:debug`<br>
  When `true`, traces the detection chain to stdout as it runs.
- `:timeout-ms`<br>
  How long to wait for the terminal's reply to the query (default `2000`, clamped to `0-30000`). The read stops as soon as the reply arrives, usually within a few milliseconds, so the full wait only happens on a terminal that does not answer.
- `:stream`<br>
  Defaults to `:stdout`. Set to `:stderr` for stderr. For `profile` (which stream color is destined for).
- `:sniff-flags`<br>
  Whether to check CLI args like `--color`/`--no-color` (default true).

This options map takes a `:context` entry, a map that overrides what detection reads from the running process. Two keys are consulted:

- `:env`<br>
  A map of env-var overrides (keys are the strings in `sc/env-var-keys`), used instead of the real environment.
- `:runtime`<br>
  Runtime overrides: `:tty {:stdout :stderr :stdin}`, `:argv`, and `:os {:name :version :platform}`, used instead of live TTY / argv / OS reads.

Anything absent from the context falls back to the running process, so a bare `{:env {...}}` overrides only environment variables and leaves TTY, argv, and OS live. For a fully deterministic result, provide a complete `:env` map (every key in `sc/env-var-keys`) so no real variables leak through.

For example, you could override an environment variable:

```clojure
(sc/profile {:context {:env {"NO_COLOR" "1"}}})
=>
{:level         0 
 :color-profile :no-color 
 ...}
```

Or pin runtime facts, e.g. treat stdout as a TTY without a real terminal:

```clojure
(sc/profile {:sniff-flags false
             :context {:env {"TERM" "xterm-256color"}
                       :runtime {:tty {:stdout true}}}})
=>
{:level         2 
 :color-profile :ansi256 
 ...}
```

<br />

### `supports-color.core/get-env`

Reads a single environment variable by its string key. Consults a mock-env map
first when one is supplied, otherwise reads the real process environment. Used
internally for detection, and exposed so you can read the same variables the same way.

```clojure
(sc/get-env "NO_COLOR")
;; => nil, or the variable's value as a string

;; With a mock-env map, the map wins over the real environment:
(sc/get-env "TERM" {"TERM" "xterm-256color"})
;; => "xterm-256color"
```

<br />

### `supports-color.core/env-var-keys`

A vector of every environment-variable key (as strings) that detection consults,
including `"NO_COLOR"`, `"FORCE_COLOR"`, `"TERM"`, `"COLORTERM"`, the CI vars, and
the rest. Use it to build a complete `:env` override map for a fully deterministic
result (see the context map above). Or use it just to see what gets read.

```clojure
sc/env-var-keys
;; => ["APPVEYOR" "BUILDKITE" "CI" "CI_NAME" "CIRCLECI" "DRONE" ...]
```
