(ns supports-color.query-skip-test
  "In-process tests for when `background` skips its terminal query, and for input
   that must not throw or leak (2026-11-08 audit, C-06, C-09, D-01, D-02, D-07,
   D-11).

     off switch  SUPPORTS_COLOR_NO_QUERY set to a non-empty value skips the query.
     dumb tty    TERM=dumb (Emacs M-x shell) or no TERM at all skips the query.
     CLITHEME    separators only (\":\", \"::\") do not throw, and LIGHT is read as
                 light under a Turkish default locale.
     debug trace escapes control bytes in every value and prints only the known
                 env keys of the caller's :env.
     :context    malformed pieces are ignored instead of thrown on.

   Everything runs against the synthetic platform from `supports-color.support`,
   whose query fn is replaced by a recorder, so nothing touches a terminal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.core :as sc]
            [supports-color.harness :as h]
            [supports-color.support :as s]))

(defn- queried?
  "Whether `background` called the platform's query fn for env `overrides`, on a
   synthetic unix platform where every descriptor is a terminal."
  [overrides]
  (let [calls (atom 0)]
    (s/with-platform {:query-terminal (fn [_] (swap! calls inc) nil)}
      (sc/background {:context {:env (s/full-env overrides)}}))
    (pos? @calls)))

;; --- fix 10: off switch and dumb terminals ------------------------------------

(deftest off-switch-is-a-known-env-key
  (is (some #{"SUPPORTS_COLOR_NO_QUERY"} sc/env-var-keys)
      "SUPPORTS_COLOR_NO_QUERY is read like every other env key"))

(deftest query-runs-on-a-normal-terminal
  (is (queried? {"TERM" "xterm-256color"}) "control: a normal TERM is queried"))

(deftest off-switch-skips-the-query
  (testing "SUPPORTS_COLOR_NO_QUERY set to a non-empty value skips the query"
    (is (not (queried? {"TERM" "xterm-256color" "SUPPORTS_COLOR_NO_QUERY" "1"})))
    (is (queried? {"TERM" "xterm-256color" "SUPPORTS_COLOR_NO_QUERY" ""})
        "an empty value counts as unset")))

(deftest dumb-or-missing-term-skips-the-query
  (testing "TERM=dumb or no TERM at all skips the query"
    (is (not (queried? {"TERM" "dumb"})) "TERM=dumb")
    (is (not (queried? {})) "TERM unset")))

;; --- fix 11: input hygiene -----------------------------------------------------

(def ^:private no-tty {:tty {:stdout false :stderr false :stdin false}})

(defn- background-with-env [env]
  (try (s/with-platform {}
         (sc/background {:context {:env (s/full-env env) :runtime no-tty}}))
       (catch Throwable t {:threw (str t)})))

(deftest clitheme-separators-do-not-throw
  (testing "a CLITHEME of only separators is not a theme, and does not throw"
    (doseq [v [":" "::" ":::" " : "]]
      (let [r (background-with-env {"CLITHEME" v})]
        (is (nil? (:threw r)) (str "CLITHEME " (pr-str v) " threw " (:threw r)))
        (is (not= :clitheme (:detection-method r)))))))

(deftest clitheme-light-under-a-turkish-locale
  (testing "CLITHEME=LIGHT means light under a tr_TR default locale (I lowercases
            to a dotless i there)"
    (if (h/jolt?)
      ;; Jolt has no java.util.Locale. The form below is built with eval so Jolt
      ;; never has to analyze it.
      (do (println "  [skip] no java.util.Locale on this runtime") (is true))
      (let [r (eval
               '(let [old (java.util.Locale/getDefault)]
                  (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr-TR"))
                  (try
                    (supports-color.support/with-platform {}
                      (supports-color.core/background
                       {:context {:env (supports-color.support/full-env {"CLITHEME" "LIGHT"})
                                  :runtime {:tty {:stdout false :stderr false}}}}))
                    (finally (java.util.Locale/setDefault old)))))]
        (is (= :clitheme (:detection-method r)))
        (is (= "light" (:polarity r)))))))

(deftest debug-trace-escapes-values-and-hides-extra-keys
  (testing "control bytes in env values are escaped in the trace, and keys outside
            env-var-keys are not printed"
    (let [esc (str (char 27))
          bel (str (char 7))
          out (with-out-str
                (s/with-platform {}
                  (sc/profile {:debug true
                               :sniff-flags false
                               :context {:env (s/full-env {"TERM" (str "xterm" esc "]0;t" bel)
                                                           "MY_SECRET" "s3cr3t"})}})))]
      (is (not (str/includes? out esc)) "no raw ESC in the trace")
      (is (not (str/includes? out bel)) "no raw BEL in the trace")
      (is (not (str/includes? out "s3cr3t")) "an unknown :env key is not printed"))))

(deftest malformed-context-does-not-throw
  (testing "each malformed :context piece is ignored, not thrown on (audit D-11)"
    (doseq [ctx [{:env "x"} {:env 5} {:env true} {:env '("a")} {:env []} {:env false}
                 {:env #{"NO_COLOR"}} {:env (System/getenv)}
                 {:runtime {:argv 5}} {:runtime {:argv :kw}} {:runtime {:argv "--no-color"}}
                 {:runtime {:tty {:stdout "false"}}}
                 {:runtime {:os {:platform :win32 :version nil}}}
                 {:runtime {:os {:platform :win32 :version 10}}}
                 {:runtime {:os {:platform "linux"}}}]]
      (let [ctx (update ctx :runtime #(merge no-tty %))
            r (try (s/with-platform {}
                     [(sc/profile {:context ctx}) (sc/background {:context ctx})])
                   (catch Throwable t (str "THROWS " t)))]
        (is (vector? r) (str (pr-str (dissoc ctx :env)) " -> " r))))))

(deftest get-env-with-a-non-string-key-is-nil
  (is (nil? (try (sc/get-env nil) (catch Throwable t (str t)))))
  (is (nil? (try (sc/get-env :TERM) (catch Throwable t (str t))))))

(deftest nil-timeout-is-absent-not-a-warning
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (s/with-platform {}
        (sc/background {:timeout-ms nil :context {:runtime no-tty}})))
    (is (= "" (str err)) "no warning for a nil :timeout-ms")))
