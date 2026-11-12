(ns supports-color.manual-background-check
  "Manual check for supports-color.core/background in a real terminal. Calls
   background once and prints one map with :polarity, :background-color,
   :detection-method and :elapsed-ms.

   Nothing else from the result is printed. The full result also carries :env
   and :runtime, which describe the user's environment and don't belong in a
   terminal log or a pasted result.

   Optional first argument: :timeout-ms as an integer, for example 3000.

   Run from the repo root, in the terminal under test:
     bb --classpath src test/supports_color/manual_background_check.cljc
     npx --yes nbb -cp src test/supports_color/manual_background_check.cljc
     clojure -M test/supports_color/manual_background_check.cljc

   Not a test namespace: the test runners only load namespaces ending in -test.
   Loading it runs the check and exits the process. See doc/terminal-testing.md."
  (:require [supports-color.core :as sc]))

(defn- now-ms []
  #?(:clj  (/ (System/nanoTime) 1e6)
     :cljs (js/performance.now)))

(defn- round-1 [x]
  (/ (#?(:clj Math/round :cljs js/Math.round) (* 10.0 x)) 10.0))

(defn- parse-timeout [s]
  (when s
    #?(:clj  (Long/parseLong s)
       :cljs (let [n (js/parseInt s 10)]
               (when (js/isNaN n)
                 (throw (js/Error. (str "not an integer: " s))))
               n))))

(defn- exit! [code]
  #?(:clj  (System/exit code)
     :cljs (js/process.exit code)))

(defn- error-line [e]
  #?(:clj  (str (.getName (class e)) ": " (.getMessage e))
     :cljs (str "Error: " (.-message e))))

(defn -main [args]
  (try
    (let [timeout-ms (parse-timeout (first args))
          opts       (if timeout-ms {:timeout-ms timeout-ms} {})
          start      (now-ms)
          result     (sc/background opts)
          elapsed    (- (now-ms) start)]
      (prn (-> result
               (select-keys [:polarity :background-color :detection-method])
               (assoc :elapsed-ms (round-1 elapsed))))
      (exit! 0))
    (catch #?(:clj Throwable :cljs :default) e
      #?(:clj  (binding [*out* *err*] (println (error-line e)))
         :cljs (js/console.error (error-line e)))
      (exit! 1))))

(-main *command-line-args*)
