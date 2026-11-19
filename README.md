<sub>Thanks to <a target="_blank" href="https://www.clojuriststogether.org/">Clojurists Together</a> for their generous support! An early phase of work on this lib (within Fireworks) was completed during the [2025 Q3 funding cycle](https://www.clojuriststogether.org/news/q3-2025-funding-announcement/#fireworks-jeremiah-coyle).</sub> 

<br>
<br>

# supports-color

**Terminal color capability and background theme detection for Clojure dialects**<br>
<sub>Works with Clojure, Babashka, ClojureScript, nbb, and Jolt</sub>

<br/>
<br/>

The **supports-color** API supplies 3 data points:

- **Color level support**: none, basic (16 colors), 256 colors, or truecolor
- **Background polarity**: dark, light, or unknown
- **Background color:**  `[r, g, b]`

```clojure
(require '[supports-color.core :as sc])


(sc/profile)
=> 
{:level         3 
 :color-profile :truecolor}


(sc/background)
=> 
{:polarity         "dark"
 :background-color {:r 33 :g 22 :b 12}
 ...}


;; Polarity only.
(sc/theme)
=> 
{:polarity "dark"
 ...}
```

<br>

For an orientation to terminology used in this readme, see the [supports-color glossary](doc/supports-color-glossary.md).


<br>

## Development Status

| Period           | Stage        | Release                                                                   |
| ---------------- | ------------ | ------------------------------------------------------------------------- |
| 2025 Oct         | Experimental | (Internal [Fireworks](https://github.com/paintparty/fireworks) namespace) |
| 2026 Nov-present | Alpha        | 0.1.1                                                                     |

<br />

## Quick comparison
Comparison with other libs from other languages that you may be familiar with:
|  | This library | [chalk/supports-color↗](https://github.com/chalk/supports-color) | [colorprofile↗](https://github.com/charmbracelet/colorprofile) |
|---|---|---|---|
| Language | Clojure dialects | JavaScript, Node | Go |
| Color level detection (16, 256, truecolor) | ✓ | ✓ | ✓ |
| Terminal background color query via `OSC 11` | ✓ | ✕ | ✕ |
| Terminal theme query via `CSI ? 996 n`| ✓ | ✕ | ✕ |
| Respects `NO_COLOR` | ✓ | ✕ | ✓ |
| Works in a web browser | ✓ | ✕ | ✕ |

View the [full comparison matrix](doc/related-libraries.md#full-comparison-matrix), which shows more features and more libraries.

<br />

## Requirements

**Clojure 1.10 or newer**.

Also runs on Babashka, Jolt, and ClojureScript.

**ClojureScript on Node: Node 20.16+ or 22.3+.** 
**supports-color** loads Node's builtins at run time with `process.getBuiltinModule`, so a browser build never includes them. Verified with nbb and with shadow-cljs `:simple` and `:advanced` builds.

**ClojureScript in browsers:**
`profile` returns level 3, because browser dev consoles render truecolor, with `:runtime :host :browser`. `theme` and `background` read `prefers-color-scheme`. Verified with an `:advanced` build, `bb test:browser`.

<br />

## Setup

Add as a dependency to your project:

Deps:

```clojure
io.github.paintparty/supports-color {:mvn/version "0.1.1"}
```

Leiningen:

```clojure
[io.github.paintparty/supports-color "0.1.1"]
```

Require it:

```Clojure
(require '[supports-color.core :as sc])
```

Or import into your namespace:

```Clojure
(ns myns.core
  (:require
    [supports-color.core :as sc]))
```

### Consuming via git

Using a tool without Maven support? See
[Consuming supports-color as a git dependency](doc/git-deps.md).

<br />


## Documentation

**[API](doc/api.md)**<br>
The public API.

**[Full output reference](doc/full-output-reference.md)**<br>
Example return values for `profile` and `background`.

**[How detection works](doc/how-detection-works.md)**<br>
Explains how the terminal query works, and outlines the pipeline of checks that determines the color level and the background polarity, 

**[More details about how supports-color works](doc/more-details.md)**<br>
How the api relates to your program and terminal: the subprocesses it starts, the environment variables and flags it reads, timeouts, when the terminal query runs, and how to turn it off.

**[Developing](doc/developing.md)**<br>
Running the tests on each runtime, building and releasing, and regenerating the documentation.

**[Related libraries](doc/related-libraries.md)**<br>
The libraries supports-color builds on, ports of the og chalk/supports-color in other languages, and a full feature comparison.

<br />

## Contributing

Issues for bugs, improvements, or features are very welcome.

Please file an issue for discussion before starting or submitting a PR.

If you want to submit a PR against an open issue, start by dropping a message in the open issue before doing any work or issuing a PR, in order to initiate human communication, and make sure everyone involved is on the same page.

<br />

## License

Copyright © 2026 Jeremiah Coyle

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)
