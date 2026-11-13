(ns supports-color.theme-test
  "In-process tests for `theme`, the polarity-only companion to `background`.

     CLITHEME    dark or light decides the polarity and sends no terminal query.
     otherwise   theme queries like background, and agrees with it.
     result      theme returns no :background-color.

   Everything runs against the synthetic platform from `supports-color.support`,
   whose query fn is replaced by a recorder, so nothing touches a terminal."
  (:require [clojure.test :refer [deftest is testing]]
            [supports-color.core :as sc]
            [supports-color.support :as s]))

(defn- run-counting
  "Calls f (sc/theme or sc/background) with env `overrides` on a synthetic unix
   platform where every descriptor is a terminal. Returns the result and the
   number of terminal queries sent."
  [f overrides]
  (let [calls  (atom 0)
        result (s/with-platform {:query-terminal (fn [_] (swap! calls inc) nil)}
                 (f {:context {:env (s/full-env overrides)}}))]
    {:result result
     :calls  @calls}))

(deftest clitheme-skips-the-query
  (doseq [v ["dark" "light"]]
    (testing (str "CLITHEME=" v)
      (let [{:keys [result calls]} (run-counting sc/theme {"TERM"     "xterm-256color"
                                                           "CLITHEME" v})]
        (is (= v (:polarity result)))
        (is (= :clitheme (:detection-method result)))
        (is (zero? calls) "no terminal query")))))

(deftest clitheme-modifiers-still-skip-the-query
  (let [{:keys [result calls]} (run-counting sc/theme {"TERM"     "xterm-256color"
                                                       "CLITHEME" "light:high-contrast"})]
    (is (= "light" (:polarity result)))
    (is (zero? calls))))

(deftest auto-or-unset-clitheme-queries
  (testing "CLITHEME=auto does not decide the polarity, so theme queries"
    (is (= 1 (:calls (run-counting sc/theme {"TERM"     "xterm-256color"
                                             "CLITHEME" "auto"})))))
  (testing "no CLITHEME, so theme queries"
    (is (= 1 (:calls (run-counting sc/theme {"TERM" "xterm-256color"}))))))

(deftest background-still-queries-with-clitheme
  (testing "background fills :background-color, so it queries even with CLITHEME set (expected)"
    (is (= 1 (:calls (run-counting sc/background {"TERM"     "xterm-256color"
                                                  "CLITHEME" "dark"}))))))

(deftest theme-agrees-with-background
  (doseq [env [{"TERM" "xterm-256color" "CLITHEME" "dark"}
               {"TERM" "xterm-256color" "CLITHEME" "light"}
               {"TERM" "xterm-256color" "COLORFGBG" "15;0"}
               {"TERM" "xterm-256color" "COLORFGBG" "0;15"}
               {"TERM" "xterm-256color"}]]
    (testing (pr-str env)
      (is (= (:polarity (:result (run-counting sc/background env)))
             (:polarity (:result (run-counting sc/theme env))))))))

(deftest theme-has-no-background-color
  (let [{:keys [result]} (run-counting sc/theme {"TERM" "xterm-256color"})]
    (is (not (contains? result :background-color)))
    (is (contains? result :polarity))))
