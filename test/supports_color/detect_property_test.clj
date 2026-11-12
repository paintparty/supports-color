(ns supports-color.detect-property-test
  "In-process property/fuzz coverage. Where the table test pins individual rules,
   these assert invariants that must hold across the whole input space: the
   resolver never throws and always returns a valid level, NO_COLOR always wins,
   an in-range FORCE_COLOR always sets the level, and detection is deterministic.
   The fuzz feeds malformed env values (bad FORCE_COLOR, junk versions, odd
   TERMs) and random OS/TTY adapters, which is exactly where the parsing branches
   (Windows build, TeamCity, TERM_PROGRAM_VERSION) would otherwise blow up."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [supports-color.support :as sup]))

;; --- generators ------------------------------------------------------------

(def gen-env-value
  "A value for one env var: often unset (nil), otherwise a mix of meaningful and
   deliberately malformed strings."
  (gen/one-of
   [(gen/return nil)
    (gen/elements ["" "0" "1" "2" "3" "true" "false" "never" "always"
                   "truecolor" "24bit" "abc" "-1" "99" "5" "9.1.0" "2020.1"
                   "iTerm.app" "Apple_Terminal" "xterm-256color" "xterm"
                   "screen" "dumb" "xterm-kitty" "3.4" "2.0"])
    gen/string-alphanumeric]))

(def gen-env
  "A full mock env: every known key present with a random value."
  (apply gen/hash-map (mapcat (fn [k] [k gen-env-value]) sup/env-keys)))

(def gen-adapter
  "Random OS / TTY / argv overrides for the platform adapter."
  (gen/hash-map
   :platform   (gen/elements [(constantly :unix) (constantly :macos) (constantly :win32)])
   :os-release (gen/elements (map constantly
                                  ["0.0.0" "10.0.14931" "10.0.10586" "10.0.10240"
                                   "6.1.7601" "10.0" "" "abc" "10.0.x.y"]))
   :stdout-tty (gen/elements [(constantly true) (constantly false)])
   :stderr-tty (gen/elements [(constantly true) (constantly false)])))

(defn- level-under [adapter env opts]
  (sup/with-platform* adapter #(sup/level env opts)))

;; --- properties ------------------------------------------------------------

(defspec level-is-always-valid-and-never-throws 400
  (prop/for-all [env gen-env
                 adapter gen-adapter
                 sniff? gen/boolean]
    (contains? #{0 1 2 3} (level-under adapter env {:sniff-flags sniff?}))))

(defspec no-color-always-wins 200
  (prop/for-all [env gen-env
                 adapter gen-adapter]
    (zero? (level-under adapter (assoc env "NO_COLOR" "1") {:sniff-flags false}))))

(defspec in-range-force-color-sets-the-level 200
  (prop/for-all [env gen-env
                 adapter gen-adapter
                 n (gen/choose 0 3)]
    ;; With NO_COLOR cleared and flag sniffing off, an in-range FORCE_COLOR is
    ;; rule 2 and wins over everything downstream regardless of the rest of env.
    (= n (level-under adapter
                      (assoc env "NO_COLOR" nil "FORCE_COLOR" (str n))
                      {:sniff-flags false}))))

(defspec detection-is-deterministic 200
  (prop/for-all [env gen-env
                 adapter gen-adapter]
    (= (level-under adapter env {:sniff-flags false})
       (level-under adapter env {:sniff-flags false}))))

(deftest generators-are-wired
  (is (contains? #{0 1 2 3} (level-under {} (sup/full-env {}) {:sniff-flags false}))))
