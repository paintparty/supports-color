(ns supports-color.core-test
  (:require [clojure.test :refer [are deftest is testing]]
            [supports-color.core :as sut])) ; system under test

(def ^:private empty-mock-env
  (zipmap sut/env-var-keys (repeat nil)))

(deftest detects-a-color-profile-on-the-jvm
  (testing "the no-argument JVM API returns a supported level and its context"
    (let [profile (sut/profile)]
      (is (#{0 1 2 3} (:level profile)))
      (is (map? (:env profile)))
      (is (map? (:runtime profile))))))

(deftest mock-environments-select-each-supported-color-level
  (testing "environment overrides can select levels zero through three"
    (are [expected-level mock-env]
      (= expected-level
         (:level (sut/profile
                  {:sniff-flags false
                   :context      {:env (merge empty-mock-env mock-env)}})))
      0 {"NO_COLOR" "1"}
      1 {"TF_BUILD" "True" "AGENT_NAME" "test-agent"}
      2 {"FORCE_COLOR" "2"}
      3 {"FORCE_COLOR" "3"})))
