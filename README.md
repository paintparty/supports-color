# supports-color

**Terminal color capability and background theme detection for Clojure dialects**<br>
<sub>Works with Clojure, ClojureScript, Babashka, and Jolt</sub>

<br/>
<br/>

The `color-supports` API supplies 3 data points:

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
```

<br>

For an orientation to terminology used in this readme, see the [supports-color glossary](doc/supports-color-glossary.md).


<br>

## Development Status

| Period           | Stage        | Release                                                                   |
| ---------------- | ------------ | ------------------------------------------------------------------------- |
| 2025 Oct         | Experimental | (Internal [Fireworks](https://github.com/paintparty/fireworks) namespace) |
| 2026 Nov-present | Alpha        | 0.1.0                                                                     |

<br />

## Quick comparison
Comparison with other libs from other languages that you may be familiar with:
|  | this library | [chalk/supports-color↗](https://github.com/chalk/supports-color) | [colorprofile↗](https://github.com/charmbracelet/colorprofile) |
|---|---|---|---|
| Language | Clojure dialects | JavaScript, Node | Go |
| Color support level (16, 256, truecolor) | ✓ | ✓ | ✓ |
| Respects `NO_COLOR` | ✓ | ✕ | ✓ |
| Terminal background color query via `OSC 11` | ✓ | ✕ | ✕ |
| Terminal theme query via `CSI ? 996 n`| ✓ | ✕ | ✕ |

View the [full comparison matrix](doc/related-libraries.md#full-comparison-matrix), which shows more features and more libraries.

<br />

## Requirements

**Clojure 1.10 or newer**.

The library code itself uses nothing past Clojure 1.8
and loads fine on 1.9. The stated floor is 1.10 only because the test tooling needs
it (not the runtime). The test suite is verified against 1.10.3, 1.11.4, and
1.12.5, verified with `bb test:compat`. Also runs on Babashka, Jolt, and ClojureScript
(nbb/Node).

<br />

## Setup

Add as a dependency to your project:

Deps:

```clojure
io.github.paintparty/supports-color {:mvn/version "0.1.0"}
```

Leiningen:

```clojure
[io.github.paintparty/supports-color "0.1.0"]
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
The public api.

**[Full output reference](doc/full-output-reference.md)**<br>
The complete maps that `profile` and `background` return, every key included, shown for a truecolor terminal with a dark theme.

**[How detection works](doc/how-detection-works.md)**<br>
The order of checks behind the color level and the background polarity, and how the terminal query works.

**[More details about how supports-color works](doc/more-details.md)**<br>
What supports-color does to your program and terminal: the subprocesses it starts, the environment variables and flags it reads, timeouts, when the terminal query runs, and how to turn it off.

**[Developing](doc/developing.md)**<br>
Running the tests on each runtime, building and releasing, and regenerating the git-deps docs.

**[Related libraries](doc/related-libraries.md)**<br>
The libraries supports-color builds on, ports of supports-color in other languages, and a full feature comparison.

<br />

## Contributing

Issues for bugs, improvements, or features are very welcome.

Please file an issue for discussion before starting or submitting a PR.

If you want to submit a PR against an open issue, start by dropping a message in the open issue before doing any work or issuing a PR, in order to initiate human communication, and make sure everyone is on the same page.

<br />

## License

Copyright © 2026 Jeremiah Coyle

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)
