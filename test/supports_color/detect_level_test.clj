(ns supports-color.detect-level-test
  "In-process table-driven coverage of the env/flag/TTY/CI precedence tree in
   detect-level, driven entirely in-process through the platform adapter. Each row
   pins one rule (or one precedence ordering between rules) to its documented
   level. Runs identically under the JVM and Babashka."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [supports-color.core :as sc]
            [supports-color.support :as sup]))

;; Every test runs under a synthetic unix adapter whose stdout/stderr/stdin are
;; TTYs, so env-var rules are reached. tty/windows tests override within.
(use-fixtures :each (fn [f] (sup/with-platform* {} f)))

(deftest env-var-precedence
  (testing "the ordered heuristic chain resolves each rule to its documented level"
    (are [expected env]
        (= expected (sup/level env))
      ;; 1 NO_COLOR beats the FORCE_COLOR env var. Command-line flags are checked
      ;; before it, see flags-override-no-color.
      0 {"NO_COLOR" "1" "FORCE_COLOR" "3"}
      ;; 2 FORCE_COLOR sets the level directly and beats terminal heuristics.
      ;; force-color.org: any non-empty value forces color. "0" and "false" keep
      ;; chalk's meaning (off), and "1" to "3" pick the level.
      2 {"FORCE_COLOR" "2"}
      3 {"FORCE_COLOR" "3"}
      1 {"FORCE_COLOR" "1"}
      1 {"FORCE_COLOR" "true"}
      0 {"FORCE_COLOR" "false"}
      0 {"FORCE_COLOR" "0"}
      0 {"FORCE_COLOR" ""}           ; empty counts as unset (force-color.org)
      1 {"FORCE_COLOR" "yes"}        ; any other non-empty value forces basic color
      1 {"FORCE_COLOR" "5"}
      1 {"FORCE_COLOR" "-1"}
      1 {"FORCE_COLOR" "3 "}         ; exact match only, same on the JVM and Node
      ;; 5 Azure Pipelines
      1 {"TF_BUILD" "True" "AGENT_NAME" "agent"}
      ;; 7 dumb terminal
      0 {"TERM" "dumb"}
      1 {"TERM" "dumb" "FORCE_COLOR" "1"} ; force-color rescues dumb via rule 2
      ;; 11 COLORTERM truecolor/24bit
      3 {"COLORTERM" "truecolor"}
      3 {"COLORTERM" "24bit"}
      ;; 12 known truecolor terminals
      3 {"TERM" "xterm-kitty"}
      3 {"TERM" "wezterm"}
      ;; 13 TERM_PROGRAM
      3 {"TERM_PROGRAM" "iTerm.app" "TERM_PROGRAM_VERSION" "3.4"}
      2 {"TERM_PROGRAM" "iTerm.app" "TERM_PROGRAM_VERSION" "2.9"}
      2 {"TERM_PROGRAM" "Apple_Terminal"}
      ;; 9 CI providers
      3 {"CI" "1" "GITHUB_ACTIONS" "true"}
      3 {"CI" "1" "CIRCLECI" "true"}
      1 {"CI" "1" "TRAVIS" "1"}
      1 {"CI" "1" "GITLAB_CI" "1"}
      0 {"CI" "1"}                    ; CI, unknown provider, no override
      ;; 14 CLICOLOR_FORCE
      1 {"CLICOLOR_FORCE" "1"}
      0 {"CLICOLOR_FORCE" "0"}        ; "0" is inactive, falls through to fallback
      ;; 16 -256color suffix
      2 {"TERM" "xterm-256color"}
      2 {"TERM" "screen-256color"}
      ;; 17 basic TERM pattern
      1 {"TERM" "xterm"}
      1 {"TERM" "screen"}
      ;; 18 COLORTERM set (fallback)
      1 {"COLORTERM" "1"}
      ;; 19 final fallback
      0 {})))

(deftest teamcity-version-precedence
  (testing "TeamCity ≥9.x reports basic color, older reports none"
    (is (= 1 (sup/level {"TEAMCITY_VERSION" "2020.1"})))
    (is (= 0 (sup/level {"TEAMCITY_VERSION" "8.0"})))))

(deftest non-tty-precedence
  (testing "a non-TTY stream with no force override collapses to 0 before TERM rules"
    (sup/with-platform {:stdout-tty (sup/tty false)}
      (is (= 0 (sup/level {})))
      (is (= 0 (sup/level {"TERM" "xterm-256color"})) "TERM rules don't apply off-TTY")
      (is (= 0 (sup/level {"NO_COLOR" "1"})))
      (is (= 2 (sup/level {"FORCE_COLOR" "2"})) "an explicit override still wins off-TTY")
      (is (= 1 (sup/level {"FORCE_COLOR" "yes"})) "any non-empty value forces color off-TTY")
      (is (= 0 (sup/level {"FORCE_COLOR" ""})) "an empty FORCE_COLOR forces nothing")))
  (testing "CLICOLOR only grants color on a TTY"
    (sup/with-platform {:stdout-tty (sup/tty true)}
      (is (= 1 (sup/level {"CLICOLOR" "1"}))))
    (sup/with-platform {:stdout-tty (sup/tty false)}
      (is (= 0 (sup/level {"CLICOLOR" "1"}))))))

