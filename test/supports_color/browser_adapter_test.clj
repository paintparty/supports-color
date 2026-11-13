(ns supports-color.browser-adapter-test
  "In-process tests for the browser adapter's effect on profile, theme, and
   background.

     level     a browser reports level 3 and :runtime :host :browser.
     polarity  theme and background read the adapter's :prefers-dark, with
               :detection-method :prefers-color-scheme.
     no query  a browser never calls the terminal query fn.

   Everything runs against the synthetic platform from `supports-color.support`
   with :host :browser added, so nothing touches a terminal."
  (:require [clojure.test :refer [deftest is testing]]
            [supports-color.core :as sc]
            [supports-color.support :as s]))

(defn- in-browser
  "Calls f with a browser adapter whose :prefers-dark returns dark?. Returns the
   result and the number of terminal queries sent."
  [f dark?]
  (let [calls  (atom 0)
        result (s/with-platform {:host           :browser
                                 :prefers-dark   (constantly dark?)
                                 :query-terminal (fn [_] (swap! calls inc) nil)}
                 (f))]
    {:result result
     :calls  @calls}))

(deftest browser-profile-is-truecolor
  (let [{:keys [result]} (in-browser #(sc/profile {:context {:env (s/full-env)}}) nil)]
    (is (= 3 (:level result)))
    (is (= :truecolor (:color-profile result)))
    (is (= :browser (get-in result [:runtime :host])))))

(deftest browser-polarity-comes-from-prefers-color-scheme
  (doseq [[dark? polarity] [[true "dark"] [false "light"]]
          f                [sc/theme sc/background]]
    (testing (str f " " polarity)
      (let [{:keys [result calls]} (in-browser #(f {:context {:env (s/full-env)}}) dark?)]
        (is (= polarity (:polarity result)))
        (is (= :prefers-color-scheme (:detection-method result)))
        (is (zero? calls) "no terminal query")))))

(deftest browser-without-a-preference-has-no-polarity
  (let [{:keys [result calls]} (in-browser #(sc/theme {:context {:env (s/full-env)}}) nil)]
    (is (nil? (:polarity result)))
    (is (= :none (:detection-method result)))
    (is (zero? calls))))

(deftest browser-background-has-no-color
  (let [{:keys [result]} (in-browser #(sc/background {:context {:env (s/full-env)}}) true)]
    (is (contains? result :background-color))
    (is (nil? (:background-color result))))
  (let [{:keys [result]} (in-browser #(sc/theme {:context {:env (s/full-env)}}) true)]
    (is (not (contains? result :background-color)))))
