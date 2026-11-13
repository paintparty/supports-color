(ns supports-color.common
  "Shared utilities and detection logic for supports-color.
   Platform-specific primitives are injected via register-platform!."
  (:require [clojure.string :as str]))
;; Malli spec for terminal/CI environment variables, V3
;;
;; Purpose: warn users about non-conventional values.
;;   - [:enum ...] for variables with known conventional values (strict)
;;   - :string only for genuinely free-form variables
;;
;; Each entry's :desc and :links are the single source of truth for what the
;; variable means and where that understanding came from; there is no separate
;; glossary comment to keep in sync with this schema.

;; ---------------------------------------------------------------------------
;; Mock env spec, valid Malli schema (malli is not used directly in supports-color)
;; ---------------------------------------------------------------------------

(def MockEnv
  [:map

   ;; -- CI detection flags ------------------------------------------
   ;; Presence is what matters, but each CI system documents a specific
   ;; canonical value. Warn if set to something unexpected.

   ["APPVEYOR"
    {:optional true
     :adoption :low
     :desc "AppVeyor is a hosted CI/CD service, launched 2013, historically popular
            for Windows and .NET projects because it offered free Windows build
            agents when most competitors were Linux-only. Set to \"True\" (Windows)
            or \"true\" (Linux/macOS agents) on every AppVeyor build."
     :links [{:url "https://www.appveyor.com/"
              :desc "AppVeyor homepage"}
             {:url "https://en.wikipedia.org/wiki/AppVeyor"
              :desc "AppVeyor on Wikipedia"}]}
    [:enum "True" "true"]]

   ["BUILDKITE"
    {:optional true
     :adoption :low
     :desc "Buildkite is a hybrid CI/CD platform: Buildkite hosts the pipeline
            coordination and UI, but build agents run on the customer's own
            infrastructure (cloud VMs, on-prem, containers). Used by teams that
            need CI but can't or won't hand code/secrets to a fully-hosted runner.
            Set to \"true\" on every Buildkite build."
     :links [{:url "https://buildkite.com/"
              :desc "Buildkite homepage"}
             {:url "https://buildkite.com/docs/pipelines/environment-variables"
              :desc "Buildkite environment variables reference"}]}
    [:enum "true"]]

   ["CI"
    {:optional true
     :adoption :high
     :desc "The generic \"we are running in some CI system\" flag. Popularized by
            Travis CI and now set as a lowest-common-denominator signal by nearly
            every CI provider (GitHub Actions, GitLab CI, CircleCI, Jenkins via
            plugins, etc.), specifically so tools don't need a separate check per
            provider just to answer \"is this CI at all?\""
     :links [{:url "https://github.com/watson/ci-info"
              :desc "ci-info: catalog of CI-provider environment variables"}
             {:url "https://github.com/pvdlg/env-ci"
              :desc "env-ci: another catalog of CI-provider environment variables"}]}
    [:enum "true" "1"]]

   ["CI_NAME"
    {:optional true
     :adoption :low
     :desc "Some CI providers additionally self-identify by name in this
            variable, since the generic CI flag alone doesn't say which
            provider is running."
     :links [{:url "https://github.com/watson/ci-info"
              :desc "ci-info: catalog of CI-provider environment variables"}]}
    :string]

   ["CIRCLECI"
    {:optional true
     :adoption :medium
     :desc "CircleCI is a hosted CI/CD platform founded in 2011, one of the
            earliest cloud CI services alongside Travis CI. Heavily used in the
            JavaScript and Ruby ecosystems and by many startups. Set to \"true\"
            on every CircleCI build."
     :links [{:url "https://circleci.com/"
              :desc "CircleCI homepage"}
             {:url "https://en.wikipedia.org/wiki/CircleCI"
              :desc "CircleCI on Wikipedia"}]}
    [:enum "true"]]

   ["DRONE"
    {:optional true
     :adoption :low
     :desc "Drone is a container-native CI/CD system, typically self-hosted,
            where every pipeline step runs inside its own Docker container.
            Popular with teams that want a lightweight, self-hosted alternative
            to Jenkins; acquired by Harness in 2020 and now distributed as
            Harness CI Community Edition. Set to \"true\" on every Drone build."
     :links [{:url "https://www.drone.io/"
              :desc "Drone CI homepage"}
             {:url "https://en.wikipedia.org/wiki/Drone_(software)"
              :desc "Drone on Wikipedia"}]}
    [:enum "true"]]

   ["GITEA_ACTIONS"
    {:optional true
     :adoption :low
     :desc "Gitea Actions is the built-in CI/CD system for Gitea, a lightweight
            self-hosted Git server popular as a GitHub alternative for
            self-hosters. Its workflow syntax intentionally mirrors GitHub
            Actions. Set to \"true\" on every Gitea Actions run."
     :links [{:url "https://docs.gitea.com/usage/actions/overview"
              :desc "Gitea Actions overview"}
             {:url "https://en.wikipedia.org/wiki/Gitea"
              :desc "Gitea on Wikipedia"}]}
    [:enum "true"]]

   ["GITHUB_ACTIONS"
    {:optional true
     :adoption :high
     :desc "GitHub Actions is GitHub's built-in CI/CD, introduced in 2019. Given
            GitHub's dominance in hosting both open-source and private
            repositories, it is now likely the single most common CI system
            build tooling will encounter. Set to \"true\" on every Actions run."
     :links [{:url "https://docs.github.com/en/actions"
              :desc "GitHub Actions documentation"}
             {:url "https://en.wikipedia.org/wiki/GitHub_Actions"
              :desc "GitHub Actions on Wikipedia"}]}
    [:enum "true"]]

   ["GITLAB_CI"
    {:optional true
     :adoption :medium
     :desc "GitLab CI/CD is GitLab's built-in pipeline system, available on both
            hosted GitLab.com and self-hosted GitLab instances. Widely used by
            enterprises and teams running their own GitLab server rather than
            GitHub. Set to \"true\" on every GitLab CI run."
     :links [{:url "https://docs.gitlab.com/ee/ci/"
              :desc "GitLab CI/CD documentation"}
             {:url "https://en.wikipedia.org/wiki/GitLab"
              :desc "GitLab on Wikipedia"}]}
    [:enum "true"]]

   ["TEAMCITY_VERSION"
    {:optional true
     :adoption :medium
     :desc "TeamCity is JetBrains' CI/CD server, common in enterprise .NET and
            JVM shops (it integrates tightly with IntelliJ-family IDEs). Unlike
            most other CI flags, it reports its own server version string rather
            than a bare boolean, e.g. \"2024.03\"."
     :links [{:url "https://www.jetbrains.com/teamcity/"
              :desc "TeamCity homepage"}
             {:url "https://en.wikipedia.org/wiki/TeamCity"
              :desc "TeamCity on Wikipedia"}]}
    :string]

   ["TF_BUILD"
    {:optional true
     :adoption :medium
     :desc "TF_BUILD (\"Team Foundation Build\") is set by Azure Pipelines,
            Microsoft's CI/CD offering integrated into Azure DevOps and the
            successor to Team Foundation Server/VSTS. Common in enterprise .NET
            shops already invested in the Microsoft/Azure ecosystem."
     :links [{:url "https://learn.microsoft.com/en-us/azure/devops/pipelines/"
              :desc "Azure Pipelines documentation"}
             {:url "https://en.wikipedia.org/wiki/Azure_DevOps"
              :desc "Azure DevOps on Wikipedia"}]}
    [:enum "True" "true"]]

   ["TRAVIS"
    {:optional true
     :adoption :low
     :desc "Travis CI, launched 2011, was one of the first hosted CI services
            for GitHub projects and was hugely influential in popularizing
            \"CI as a service\" for open source; the generic CI env var
            convention traces back to it. Usage has declined sharply since
            GitHub Actions, but many older open-source projects still carry
            Travis config and this check. Set to \"true\" on every Travis build."
     :links [{:url "https://www.travis-ci.com/"
              :desc "Travis CI homepage"}
             {:url "https://en.wikipedia.org/wiki/Travis_CI"
              :desc "Travis CI on Wikipedia"}]}
    [:enum "true"]]

   ;; -- Color control ---------------------------------------------

   ["CLICOLOR"
    {:optional true
     :adoption :medium
     :desc "CLICOLOR/CLICOLOR_FORCE is a BSD-world convention (its roots are in
            FreeBSD's colorized `ls` and related utilities) for opting a CLI
            tool into color only when writing to a real terminal. \"0\" disables
            color outright; \"1\" enables it when the output is a tty. Adopted
            outside BSD too, e.g. by CMake."
     :links [{:url "bixense.com/clicolors"
              :desc "CLICOLOR/CLICOLOR_FORCE convention"}
             {:url "cmake.org/cmake/help/latest/envvar/CLICOLOR.html"
              :desc "CMake's adoption of CLICOLOR"}]}
    [:enum "0" "1"]]

   ["CLICOLOR_FORCE"
    {:optional true
     :adoption :medium
     :desc "The forcing half of the CLICOLOR pair: \"0\" or absent means no
            force, any non-zero value requests color even when output is
            redirected to a file or pipe (where a tool would normally disable
            color automatically)."
     :links [{:url "bixense.com/clicolors"
              :desc "CLICOLOR/CLICOLOR_FORCE convention"}
             {:url "cmake.org/cmake/help/latest/envvar/CLICOLOR.html"
              :desc "CMake's adoption of CLICOLOR"}]}
    [:enum "0" "1"]]

   ["CLITHEME"
    {:optional true
     :adoption :low
     :desc "A newer, not-yet-widely-adopted proposed standard
            (wiki.tau.garden/cli-theme) letting a user declare a light/dark/auto
            preference for CLI tools to style around, independent of terminal
            queries. Base values are \"dark\", \"light\", \"auto\"; optional
            colon-separated modifiers can follow, e.g. \"dark:high-contrast\"."
     :links [{:url "wiki.tau.garden/cli-theme"
              :desc "CLITHEME proposal"}]}
    [:fn {:error/message "expected dark[:<mod>], light[:<mod>], or auto[:<mod>]"}
     #(re-matches #"(?:dark|light|auto)(?::.*)*" %)]]

   ["COLORFGBG"
    {:optional true
     :adoption :low
     :desc "A semicolon-delimited foreground/background palette-index hint that
            predates the truecolor era, originating with the rxvt terminal
            emulator and later adopted by others including Konsole. The final
            numeric field gives a coarse ANSI-16-index light/dark signal (below
            7 is dark, 7 and above is light), e.g. \"15;0\" or
            \"0;default;15\". Values are too provider-specific to enumerate."
     :links [{:url "github.com/neovim/neovim/issues/2764"
              :desc "COLORFGBG background-detection discussion"}
             {:url "pypi.org/project/term-background"
              :desc "term-background's use of COLORFGBG"}]}
    :string]

   ["COLORTERM"
    {:optional true
     :adoption :medium
     :desc "A terminal capability hint, commonly set by terminal emulators
            (originating with things like gnome-terminal/konsole, later adopted
            by Alacritty, kitty, iTerm2, and others) specifically to advertise
            24-bit truecolor RGB support, which TERM's terminfo-name convention
            has no clean way to express."
     :links [{:url "github.com/alacritty/alacritty/issues/1526"
              :desc "COLORTERM truecolor discussion"}
             {:url "marvinh.dev/blog/terminal-colors"
              :desc "Terminal color-depth survey"}]}
    [:enum "truecolor" "24bit"]]

   ["COLOR_BACKGROUND"
    {:optional true
     :adoption :low
     :desc "A background color as a hex string, e.g. \"#303446\". No known
            terminal or standard sets it. supports-color returns it in :env
            and does not act on it."}
    :string]

   ["COLOR_CONTRAST"
    {:optional true
     :adoption :low
     :desc "A contrast preference. No known terminal or standard sets it.
            supports-color returns it in :env and does not act on it."}
    [:enum "high" "medium" "low"]]

   ["COLOR_FOREGROUND"
    {:optional true
     :adoption :low
     :desc "A foreground color as a hex string, e.g. \"#c6d0f5\". No known
            terminal or standard sets it. supports-color returns it in :env
            and does not act on it."}
    :string]

   ["COLOR_THEME"
    {:optional true
     :adoption :low
     :desc "A light/dark preference. No known terminal or standard sets it.
            supports-color returns it in :env and does not act on it. CLITHEME
            is the published proposal for this."}
    [:enum "dark" "light"]]

   ["FORCE_COLOR"
    {:optional true
     :adoption :high
     :desc "An explicit color-level override that originated in the Node.js
            ecosystem via chalk/supports-color, then spread as a de facto
            convention (championed at force-color.org) to CLI tooling well
            beyond Node. Empty counts as unset (force-color.org). false/0
            disable color outright (chalk), 1-3 select progressively richer
            palettes (\"1\"=basic 16 colors, \"2\"=256 colors, \"3\"=16m
            truecolor), and true or any other non-empty value forces basic
            color."
     :links [{:url "npmjs.com/package/chalk"
              :desc "chalk's FORCE_COLOR handling"}
             {:url "force-color.org"
              :desc "FORCE_COLOR convention"}]}
    [:enum "0" "1" "2" "3"]]

   ["NO_COLOR"
    {:optional true
     :adoption :high
     :desc "An informal, cross-language, cross-tool standard (no-color.org)
            letting a user opt every well-behaved CLI tool out of ANSI color at
            once, regardless of platform or ecosystem. Presence disables color
            no matter what value is set; the value itself is intentionally
            irrelevant, only presence/absence is checked."
     :links [{:url "no-color.org"
              :desc "NO_COLOR convention"}]}
    :string]

   ;; ── Terminal identification ──────────────────────────────────────────

   ["TERM"
    {:optional true
     :adoption :high
     :desc "The classic Unix terminfo/termcap entry name, a decades-old
            convention identifying the terminal type so curses-family libraries
            know which capabilities and escape sequences to use. Warns on
            values outside the common set below rather than rejecting them,
            since new terminal emulators regularly introduce new TERM values."}
    [:enum
     "xterm-ghostty"
     "xterm-256color"
     "xterm"
     "screen"
     "screen-256color"
     "tmux-256color"
     "linux"
     "dumb"
     "vt100"
     "vt220"
     "rxvt-unicode-256color"]]

   ["TERM_PROGRAM"
    {:optional true
     :adoption :medium
     :desc "Set by specific terminal emulator applications (iTerm2, Apple
            Terminal, VS Code's integrated terminal, WezTerm, Alacritty, Hyper,
            tmux, kitty, Ghostty) to self-identify beyond what the generic,
            terminfo-oriented TERM variable can express."}
    [:enum "ghostty"
     "iTerm.app"
     "Apple_Terminal"
     "vscode"
     "WezTerm"
     "alacritty"
     "Hyper"
     "tmux"
     "kitty"]]

   ["TERM_PROGRAM_VERSION"
    {:optional true
     :adoption :medium
     :desc "The version of whichever terminal emulator TERM_PROGRAM identifies,
            e.g. \"1.3.1\"."}
    :string]

   ;; -- Misc ---------------------------------------------------

   ["AGENT_NAME"
    {:optional true
     :adoption :low
     :desc "Build agent name, set alongside TF_BUILD in Azure Pipelines runs to
            identify which agent machine executed the build."}
    :string]

   ;; -- supports-color's own ---------------------------------------

   ["SUPPORTS_COLOR_NO_QUERY"
    {:optional true
     :adoption :low
     :desc "Off switch for the terminal queries. Any non-empty value makes
            `background` skip the CSI ?996 and OSC 11 queries, so the terminal is
            never put into raw mode and nothing is written to /dev/tty. Polarity
            then comes only from CLITHEME and COLORFGBG. It can only turn
            behavior off, never choose a binary or a path."}
    :string]])

(def SupportsColor
  "Schema for supports-color detection results. Every result carries :env and
   :runtime. The rest is keyed by which function produced it: profile contributes
   :level and :color-profile; background contributes :polarity, :background-color,
   and the detection-* metadata. A merged (profile + background) map has all of
   them, so the function-specific keys are :optional."
  [:map
   [:env MockEnv]
   [:runtime :map]

   ;; -- from profile ------------------------------------------------------
   [:level         {:optional true} [:enum 0 1 2 3]]
   [:color-profile {:optional true} [:enum :no-color :basic :ansi256 :truecolor]]

   ;; -- from background ---------------------------------------------------
   [:polarity         {:optional true} [:maybe [:enum "dark" "light"]]]
   [:background-color {:optional true} [:maybe [:map [:r :int] [:g :int] [:b :int]]]]
   [:detection-method {:optional true} [:enum :clitheme :csi996 :osc11 :colorfgbg :prefers-color-scheme :none]]
   [:clitheme         {:optional true} [:maybe [:enum :dark :light]]]
   [:csi996-result    {:optional true} [:maybe [:enum :dark :light]]]
   [:csi996-supported {:optional true} :boolean]
   [:osc11-supported  {:optional true} :boolean]

   ;; -- reserved for the parked :theme-profile work, not yet produced -----
   [:contrast                 {:optional true} [:enum "high" "low" "medium"]]
   [:color-variant            {:optional true} [:enum "extra-light" "light" "medium" "dark" "extra-dark"]]
   [:background-color-variant {:optional true} [:enum "shadow" "highlight"]]])


;; ---------------------------------------------------------------------------
;; Platform injection
;; ---------------------------------------------------------------------------

(defonce ^{:private true
           :doc "Holds the active platform adapter installed by register-platform!."}
  pf
  (atom nil))

(defn ^:no-doc register-platform!
  "Installs the platform-specific environment, TTY, OS, argv, and terminal-query
   functions used by the shared detection logic. Replaces any previously
   registered adapter, for the whole process, and returns impl. supports-color.core
   calls it on load, and the tests call it to fake a platform. Internal, can change
   in any release.

   Optional keys: :host, reported as :runtime :host in place of :jvm or :js
   (:browser makes profile return level 3 and skips the terminal query), and
   :prefers-dark, a fn returning true, false, or nil that background and theme
   use for the polarity when :host is :browser."
  [impl]
  (reset! pf impl))

(defn ^:no-doc tool-candidates
  "Absolute paths tried, in order, to find the system tool `tool-name`. The first
   that exists is used. Nothing is looked up on PATH and no environment variable
   can add to or replace this list, so neither PATH nor a project's .env can
   choose the binary. Internal, can change in any release."
  [tool-name]
  [(str "/bin/" tool-name)
   (str "/usr/bin/" tool-name)
   (str "/run/current-system/sw/bin/" tool-name)             ; NixOS
   (str "/run/current-system/profile/bin/" tool-name)        ; Guix System
   (str "/data/data/com.termux/files/usr/bin/" tool-name)])  ; Termux

(def ^:no-doc stty-candidates
  "Absolute paths tried, in order, to find `stty`. See `tool-candidates`.
   Internal, can change in any release."
  (tool-candidates "stty"))

;; ---------------------------------------------------------------------------
;; Platform wrapper functions
;; ---------------------------------------------------------------------------

(defn- real-get-env [k]
  ((:real-get-env @pf) k))

(defn- stdout-tty? []
  ((:stdout-tty @pf)))

(defn- stderr-tty? []
  ((:stderr-tty @pf)))

(defn- stdin-tty? []
  ((:stdin-tty @pf)))

(defn- host
  "The adapter's :host, or :jvm or :js by platform."
  []
  (or (:host @pf) #?(:clj :jvm :cljs :js)))


(defn- platform []
  ((:platform @pf)))

(defn- os-release []
  ((:os-release @pf)))

(defn- argv []
  ((:argv @pf)))

(defn ^:no-doc lower-case-ascii
  "Lowercases A-Z only. String.toLowerCase follows the default locale, and under
   tr or az an I lowercases to a dotless i, so CLITHEME=LIGHT stopped matching
   \"light\". Jolt has no java.util.Locale, so Locale/ROOT is not an option.
   Every string this is used on is ASCII by contract. Internal, can change in
   any release."
  [s]
  (apply str (map (fn [ch]
                    (let [code #?(:clj (int ch) :cljs (.charCodeAt ch 0))]
                      (if (<= 65 code 90) (char (+ code 32)) ch)))
                  s)))

(defn- parse-decimal
  "A string of 1 to 9 ASCII digits as a number, else nil. Integer/parseInt also
   takes other scripts' digits, and js/parseInt takes a numeric prefix (\"7abc\")
   and leading spaces, so the same env value read differently on the JVM and
   Node. Nine digits cannot overflow an int on either."
  [s]
  (when (and (string? s) (re-matches #"[0-9]{1,9}" s))
    #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))))

(defn- hex->long [s]
  #?(:clj  (try (Long/parseLong s 16) (catch Exception _ nil))
     :cljs (let [n (js/parseInt s 16)] (when-not (js/isNaN n) n))))

(defn ^:no-doc snapshot-cooked?
  "Whether the `stty -g` snapshot `s` has canonical input (ICANON) and echo
   (ECHO) on. Returns true, false when either is off, or nil when `s` is not one
   of the two known formats. `platform` (:macos, :unix) picks the ICANON bit for
   the positional format, and defaults to the registered adapter's.

   BSD and macOS print `gfmt1:...:lflag=<hex>:...`, where ICANON is 0x100. GNU
   coreutils and busybox print positional hex, iflag:oflag:cflag:lflag:cc...,
   where ICANON is 0x2 on Linux and 0x100 on macOS. ECHO is 0x8 on all of them.

   A query that starts on a terminal that is not cooked declines: another
   process, a pager or a TUI holds it raw, and restoring this snapshot later
   would write that raw state back after the other process restored cooked.
   Internal, can change in any release."
  ([s] (snapshot-cooked? s (platform)))
  ([s platform]
   (when (string? s)
     (let [[lflag icanon]
           (if-let [[_ h] (re-find #"(?:^|:)lflag=([0-9a-fA-F]+)(?::|$)" s)]
             [(hex->long h) 0x100]
             (let [fields (str/split (str/trim s) #":")]
               (when (and (> (count fields) 4)
                          (every? #(re-matches #"[0-9a-fA-F]+" %) fields))
                 [(hex->long (nth fields 3)) (if (= :macos platform) 0x100 0x2)])))]
       (when lflag
         (and (not (zero? (bit-and lflag icanon)))
              (not (zero? (bit-and lflag 0x8)))))))))

(defn ^:no-doc proc-stat-foreground?
  "Whether the Linux `/proc/<pid>/stat` line `s` shows a process in its
   terminal's foreground process group: pgrp (field 5) equals tpgid (field 8).
   Returns true, false (also when there is no controlling terminal, tpgid -1),
   or nil when `s` is not a stat line.

   Fields are counted after the last `)`, because the command name in field 2
   can hold spaces and parentheses. Reading /proc/self/stat needs no subprocess
   and works where `ps` has no `-p` (busybox). Internal, can change in any
   release."
  [s]
  (when (string? s)
    (let [i (str/last-index-of s ")")]
      (when i
        (let [fields (str/split (str/trim (subs s (inc i))) #"\s+")]
          (when (> (count fields) 5)
            (let [pgrp  (nth fields 2)
                  tpgid (nth fields 5)]
              (when (and (re-matches #"\d+" pgrp) (re-matches #"-?\d+" tpgid))
                (= pgrp tpgid)))))))))

;; ---------------------------------------------------------------------------
;; Runtime fact accessors (context-first)
;;
;; PROTOTYPE: each reads a runtime fact from an injected context's :runtime
;; snapshot when that snapshot carries it, and otherwise falls back to the live
;; platform adapter. This is the same override idiom get-env already uses for
;; env vars, extended to TTY/argv/OS. A context with no :runtime (or none at all)
;; behaves exactly like today: every read hits the adapter.
;; ---------------------------------------------------------------------------

(defn- ctx-fact
  "Runtime fact at `path` under context's :runtime, or ::miss if absent."
  [context path]
  (get-in context (into [:runtime] path) ::miss))

(defn- tty?* [context which]
  (let [v (ctx-fact context [:tty which])]
    (if (= v ::miss)
      (case which :stderr (stderr-tty?) :stdin (stdin-tty?) (stdout-tty?))
      v)))

(defn- argv* [context]
  (let [v (ctx-fact context [:argv])]
    ;; Normalize an injected argv to a vector so the .indexOf interop in has-flag?
    ;; works uniformly on JVM and Node for any injected sequence (vector, list,
    ;; lazy-seq, JS array). The live adapter value is used as-is.
    (if (= v ::miss) (argv) (vec v))))

(defn- platform* [context]
  (let [v (ctx-fact context [:os :platform])]
    (if (= v ::miss) (platform) v)))

(defn- os-release* [context]
  (let [v (ctx-fact context [:os :version])]
    (if (= v ::miss) (os-release) v)))

(defn- os-name* [context]
  (let [v (ctx-fact context [:os :name])]
    (if (= v ::miss) ((:os-name @pf)) v)))

;; ---------------------------------------------------------------------------
;; Pure utilities
;; ---------------------------------------------------------------------------


(defn- have-terminal?
  "True when this process has a terminal on stdout or stderr. Controls whether
   the /dev/tty terminal queries run at all. In a daemon or CI job with
   no controlling terminal, `stty -g`/`stty raw` can only fail, so spawning them
   wastes subprocesses and up to timeout-ms of startup latency, and needlessly
   reaches the stty PATH-resolution logic in exactly the environments where PATH
   is least trusted. This checks the output descriptors rather than stdin, so a legitimate
   `cmd < file` on a real terminal still queries. The CLJS side applies the same
   check to its queries via `(.isatty tty 0)`.

   Reads TTY state from the injected context's :runtime when present, so an
   injected non-terminal context skips the terminal queries just like a real
   daemon would."
  ([] (have-terminal? nil))
  ([context]
   (or (tty?* context :stdout) (tty?* context :stderr))))

(defn- non-blank
  "Returns s if it's a non-empty string, otherwise nil."
  [s]
  (when (and (string? s) (not (empty? s))) s))

(defn ^:public get-env
  "Reads env var k, consulting mock-env first if bound. A k that is not a string
   names no env var, so it reads as nil."
  ([k] (get-env k nil))
  ([k mock-env]
   (when (string? k)
     (if mock-env
       (if (contains? mock-env k)
         (let [v (get mock-env k)]
           (when (some? v) (str v)))
         (real-get-env k))
       (real-get-env k)))))

(defn- env-contains?
  "Returns true if env var k is set to a non-empty value, consulting mock-env if
   bound. An empty value counts as unset, as no-color.org specifies for NO_COLOR
   (\"present and not an empty string\"). The same rule applies to every
   presence flag (CI, CLICOLOR_FORCE, TERM_PROGRAM, the CI provider flags), so
   `NO_COLOR= cmd` or an export of an unset variable never switches anything on.
   FORCE_COLOR is a value, not a flag, and does its own check."
  ([k] (env-contains? k nil))
  ([k mock-env]
   (some? (non-blank (get-env k mock-env)))))

(def ^:public ^:no-doc env-var-keys
  "Based on Mock Env schema"
  (reduce (fn [vc x] (if (vector? x) (conj vc (nth x 0)) vc)) [] MockEnv))

;; ---------------------------------------------------------------------------
;; Debug output helpers
;; ---------------------------------------------------------------------------

(defn- escape-controls
  "Shows every C0 and C1 control byte and DEL in `s` as \\uXXXX and leaves the
   rest as is, including the trace's own box-drawing characters. Env and context
   values are untrusted. An ESC or BEL printed raw would act on the terminal
   reading the trace, or on whoever later cats a log of it."
  [s]
  (apply str (map (fn [ch]
                    (let [code #?(:clj (int ch) :cljs (.charCodeAt ch 0))]
                      (if (or (< code 0x20) (<= 0x7f code 0x9f))
                        (let [h #?(:clj  (Integer/toHexString code)
                                   :cljs (.toString code 16))]
                          (str "\\u" (subs "0000" (count h)) h))
                        ch)))
                  s)))

(defn- dbg
  "Prints an indented debug trace line with box-drawing prefix. Control bytes in
   the printed values are escaped."
  [depth & parts]
  (println (str (apply str (repeat depth "│  ")) "│  "
                (escape-controls (apply str parts)))))

(defn- dbg-blank
  "Prints an empty debug trace line at the given nesting depth."
  [depth]
  (println (str (apply str (repeat depth "│  ")) "│")))

(defn- dbg-header
  "Prints a boxed section header for debug trace output."
  [depth label]
  (let [pfx (apply str (repeat depth "│  "))
        n   (max 1 (- 48 (count label)))]
    (println (str pfx "╭─ " label " " (apply str (repeat n "─"))))))

(defn- dbg-footer
  "Prints a section footer line with result summary for debug output."
  [depth & parts]
  (println (str (apply str (repeat depth "│  ")) "╰─ " (apply str parts))))

(defn- dbg-close
  "Prints a closing border line for a debug trace section."
  [depth]
  (println (str (apply str (repeat depth "│  "))
                "╰────────────────────────────────────────────────")))

(def ^:private max-debug-raw
  "Cap on how much of a raw terminal reply is rendered into a debug trace,
   matching the read-loop's own response cap so an over-long or hostile
   reply cannot flood the trace."
  256)

(defn- dbg-raw
  "Render a raw terminal reply for a debug trace, with every byte below 0x20 or
   above 0x7e shown as \\uXXXX and the whole thing truncated to `max-debug-raw`
  . Terminal replies are untrusted input. `pr-str` does not escape
   control bytes, so printed verbatim an embedded ESC/OSC/BEL sequence would
   reach the debug terminal, or a log someone later cats, unescaped. Returns
   \"nil\" for a nil reply, matching the previous pr-str output on the failure
   branches."
  [s]
  (if (nil? s)
    "nil"
    (let [capped (subs s 0 (min (count s) max-debug-raw))]
      (str "\""
           (apply str
                  (map (fn [ch]
                         (let [code #?(:clj (int ch) :cljs (.charCodeAt ch 0))]
                           (if (or (< code 0x20) (> code 0x7e))
                             (let [h #?(:clj  (Integer/toHexString code)
                                        :cljs (.toString code 16))]
                               (str "\\u" (subs "0000" (count h)) h))
                             ch)))
                       capped))
           "\""))))

;; ---------------------------------------------------------------------------
;; CLI flag sniffing
;; Color flags are process-local overrides used by many CLI tools. We inspect only
;; arguments before the conventional `--` terminator, because later values belong
;; to a child command or positional input. For example, `--color=256` requests
;; level 2, `--color=16m` requests level 3, and `--no-color` requests level 0;
;; in `tool -- child --no-color`, the final flag must not affect this process.
;; ---------------------------------------------------------------------------

(defn- has-flag?
  "Returns true if the given CLI flag is present in argv before any -- terminator.
   Reads argv from the injected context when present, else the adapter."
  ([flag] (has-flag? flag nil))
  ([flag context]
   (let [prefix (cond
                  (.startsWith flag "-") ""
                  (= 1 (count flag))    "-"
                  :else                 "--")
         target     (str prefix flag)
         args       (argv* context)
         position   (.indexOf args target)
         terminator (.indexOf args "--")]
     (and (not= position -1)
          (or (= terminator -1) (< position terminator))))))

(defn- sniff-flag-force-color
  "Scans CLI args for --color/--no-color flags and returns the implied level
  (0-3) or nil."
  ([] (sniff-flag-force-color nil))
  ([context]
   (cond
     (or (has-flag? "no-color" context) (has-flag? "no-colors" context)
         (has-flag? "color=false" context) (has-flag? "color=never" context))   0
     (or (has-flag? "color=16m" context) (has-flag? "color=full" context)
         (has-flag? "color=truecolor" context))                                 3
     (has-flag? "color=256" context)                                            2
     (or (has-flag? "color" context) (has-flag? "colors" context)
         (has-flag? "color=true" context) (has-flag? "color=always" context))   1
     :else nil)))

(def ^:private color-flags
  "Every color-related CLI flag detection recognizes, without the leading --."
  ["no-color" "no-colors" "color=false" "color=never"
   "color=16m" "color=full" "color=truecolor" "color=256"
   "color" "colors" "color=true" "color=always"])

(defn- sniff-matched-flags
  "Returns the set of recognized color-related CLI flags present in argv."
  ([] (sniff-matched-flags nil))
  ([context]
   (into #{} (filter #(has-flag? % context)) color-flags)))

(defn- color-flag-args
  "The recognized color flags in argv, before any -- terminator, in argv order.
   This is the :argv a result carries: enough to replay the same level, and
   nothing else from the command line, which can hold paths and secrets."
  [context]
  (let [known (into #{} (map #(str "--" %)) color-flags)]
    (into [] (comp (take-while #(not= "--" %)) (filter known)) (argv* context))))

;; ---------------------------------------------------------------------------
;; FORCE_COLOR env var
;; ---------------------------------------------------------------------------


(defn- env-force-color
  "Parses FORCE_COLOR into a color level, or nil when it is unset or empty.

   force-color.org: when FORCE_COLOR is \"present and not an empty string
   (regardless of its value), it should force the addition of ANSI color\". So
   an empty value counts as unset, and any non-empty value forces color.
   Two departures, both chalk's convention: \"0\" and \"false\" turn color off,
   since that is what people setting them mean, and \"1\" to \"3\" select the
   level. \"true\" and any other value force basic color (1). Values are matched
   exactly, so the JVM and Node read \"3 \" the same way (as 1)."
  [mock-env]
  (when-let [v (non-blank (get-env "FORCE_COLOR" mock-env))]
    (case v
      ("0" "false") 0
      ("1" "true")  1
      "2"           2
      "3"           3
      1)))

;; ---------------------------------------------------------------------------
;; Context normalization
;; ---------------------------------------------------------------------------

(defn- normalize-env
  "The caller's :env as a map, or nil. A Clojure map is used as is. A JS object
   (process.env) or a java.util.Map is converted. Anything else is ignored."
  [env]
  (cond
    (nil? env) nil
    (map? env) env
    #?@(:cljs [(object? env) (js->clj env)])
    :else (try (into {} env)
               (catch #?(:clj Exception :cljs :default) _e nil))))

(defn- normalize-context
  "The caller's :context with every malformed piece dropped, so detection reads
   that fact live instead of throwing. Public fns promise that bad input degrades
   rather than throws, as `sanitize-timeout-ms` does for :timeout-ms.

   - :env, see `normalize-env`.
   - :runtime :argv, a sequential value or JS array, as a vector of strings.
   - :runtime :tty, only true/false facts. A string \"false\" would read as a tty.
   - :runtime :os, :name and :version as strings, :platform only as :win32,
     :macos or :unix."
  [context]
  (when (map? context)
    (let [rt   (when (map? (:runtime context)) (:runtime context))
          argv (:argv rt)
          tty  (when (map? (:tty rt))
                 (into {} (filter (comp boolean? val)) (:tty rt)))
          os   (when (map? (:os rt))
                 (let [{:keys [name version platform]} (:os rt)]
                   (cond-> {}
                     (some? name)                      (assoc :name (str name))
                     (some? version)                   (assoc :version (str version))
                     (#{:win32 :macos :unix} platform) (assoc :platform platform))))
          rt   (cond-> (dissoc rt :argv :tty :os)
                 (or (sequential? argv) #?(:cljs (array? argv) :clj false))
                 (assoc :argv (mapv str argv))
                 (seq tty) (assoc :tty tty)
                 (seq os)  (assoc :os os))
          env  (normalize-env (:env context))]
      (cond-> (dissoc context :env :runtime)
        env       (assoc :env env)
        (seq rt)  (assoc :runtime rt)))))

;; ---------------------------------------------------------------------------
;; Context collection
;; ---------------------------------------------------------------------------

(defn- collect-env
  "Snapshots all color-relevant env vars into a {name value} map: each value as
   detection read it, nil when unset. An empty value stays \"\", so a result fed
   back as :context resolves to the same level (an empty FORCE_COLOR requests
   basic color, while an empty presence flag counts as unset). Only the keys in
   env-var-keys are kept: a caller's :env can hold anything, secrets included."
  [mock-env]
  (select-keys (merge (into {} (map (fn [k] [k (get-env k mock-env)])) env-var-keys)
                      mock-env)
               env-var-keys))

(defn- collect-runtime
  "Gathers OS, TTY, and CLI flag info into a runtime context map. Reads each fact
   from the injected context's :runtime when present, else the live adapter."
  [{sniff-flags? :sniff-flags :or {sniff-flags? true}}
   mock-env context]
  (let [flag-level (when sniff-flags? (sniff-flag-force-color context))]
    {:host  (host)
     :os    {:name     (non-blank (os-name* context))
             :version  (non-blank (os-release* context))
             :platform (platform* context)}
     :tty   {:stdout (tty?* context :stdout)
             :stderr (tty?* context :stderr)
             :stdin  (tty?* context :stdin)}
     :argv  (color-flag-args context)
     :flags {:sniff-flags sniff-flags?
             :matched     (when sniff-flags? (sniff-matched-flags context))
             :level       flag-level}}))

(defn- collect-context
  "Combines env and runtime snapshots into a single context map. The optional
   third argument injects a prior context whose :runtime overrides live reads."
  ([opts mock-env] (collect-context opts mock-env nil))
  ([opts mock-env context]
   {:env     (collect-env mock-env)
    :runtime (collect-runtime opts mock-env context)}))

;; ---------------------------------------------------------------------------
;; Color profile detection (env-var heuristics)
;; ---------------------------------------------------------------------------

(defn- detect-level
  "Walks the ordered env/flag/TTY/CI heuristic chain and returns a color level:
   0 for no color, 1 for basic ANSI, 2 for ANSI 256, or 3 for truecolor. Earlier
   signals have precedence: command-line color flags beat NO_COLOR, as
   no-color.org asks (\"per-instance command-line arguments should override the
   NO_COLOR variable\"), NO_COLOR beats the FORCE_COLOR env var, and explicit
   overrides beat inferred terminal capabilities.

   Examples with flag sniffing disabled:
     a non-TTY with no override resolves to 0;
     a TTY with TERM=xterm-256color resolves to 2;
     a TTY with COLORTERM=truecolor resolves to 3."
  [{stream-tty?  :stream-tty
    sniff-flags? :sniff-flags
    debug?       :debug
    :keys        [context]
    :or          {sniff-flags? true}}
   mock-env]
  (let [env-fc      (env-force-color mock-env)
        flag-fc     (when sniff-flags? (sniff-flag-force-color context))
        force-color (or flag-fc env-fc)
        term        (get-env "TERM" mock-env)
        colorterm   (get-env "COLORTERM" mock-env)
        hit         (fn [s level]
                      (when debug? (dbg 1 "★ " s " → level " level))
                      {::level level})
        skip        (fn [s] (when debug? (dbg 1 "· " s)) nil)]

    (when debug?
      (dbg-header 1 "detect-level")
      (dbg 1 "flag-force-color: " (pr-str flag-fc))
      (dbg 1 "FORCE_COLOR: " (pr-str env-fc))
      (dbg 1 "force color, resolved: " (pr-str force-color))
      (dbg 1 "TERM: " (pr-str term))
      (dbg 1 "COLORTERM: " (pr-str colorterm))
      (dbg 1 "stream-tty: " (pr-str stream-tty?))
      (dbg-blank 1))

    (let [level
          (::level
           (or
            ;; 0. A browser dev console renders truecolor. There is no env,
            ;; argv, or terminal to read.
            (if (= :browser (host))
              (hit "browser dev console" 3)
              (skip "not a browser"))

            ;; 1. Command-line color flags (--color, --no-color, --color=256, ...).
            ;; no-color.org: per-instance command-line arguments override NO_COLOR.
            (if (some? flag-fc)
              (hit (str "color flag, force-color=" flag-fc) (min flag-fc 3))
              (skip "no color flag"))

            ;; 2. NO_COLOR - any non-empty value means level 0
            (if (env-contains? "NO_COLOR" mock-env)
              (hit "NO_COLOR set" 0)
              (skip "NO_COLOR not set"))

            ;; 3. FORCE_COLOR - overrides all heuristics, sets level directly
            (if (some? env-fc)
              (hit (str "FORCE_COLOR=" env-fc) (min env-fc 3))
              (skip "No FORCE_COLOR set"))

            ;; 3. Truecolor flags
            (if (and sniff-flags?
                     (or (has-flag? "color=16m" context) (has-flag? "color=full" context)
                         (has-flag? "color=truecolor" context)))
              (hit "--color=16m/full/truecolor flag" 3)
              (skip "no truecolor flag"))

            ;; 4. 256-color flag
            (if (and sniff-flags? (has-flag? "color=256" context))
              (hit "--color=256 flag" 2)
              (skip "no --color=256 flag"))

            ;; 5. Azure Pipelines
            (if (and (env-contains? "TF_BUILD" mock-env)
                     (env-contains? "AGENT_NAME" mock-env))
              (hit "TF_BUILD + AGENT_NAME (Azure Pipelines)" 1)
              (skip "TF_BUILD + AGENT_NAME not both set"))

            ;; 6. Not a TTY with no force override
            (if (and (not stream-tty?) (nil? force-color))
              (hit "stream not TTY, no force-color override" 0)
              (skip (if stream-tty?
                      "stream is TTY"
                      (str "non-TTY but force-color=" force-color))))

            ;; 7. Dumb terminal
            (if (= term "dumb")
              (hit (str "TERM=\"dumb\""
                        (when force-color (str ", force-color=" force-color)))
                   (or force-color 0))
              (skip "TERM ≠ \"dumb\""))

            ;; 8. Windows version detection
            (if (= (platform* context) :win32)
              (let [release (os-release* context)
                    parts   (str/split release #"\.")
                    major   (or (parse-decimal (nth parts 0)) 0)
                    build   (when (>= (count parts) 3)
                              (parse-decimal (nth parts 2)))]
                (when debug?
                  (dbg 1 "  platform=:win32 release=" release
                       " major=" major " build=" (pr-str build)))
                (if (and (>= major 10) build (>= build 10586))
                  (if (>= build 14931)
                    (hit "Windows ≥10 build≥14931" 3)
                    (hit "Windows ≥10 build≥10586" 2))
                  (hit "Windows (basic)" 1)))
              (skip "platform ≠ :win32"))

            ;; 9. CI environment
            (if (env-contains? "CI" mock-env)
              (do
                (when debug? (dbg 1 "  CI detected, checking provider..."))
                (cond
                  (some #(env-contains? % mock-env)
                        ["GITHUB_ACTIONS"
                         "GITEA_ACTIONS"
                         "CIRCLECI"])
                  (hit "CI: GitHub/Gitea Actions or CircleCI" 3)

                  (or (some #(env-contains? % mock-env)
                            ["TRAVIS"
                             "APPVEYOR"
                             "GITLAB_CI"
                             "BUILDKITE"
                             "DRONE"])
                      (= (get-env "CI_NAME" mock-env) "codeship"))
                  (hit "CI: Travis/AppVeyor/GitLab/Buildkite/Drone/Codeship" 1)

                  :else
                  (hit (str "CI (unknown)"
                            (when force-color (str ", force-color=" force-color)))
                       (or force-color 0))))
              (skip "CI not set"))

            ;; 10. TeamCity
            (if (env-contains? "TEAMCITY_VERSION" mock-env)
              (let [tc (or (get-env "TEAMCITY_VERSION" mock-env) "")]
                (when debug? (dbg 1 "  TEAMCITY_VERSION=" (pr-str tc)))
                (if (re-find #"^(9\.(0*[1-9]\d*)\.\d+\.|\d{2,}\.)" tc)
                  (hit "TeamCity ≥9.x" 1)
                  (hit "TeamCity (old)" 0)))
              (skip "TEAMCITY_VERSION not set"))

            ;; 11. COLORTERM=truecolor/24bit
            (if (or (= colorterm "truecolor") (= colorterm "24bit"))
              (hit (str "COLORTERM=\"" colorterm "\"") 3)
              (skip (str "COLORTERM=" (pr-str colorterm) " ≠ truecolor/24bit")))

            ;; 12. Known truecolor terminals by TERM
            (if (contains? #{"xterm-kitty" "xterm-ghostty" "wezterm"} term)
              (hit (str "TERM=\"" term "\" (known truecolor)") 3)
              (skip "TERM not in {xterm-kitty, xterm-ghostty, wezterm}"))

            ;; 13. TERM_PROGRAM
            (if (env-contains? "TERM_PROGRAM" mock-env)
              (let [tp  (get-env "TERM_PROGRAM" mock-env)
                    ver (some-> (get-env "TERM_PROGRAM_VERSION" mock-env)
                                (str/split #"\.")
                                first
                                parse-decimal)]
                (when debug?
                  (dbg 1 "  TERM_PROGRAM=" (pr-str tp) " version=" (pr-str ver)))
                (case tp
                  "iTerm.app"
                  (if (and ver (>= ver 3))
                    (hit "iTerm.app ≥3" 3)
                    (hit "iTerm.app <3" 2))

                  "Apple_Terminal"
                  (hit "Apple_Terminal" 2)

                  (cond
                    (re-find #"(?i)-256(color)?$" (or term ""))
                    (hit (str "TERM_PROGRAM=" (pr-str tp)
                              ", TERM matches -256color") 2)

                    (re-find #"(?i)^screen|^xterm|^vt100|^vt220|^rxvt|color|ansi|cygwin|linux"
                             (or term ""))
                    (hit (str "TERM_PROGRAM=" (pr-str tp)
                              ", TERM matches basic pattern") 1)

                    (env-contains? "COLORTERM" mock-env)
                    (hit (str "TERM_PROGRAM=" (pr-str tp) ", COLORTERM set") 1)

                    :else
                    (hit (str "TERM_PROGRAM=" (pr-str tp) " (no further match)")
                         (or force-color 0)))))
              (skip "TERM_PROGRAM not set"))

            ;; 14. CLICOLOR_FORCE
            (if (and (env-contains? "CLICOLOR_FORCE" mock-env)
                     (not= (get-env "CLICOLOR_FORCE" mock-env) "0"))
              (hit "CLICOLOR_FORCE set and ≠ \"0\"" 1)
              (skip "CLICOLOR_FORCE not active"))

            ;; 15. CLICOLOR with TTY
            (if (and stream-tty? (env-contains? "CLICOLOR" mock-env)
                     (not= (get-env "CLICOLOR" mock-env) "0"))
              (hit "TTY + CLICOLOR set and ≠ \"0\"" 1)
              (skip "CLICOLOR not applicable"))

            ;; 16. TERM -256color suffix
            (if (re-find #"(?i)-256(color)?$" (or term ""))
              (hit (str "TERM=" (pr-str term) " matches -256color") 2)
              (skip "TERM does not match -256color"))

            ;; 17. TERM basic pattern
            (if (re-find #"(?i)^screen|^xterm|^vt100|^vt220|^rxvt|color|ansi|cygwin|linux"
                         (or term ""))
              (hit (str "TERM=" (pr-str term) " matches basic pattern") 1)
              (skip "TERM does not match basic pattern"))

            ;; 18. COLORTERM set (fallback)
            (if (env-contains? "COLORTERM" mock-env)
              (hit "COLORTERM set (fallback)" 1)
              (skip "COLORTERM not set"))

            ;; 19. Final fallback
            (hit "no match, fallback" (or force-color 0))))]

      (when debug? (dbg-footer 1 "level: " level))
      level)))

(defn- level->profile
  "Maps a numeric color level (0-3) to a profile keyword."
  [level]
  (case (int level)
    0 :no-color  1 :basic  2 :ansi256  3 :truecolor
    :no-color))

;; ---------------------------------------------------------------------------
;; Public API - color profile
;; ---------------------------------------------------------------------------


(defn profile
  "Detect the terminal color profile for an output stream. A stream is the
   destination that will receive ANSI escapes: :stdout (the default) or :stderr.
   Choosing the stream matters because either destination may independently be a
   TTY or be redirected to a file or pipe.

   Options map (single argument, all keys optional):
     :stream       - :stdout (default) or :stderr
     :sniff-flags  - check CLI flags like --color (default true)
     :debug        - trace the detection logic to stdout (default false)
     :context      - a map whose entries override the live process reads.
                     Anything absent falls back to the running process. Two
                     keys are consulted:
                       :env     - env-var overrides (keys from env-var-keys),
                                  used instead of the real environment
                       :runtime - runtime overrides: :tty {:stdout :stderr
                                  :stdin}, :argv, and :os {:name :version
                                  :platform}, used instead of live TTY/argv/OS
                                  reads

   Returns a map with :level, :color-profile (:no-color, :basic, :ansi256, or
   :truecolor), :env, and :runtime. :env holds only the keys in env-var-keys.
   :runtime :argv holds only the color flags detection recognized, before any
   --, so the result can be logged without the program's other arguments."
  ([] (profile {}))
  ([{sniff-flags? :sniff-flags
     debug?       :debug
     :keys        [stream context]
     :or          {stream       :stdout
                   sniff-flags? true}
     :as          opts}]
   (let [context  (normalize-context context)
         mock-env (:env context)]
     (when debug?
       (dbg-header 0 "profile")
       (dbg 0 "stream: " (pr-str stream))
       (dbg 0 "sniff-flags: " (pr-str sniff-flags?))
       ;; Only the known keys: a caller's :env can hold anything, secrets included.
       (when mock-env (dbg 0 "mock-env: " (pr-str (select-keys mock-env env-var-keys)))))
     (let [tty?  (case stream :stderr (tty?* context :stderr) (tty?* context :stdout))
           _     (when debug? (dbg 0 "tty?: " (pr-str tty?)) (dbg-blank 0))
           level (detect-level {:stream-tty  tty?
                                :sniff-flags sniff-flags?
                                :debug       debug?
                                :context     context}
                               mock-env)
           ctx   (collect-context opts mock-env context)]
       (when debug?
         (dbg-blank 0)
         (dbg 0 "level: " level)
         (dbg-close 0))
       (merge ctx
              {:level         level
               :color-profile (level->profile level)})))))

;; ---------------------------------------------------------------------------
;; Step 1 of dark/light chain: CLITHEME env var
;; ---------------------------------------------------------------------------

(defn- clitheme-dark?
  "Check CLITHEME env var for an explicit user preference.
   Returns :dark, :light, or nil (meaning 'auto' / not set)."
  ([] (clitheme-dark? nil))
  ([mock-env]
   (when-let [v (get-env "CLITHEME" mock-env)]
     ;; A limit of 2 keeps trailing empty fields, so ":" gives ["" ""] rather
     ;; than an empty array whose first is nil.
     (let [theme (some-> v (str/split #":" 2) first str/trim lower-case-ascii)]
       (case theme
         "dark"  :dark
         "light" :light
         nil)))))

;; ---------------------------------------------------------------------------
;; Step 2 of dark/light chain: CSI ?996 query
;; ---------------------------------------------------------------------------

(defn- parse-csi996-response
  "Parse the raw bytes read after a CSI ?996 query.
   Returns :dark, :light, or nil (unsupported/timeout)."
  [^String s]
  (when s
    (cond
      (str/includes? s "[?997;1n") :dark
      (str/includes? s "[?997;2n") :light
      :else nil)))

;; ---------------------------------------------------------------------------
;; Step 3 of dark/light chain: OSC 11 query
;; ---------------------------------------------------------------------------

(defn- parse-osc-color-response
  "Parses an OSC 11 terminal response string into an {:r :g :b} map, or nil."
  [^String s]
  (when s
    ;; Groups are bounded to {1,4}: a conforming XParseColor component is 1-4 hex
    ;; digits, so an over-long field (e.g. a hostile "ffffffff") no longer matches
    ;; and the whole reply falls through to nil rather than reaching parseInt.
    (when-let [m (re-find #"rgb:([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})" s)]
      (let [[_ rs gs bs] m
            parse-ch (fn [hex]
                       ;; Defensive only: with the bounded regex above this parse
                       ;; cannot fail, but wrap it so any future change returns nil
                       ;; instead of throwing NumberFormatException out of a public fn.
                       (let [n    #?(:clj  (try (Integer/parseInt hex 16)
                                                (catch Exception _ nil))
                                     :cljs (let [x (js/parseInt hex 16)]
                                             (when-not (js/isNaN x) x)))
                             bits (* 4 (count hex))]
                         (when n
                           ;; Scale the component to 8 bits by proportion rather
                           ;; than shifting by (- bits 8), which is negative for a
                           ;; single-hex-digit (4-bit) field and silently
                           ;; produced a wrong value. quot keeps this integer.
                           (quot (* n 255) (dec (bit-shift-left 1 bits))))))]
        {:r (parse-ch rs) :g (parse-ch gs) :b (parse-ch bs)}))))

;; ---------------------------------------------------------------------------
;; Step 4 of dark/light chain: COLORFGBG env var
;; ---------------------------------------------------------------------------

(defn- colorfgbg-dark?
  "Parses COLORFGBG env var. Returns true if background index < 7, false if >= 7,
   and nil if unset or when the last ;-separated field is not ASCII digits
   (\"default\", or empty as in \"15;\")."
  ([] (colorfgbg-dark? nil))
  ([mock-env]
   (when-let [v (get-env "COLORFGBG" mock-env)]
     ;; -1 keeps a trailing empty field. Without it both runtimes would drop it.
     (when-let [n (parse-decimal (last (str/split v #";" -1)))]
       (< n 7)))))

;; ---------------------------------------------------------------------------
;; Shared utilities
;; ---------------------------------------------------------------------------


(defn- rgb->luma
  "Computes perceived luminance (0.0-1.0) from an 8-bit {:r :g :b} map.
   The BT.601 coefficients are a standard weighted approximation of human
   brightness perception: green contributes most, red next, and blue least."
  [{:keys [r g b]}]
  (+ (* 0.299 (/ r 255.0))
     (* 0.587 (/ g 255.0))
     (* 0.114 (/ b 255.0))))

(defn- format-luma
  "Formats a luma float to 3 decimal places for debug output."
  [luma]
  #?(:clj  (format "%.3f" (double luma))
     :cljs (.toFixed luma 3)))

(defn- ->polarity
  "Convert a dark? signal (true/false/nil) to a polarity string. true is \"dark\",
   false is \"light\", and nil (no signal) stays nil so callers can tell an
   undetected background from a detected one."
  [dark?]
  (case dark? true "dark" false "light" nil))

;; ---------------------------------------------------------------------------
;; Background detection chain
;; ---------------------------------------------------------------------------

(defn- run-dark-light-chain
  "Run the full detection chain synchronously. Returns a result map with:
     :polarity              - \"dark\", \"light\", or nil (no signal)
     :background-color  - {:r :g :b} or nil (from OSC 11 only)
     :detection-method - which step succeeded
     :csi996-result    - :dark, :light, or nil
     :csi996-supported - whether CSI ?996 was answered
     :osc11-supported  - whether OSC 11 was answered
     :clitheme         - parsed CLITHEME or nil

   `raw` is the reply buffer of the one combined terminal query (CSI ?996n,
   OSC 11, DA1), or nil when no query ran. Steps 2 and 3 both read it. `skip` is
   the reason no query ran, for the debug trace."
  [timeout-ms debug? context raw skip]
  (let [mock-env  (:env context)
        clitheme  (clitheme-dark? mock-env)
        cfg-dark  (colorfgbg-dark? mock-env)]

    (when debug?
      (dbg-header 0 "background detection")
      (dbg 0 "timeout-ms: " timeout-ms)
      (dbg-blank 0))

    (let [result
          (if (some? clitheme)
            ;; Step 1: CLITHEME - user override, done
            (do
              (when debug?
                (dbg-header 1 "Step 1: CLITHEME")
                (dbg 1 "CLITHEME → " (pr-str clitheme))
                (dbg 1 "★ " (name clitheme))
                (dbg-close 1))
              {:polarity                       (->polarity (= clitheme :dark))
               :background-color           nil
               :detection-method          :clitheme
               :clitheme                  clitheme
               :csi996-result             nil
               :csi996-supported          false
               :osc11-supported           false})

            (do
              (when debug?
                (dbg-header 1 "Step 1: CLITHEME")
                (dbg 1 "CLITHEME: nil → skip")
                (dbg-close 1)
                (dbg-blank 0))

              ;; Step 2: CSI ?996
              (let [csi996-raw raw
                    csi996     (parse-csi996-response csi996-raw)]

                (if (some? csi996)
                  (do
                    (when debug?
                      (dbg-header 1 "Step 2: CSI ?996")
                      (dbg 1 "raw: " (dbg-raw csi996-raw))
                      (dbg 1 "parsed: " (pr-str csi996))
                      (dbg 1 "★ " (name csi996))
                      (dbg-close 1))
                    {:polarity                       (->polarity (= csi996 :dark))
                     :background-color           nil
                     :detection-method          :csi996
                     :clitheme                  nil
                     :csi996-result             csi996
                     :csi996-supported          true
                     :osc11-supported           false})

                  (do
                    (when debug?
                      (dbg-header 1 "Step 2: CSI ?996")
                      (when skip (dbg 1 "query skipped: " skip))
                      (dbg 1 "raw: " (dbg-raw csi996-raw))
                      (dbg 1 "parsed: nil → skip")
                      (dbg-close 1)
                      (dbg-blank 0))

                    ;; Step 3: OSC 11
                    (let [osc11-raw raw
                          rgb       (parse-osc-color-response osc11-raw)]

                      (if (some? rgb)
                        (let [luma (rgb->luma rgb)]
                          (when debug?
                            (dbg-header 1 "Step 3: OSC 11")
                            (dbg 1 "raw: " (dbg-raw osc11-raw))
                            (dbg 1 "RGB: " (pr-str rgb))
                            (dbg 1 "luma: " (format-luma luma)
                                 (if (<= luma 0.5) " (≤ 0.5 → dark)" " (> 0.5 → light)"))
                            (dbg 1 "★ " (if (<= luma 0.5) "dark" "light"))
                            (dbg-close 1))
                          {:polarity                       (->polarity (<= luma 0.5))
                           :background-color           rgb
                           :detection-method          :osc11
                           :clitheme                  nil
                           :csi996-result             nil
                           :csi996-supported          false
                           :osc11-supported           true})

                        ;; Step 4: COLORFGBG
                        (do
                          (when debug?
                            (dbg-header 1 "Step 3: OSC 11")
                            (when skip (dbg 1 "query skipped: " skip))
                            (dbg 1 "raw: " (dbg-raw osc11-raw))
                            (dbg 1 "parsed: nil → skip")
                            (dbg-close 1)
                            (dbg-blank 0)
                            (dbg-header 1 "Step 4: COLORFGBG")
                            (dbg 1 "COLORFGBG dark?: " (pr-str cfg-dark))
                            (if (some? cfg-dark)
                              (do (dbg 1 "★ " (if cfg-dark "dark" "light"))
                                  (dbg-close 1))
                              (do (dbg 1 "· no result")
                                  (dbg-close 1))))
                          {:polarity                       (->polarity cfg-dark)
                           :background-color           nil
                           :detection-method          (if (some? cfg-dark) :colorfgbg :none)
                           :clitheme                  nil
                           :csi996-result             nil
                           :csi996-supported          false
                           :osc11-supported           false}))))))))]

      (when debug?
        (dbg-blank 0)
        (dbg 0 "polarity: " (pr-str (:polarity result)))
        (dbg 0 "detection-method: " (pr-str (:detection-method result)))
        (when-let [rgb (:background-color result)]
          (dbg 0 "background-color: " (pr-str rgb)))
        (dbg-close 0))

      result)))

;; ---------------------------------------------------------------------------
;; Input validation
;; ---------------------------------------------------------------------------

(defn- warn
  "Print a one-line warning to stderr on both the JVM and Node."
  [& parts]
  (let [msg (escape-controls (apply str "[supports-color] " parts))]
    #?(:clj  (binding [*out* *err*] (println msg))
       :cljs (binding [*print-fn* *print-err-fn*] (println msg)))))

(def ^:private min-timeout-ms 0)
(def ^:private max-timeout-ms 30000)
(def ^:private default-timeout-ms 2000)

(defn- sanitize-timeout-ms
  "Coerce a caller-supplied :timeout-ms into a usable millisecond count before it
   reaches any terminal query. A valid value is a finite number in
   [0, 30000]; a numeric value outside that range is clamped to the nearest bound,
   and a non-numeric or NaN value falls back to the 2000ms default. Every coercion
   warns on stderr so a config typo surfaces instead of silently producing a
   wrecked terminal or a non-terminating read loop.

   Clamp-and-warn rather than throw keeps the library's contract that a bad input
   degrades gracefully instead of raising out of a public detection call."
  [timeout-ms]
  (cond
    (not (number? timeout-ms))
    (do (warn "ignoring non-numeric :timeout-ms " (pr-str timeout-ms)
              "; using " default-timeout-ms "ms")
        default-timeout-ms)

    ;; NaN is the only value not equal to itself, so this excludes it portably
    ;; without a JVM-only Double/isNaN.
    (not (== timeout-ms timeout-ms))
    (do (warn "ignoring NaN :timeout-ms; using " default-timeout-ms "ms")
        default-timeout-ms)

    (< timeout-ms min-timeout-ms)
    (do (warn ":timeout-ms " (pr-str timeout-ms) " below " min-timeout-ms
              "; clamping to " min-timeout-ms "ms")
        min-timeout-ms)

    (> timeout-ms max-timeout-ms)
    (do (warn ":timeout-ms " (pr-str timeout-ms) " above " max-timeout-ms
              "; clamping to " max-timeout-ms "ms")
        max-timeout-ms)

    ;; Finite and in range: floor a fractional millisecond to an integer.
    :else (int timeout-ms)))

;; ---------------------------------------------------------------------------
;; Public API - background color
;; ---------------------------------------------------------------------------

(defn- query-skip-reason
  "Why `background` sends no terminal query, or nil when it sends one.

   - Windows: no /dev/tty or stty.
   - No terminal on stdout or stderr: the stty spawns can only fail.
   - SUPPORTS_COLOR_NO_QUERY set to a non-empty value: the user's off switch.
   - TERM unset or `dumb` (Emacs M-x shell, some CI ptys): the terminal is not
     expected to answer, and the queries would show up as text."
  [context]
  (let [mock-env (:env context)
        term     (non-blank (get-env "TERM" mock-env))]
    (cond
      (= (platform* context) :win32)                           "Windows"
      (not (have-terminal? context))                           "no terminal on stdout or stderr"
      (non-blank (get-env "SUPPORTS_COLOR_NO_QUERY" mock-env)) "SUPPORTS_COLOR_NO_QUERY is set"
      (nil? term)                                              "TERM is unset"
      (= "dumb" term)                                          "TERM=dumb"
      :else                                                    nil)))

(defn- detect-background
  "Shared body of background and theme. When theme-only? is true, a CLITHEME of
   dark or light skips the terminal query, and :background-color is left out of
   the result."
  [{debug? :debug
    :keys  [timeout-ms context]
    :or    {timeout-ms default-timeout-ms}
    :as    opts}
   theme-only?]
  (if (= :browser (host))
    ;; A browser has no terminal to query. Its color scheme preference is the
    ;; only signal.
    (let [context  (normalize-context context)
          polarity (->polarity (some-> (:prefers-dark @pf) (apply [])))]
      (when debug?
        (dbg-header 0 "background detection")
        (dbg 0 "browser, prefers-color-scheme → " (pr-str polarity)))
      (merge (collect-context opts (:env context) context)
             (cond-> {:polarity         polarity
                      :background-color nil
                      :detection-method (if polarity :prefers-color-scheme :none)
                      :csi996-result    nil
                      :csi996-supported false
                      :osc11-supported  false
                      :clitheme         nil}
               theme-only? (dissoc :background-color))))
  (let [context    (normalize-context context)
        ;; An explicit nil (a config lookup that missed) means "not given".
        timeout-ms (sanitize-timeout-ms (if (nil? timeout-ms) default-timeout-ms timeout-ms))
        mock-env   (:env context)
        skip       (or (when (and theme-only? (some? (clitheme-dark? mock-env)))
                         "CLITHEME is set")
                       (query-skip-reason context))
        ;; One combined terminal query (CSI ?996n, OSC 11, DA1) in one window.
        raw        (when-not skip ((:query-terminal @pf) timeout-ms))
        chain      (run-dark-light-chain timeout-ms debug? context raw skip)
        ;; The colour comes from the same reply buffer whichever step decided
        ;; the polarity (CLITHEME and CSI ?996 included), so no second query.
        rgb        (parse-osc-color-response raw)
        chain      (assoc chain
                          :background-color rgb
                          :osc11-supported (some? rgb))]
    (merge (collect-context opts mock-env context)
           (cond-> chain theme-only? (dissoc :background-color))))))

(defn background
  "Detect everything about the terminal's background: its dark/light polarity
   and its literal color, in one call. This is the terminal-touching companion
   to profile: where profile only reads env vars and TTY status, background may
   put the TTY into raw mode and issue escape-sequence queries, so it can block
   up to :timeout-ms and is skipped entirely when there is no controlling
   terminal (CI, daemons, redirected output).

   Detection chain for polarity (in order, first answer wins):
     1. CLITHEME env var  - user override
     2. CSI ?996 query    - OS theme preference (Contour, Ghostty, Kitty, etc.)
     3. OSC 11 query      - actual background RGB → luma
     4. COLORFGBG env var - coarse fallback

   :background-color is filled whenever the terminal supports OSC 11. The
   CSI ?996 and OSC 11 queries go out together with a DA1 sentinel in one raw
   window, and every reply is read from the same buffer, so whichever step
   decides the polarity, at most one query is sent per call.

   Returns a map with :polarity (\"dark\", \"light\", or nil when no signal),
   :background-color ({:r :g :b} or nil), plus :env, :runtime, and detection
   metadata (:detection-method, :clitheme, :csi996-result, :csi996-supported,
   :osc11-supported). :env and :runtime :argv are trimmed as in profile.

   Options (single argument):
     :timeout-ms - how long to wait for terminal queries (default 2000). The
                   read ends as soon as the terminal answers, so this only runs
                   out on a terminal that does not answer. A reply that has
                   started gets one more :timeout-ms to finish.
     :debug      - trace the detection chain to stdout (default false)
     :context    - a map whose entries override the live process reads.
                   Anything absent falls back to the running process. Two keys
                   are consulted:
                     :env     - env-var overrides (keys from env-var-keys);
                                drives the CLITHEME and COLORFGBG steps
                     :runtime - runtime overrides: :tty {:stdout :stderr
                                :stdin}, :argv, and :os {:name :version
                                :platform}. An injected non-terminal :tty skips
                                the CSI ?996 and OSC 11 queries."
  ([] (background {}))
  ([opts] (detect-background opts false)))

(defn theme
  "Detect the terminal's dark/light polarity only. Same detection chain and
   options as background, but when CLITHEME is \"dark\" or \"light\" no terminal
   query is sent, so a user who sets CLITHEME pays no query cost. Use this when
   you need polarity and not the literal background color.

   Returns the same map as background without :background-color."
  ([] (theme {}))
  ([opts] (detect-background opts true)))