(deftest windows-version-detection
  (testing "the :win32 branch grades color by OS build number"
    (are [expected release]
        (= expected
           (sup/with-platform {:platform    (sup/returns :win32)
                               :os-release  (sup/returns release)
                               :os-name     (sup/returns "Windows")}
             (sup/level {})))
      3 "10.0.14931"   ; build ≥14931 → truecolor
      2 "10.0.10586"   ; build ≥10586 → 256
      1 "10.0.10240"   ; build <10586 → basic
      1 "6.1.7601")))  ; major <10 → basic

(deftest flag-sniffing
  (testing "CLI --color flags resolve when sniffing is enabled"
    (sup/with-platform {:argv (sup/returns ["--color=256"])}
      (is (= 2 (sup/level {} {:sniff-flags true}))))
    (sup/with-platform {:argv (sup/returns ["--color=truecolor"])}
      (is (= 3 (sup/level {} {:sniff-flags true}))))
    (sup/with-platform {:argv (sup/returns ["--no-color"])}
      (is (= 0 (sup/level {"FORCE_COLOR" "3"} {:sniff-flags true}))
          "a --no-color flag overrides FORCE_COLOR via flag force-color"))
    (testing "the same argv is inert when sniffing is disabled"
      (sup/with-platform {:argv (sup/returns ["--color=256"])}
        (is (= 0 (sup/level {} {:sniff-flags false})))))))

(deftest empty-value-counts-as-unset
  (testing "an empty presence flag is the same as an unset one (no-color.org:
            NO_COLOR counts when \"present and not an empty string\")"
    (are [expected env]
        (= expected (sup/level env))
      2 {"NO_COLOR" "" "TERM" "xterm-256color"}  ; empty NO_COLOR keeps color
      2 {"CI" "" "TERM" "xterm-256color"}        ; empty CI is not CI
      0 {"CLICOLOR_FORCE" ""}                    ; empty CLICOLOR_FORCE forces nothing
      2 {"FORCE_COLOR" "" "TERM" "xterm-256color"})) ; empty FORCE_COLOR is unset too
  (testing "the result's :env, read from the real environment, replays to the
            same level"
    (doseq [env [{"NO_COLOR" "" "TERM" "xterm-256color"}
                 {"FORCE_COLOR" "" "TERM" "xterm-256color"}
                 {"CI" "" "TERM" "xterm-256color"}]]
      (sup/with-platform {:real-get-env (fn [k] (get env k))}
        (let [r (sc/profile {:sniff-flags false})]
          (is (= (:level r)
                 (:level (sc/profile {:sniff-flags false :context {:env (:env r)}})))
              (str "replaying :env for " (pr-str env))))))))

(deftest flags-override-no-color
  (testing "per no-color.org, command-line arguments override NO_COLOR"
    (sup/with-platform {:argv (sup/returns ["--color=256"])}
      (is (= 2 (sup/level {"NO_COLOR" "1"} {:sniff-flags true}))))
    (sup/with-platform {:argv (sup/returns ["--color"])}
      (is (= 1 (sup/level {"NO_COLOR" "1"} {:sniff-flags true}))))
    (sup/with-platform {:argv (sup/returns ["--no-color"])}
      (is (= 0 (sup/level {"NO_COLOR" "1"} {:sniff-flags true})))))
  (testing "NO_COLOR still beats the FORCE_COLOR env var"
    (is (= 0 (sup/level {"NO_COLOR" "1" "FORCE_COLOR" "3"})))))

(defn- colorfgbg-polarity [v]
  ;; No terminal, so no query: COLORFGBG alone decides.
  (:polarity (sc/background {:context {:env     (sup/full-env {"COLORFGBG" v})
                                       :runtime {:tty {:stdout false :stderr false :stdin false}}}})))

(deftest numbers-parse-the-same-on-every-runtime
  ;; Audit #27: only the ASCII digits 0-9 count, on every runtime. The JVM's
  ;; Integer/parseInt took other scripts' digits, JS parseInt took a numeric
  ;; prefix or leading spaces, and Java's .split dropped a trailing empty field.
  (testing "COLORFGBG uses its last ; field, as written"
    (are [expected v] (= expected (colorfgbg-polarity v))
      "dark"  "15;0"
      "light" "0;15"
      nil     "15;"
      nil     "0;7abc"
      nil     "0; 7"
      nil     "0;٧"          ; Arabic-Indic seven
      nil     "0;default"))
  (testing "TERM_PROGRAM_VERSION major"
    (are [expected v] (= expected (sup/level {"TERM_PROGRAM" "iTerm.app" "TERM_PROGRAM_VERSION" v}))
      3 "3.4"
      2 "3abc"
      2 " 3"
      2 "٣"                  ; Arabic-Indic three
      2 "99999999999"))
  (testing "Windows build number"
    (are [expected release]
        (= expected
           (sup/with-platform {:platform   (sup/returns :win32)
                               :os-release (sup/returns release)
                               :os-name    (sup/returns "Windows")}
             (sup/level {})))
      3 "10.0.14931"
      1 "10.0.10586abc"
      1 "١٠.0.14931")))  ; Arabic-Indic ten
