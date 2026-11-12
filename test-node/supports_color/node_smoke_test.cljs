(ns supports-color.node-smoke-test
  "Node-only assertions that exercise, on the real Node runtime, the shared-code
   fixes that were JVM-only idioms:

   - parse-csi996-response now uses str/includes? (was .contains, which JS strings
     lack, so the Node build threw TypeError on any successful CSI ?996 reply).
   - sanitize-timeout-ms coerces a non-numeric :timeout-ms to a number before it
     reaches a terminal query (out_of_scope #1). The query once ran as a
     `node -e` script, where raw text could have been injected.

   The .split \"\\\\.\" -> str/split fix is covered on Node by
   supports-color.detect-level-test's windows-version-detection, run from the same
   nbb runner (test-node/runner.cljs)."
  (:require [clojure.test :refer [deftest is testing]]
            [supports-color.common]
            [supports-color.core :as sc]))

;; parse-csi996-response and sanitize-timeout-ms are private. Reach them by var.
(def parse-csi996 @(resolve 'supports-color.common/parse-csi996-response))
(def sanitize     @(resolve 'supports-color.common/sanitize-timeout-ms))

(deftest proc-stat-parse-runs-on-node
  (testing "proc-stat-foreground? compares pgrp and tpgid on Node"
    (if-let [v (resolve 'supports-color.common/proc-stat-foreground?)]
      (let [fg? @v]
        (is (true? (fg? "42 (x (y) 2 3) S 1 42 42 34817 42 0")))
        (is (false? (fg? "42 (cat) R 1 43 42 34817 42 0")))
        (is (false? (fg? "42 (cat) R 1 42 42 0 -1 0")))
        (is (nil? (fg? "42 (cat) R 1"))))
      (is false "supports-color.common/proc-stat-foreground? is not defined yet")))
  (testing "where /proc exists, Node reads its own stat line"
    (when (.existsSync (js/require "fs") "/proc/self/stat")
      (if-let [v (resolve 'supports-color.core/proc-self-stat)]
        (is (boolean? (@(resolve 'supports-color.common/proc-stat-foreground?) (@v))))
        (is false "supports-color.core/proc-self-stat is not defined yet")))))

(deftest csi996-parse-runs-on-node
  (testing "str/includes? branch runs on Node (was .contains -> TypeError)"
    (is (= :dark  (parse-csi996 "[?997;1n")))
    (is (= :light (parse-csi996 "[?997;2n")))
    (is (nil? (parse-csi996 "no terminator here")))
    (is (nil? (parse-csi996 nil)))))

(deftest timeout-is-coerced-to-a-number
  (testing "a non-integer :timeout-ms is coerced to a number before it reaches a
            terminal query"
    (is (number? (sanitize "100; console.log('pwned')")))
    (is (= 2000 (sanitize "100; console.log('pwned')")) "string falls back to default")
    (is (= 2000 (sanitize nil)))
    (is (= 2000 (sanitize ##NaN)))
    (is (number? (sanitize "abc"))))
  (testing "valid numeric timeouts pass through, clamped to [0, 30000] and floored"
    (is (= 250 (sanitize 250)))
    (is (= 100 (sanitize 100.9)) "fractional ms floored to int")
    (is (= 0 (sanitize -5)) "below range clamps to 0")
    (is (= 30000 (sanitize 999999)) "above range clamps to 30000")))

(deftest malformed-context-does-not-throw-on-node
  (testing "process.env passed as :env is read like a map (audit D-11)"
    (let [r (try (sc/profile {:context {:env (.-env js/process)
                                        :runtime {:tty {:stdout false}}}})
                 (catch :default e (str "THROWS " (.-message e))))]
      (is (map? r) (str "profile returned a result, got " (pr-str r)))))
  (testing "get-env with a non-string key returns nil"
    (is (nil? (sc/get-env nil)))
    (is (nil? (sc/get-env :TERM)))))

(deftest result-carries-no-caller-data-on-node
  (testing "process.env with an extra key, and a JS argv array (audit #23 / D-08)"
    (let [env (js/Object.assign #js {} (.-env js/process) #js {"MY_TOKEN" "s3cr3t"})
          ctx {:env     env
               :runtime {:tty  {:stdout false :stderr false :stdin false}
                         :argv #js ["/usr/local/bin/node" "--token=abc123" "--color=256" "--" "--no-color"]}}]
      (doseq [r [(sc/profile {:context ctx}) (sc/background {:context ctx})]]
        (is (= (set sc/env-var-keys) (set (keys (:env r)))))
        (is (= ["--color=256"] (get-in r [:runtime :argv])))
        (is (not (re-find #"s3cr3t|MY_TOKEN|abc123|/usr/local" (pr-str r))))
        (is (not (re-find #":[a-z0-9-]+\?" (pr-str (dissoc r :env))))
            "no result key ends in ?")))))
