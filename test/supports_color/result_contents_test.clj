(ns supports-color.result-contents-test
  "What `profile` and `background` return must not carry the caller's data back
   out (2026-11-08 audit, #23 / D-08).

     :env   holds only the keys in env-var-keys, never other keys the caller
            put in :context :env.
     :argv  holds only the color flags detection recognized, before any `--`,
            in argv order. Other arguments (`--token=...`, paths) are dropped.

   A result fed back as :context still resolves to the same level.

   Everything runs against the synthetic platform from `supports-color.support`,
   so nothing touches a terminal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.core :as sc]
            [supports-color.support :as s]))

(def ^:private argv ["--token=abc123" "--color=256" "/home/someone/app.clj" "--" "--no-color"])

(def ^:private context
  {:env     (s/full-env {"MY_TOKEN" "s3cr3t" "TERM" "xterm-256color"})
   :runtime {:tty  {:stdout false :stderr false :stdin false}
             :argv argv}})

(defn- results []
  (s/with-platform {}
    {:profile    (sc/profile {:context context})
     :background (sc/background {:context context})}))

(deftest env-holds-only-known-keys
  (doseq [[fn-name r] (results)]
    (testing (name fn-name)
      (is (= (set sc/env-var-keys) (set (keys (:env r))))
          ":env has exactly the env-var-keys")
      (is (not (str/includes? (pr-str r) "s3cr3t"))
          "an unknown :env value is not in the result")
      (is (not (str/includes? (pr-str r) "MY_TOKEN"))
          "an unknown :env key is not in the result"))))

(deftest argv-holds-only-color-flags
  (doseq [[fn-name r] (results)]
    (testing (name fn-name)
      (is (= ["--color=256"] (get-in r [:runtime :argv]))
          "only recognized color flags before -- are kept")
      (is (not (str/includes? (pr-str r) "abc123"))
          "other arguments are not in the result")
      (is (not (str/includes? (pr-str r) "/home/someone"))
          "paths are not in the result"))))

(deftest argv-keeps-flag-order
  (s/with-platform {}
    (is (= ["--no-color" "--color"]
           (get-in (sc/profile {:context {:env     (s/full-env {})
                                          :runtime {:argv ["x" "--no-color" "y" "--color"]}}})
                   [:runtime :argv])))))

(deftest result-replays-to-the-same-level
  (s/with-platform {}
    (let [r      (sc/profile {:context context})
          replay (sc/profile {:context (select-keys r [:env :runtime])})]
      (is (= 2 (:level r)) "control: --color=256 gives level 2")
      (is (= (:level r) (:level replay))))))

;; --- no map key ends in ? -------------------------------------------------------

(defn- question-keys
  "Every keyword key, at any depth, whose name ends in ?."
  [m]
  (->> (tree-seq map? vals m)
       (filter map?)
       (mapcat keys)
       (filter #(and (keyword? %) (str/ends-with? (name %) "?")))
       set))

(deftest no-result-key-ends-in-question-mark
  (doseq [[fn-name r] (results)]
    (testing (name fn-name)
      (is (= #{} (question-keys r))))))

(deftest tty-context-keys-have-no-question-mark
  (let [calls (atom 0)]
    (s/with-platform {:query-terminal (fn [_] (swap! calls inc) nil)}
      (let [r (sc/background {:context {:env     (s/full-env {"TERM" "xterm-256color"})
                                        :runtime {:tty {:stdout false :stderr false :stdin false}}}})]
        (is (zero? @calls) ":tty {:stdout false :stderr false} skips the query")
        (is (= {:stdout false :stderr false :stdin false} (get-in r [:runtime :tty])))))))

(deftest option-keys-have-no-question-mark
  (s/with-platform {}
    (let [ctx {:context {:env (s/full-env {}) :runtime {:argv ["--no-color"]}}}]
      (is (= 0 (:level (sc/profile ctx))) "control: --no-color is read by default")
      (is (= false (get-in (sc/profile (assoc ctx :sniff-flags false)) [:runtime :flags :sniff-flags]))
          ":sniff-flags false turns flag reading off")
      (is (= "" (with-out-str (sc/profile ctx)))
          "control: no trace without :debug")
      (is (not= "" (with-out-str (sc/profile (assoc ctx :debug true))))
          ":debug true prints a trace"))))
