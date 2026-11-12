(ns supports-color.support
  "In-process test support.

   Drive the pure branching logic in-process by injecting a synthetic platform + a full mock env.
   This way the whole decision tree can be exercised on one machine with no real OS/TTY/subprocess.

   `profile` reads env through its `mock-env` argument and reads OS/TTY/
   argv through the global `pf` adapter installed by `register-platform!`. This
   namespace wraps both:
   - `full-env` builds a complete, real-env-isolating mock
   - `with-platform` temporarily installs a synthetic adapter and restores the real
   one afterward."
  (:require [supports-color.common :as c]
            [supports-color.core :as core])) ; side effect: registers the real JVM adapter

;; --- env -------------------------------------------------------------------

(def env-keys c/env-var-keys)

(defn full-env
  "A complete mock env: every known key present, so real environment variables
   are fully masked. A key present with nil reads as \"unset\"; a string value
   reads as \"set\". `overrides` are merged on top."
  ([] (full-env {}))
  ([overrides] (merge (zipmap env-keys (repeat nil)) overrides)))

;; --- platform --------------------------------------------------------------

(def unix-tty-adapter
  "Synthetic adapter: unix, all three descriptors are TTYs, no CLI argv, and the
   terminal-query fns return nil. Detection never calls the query fns, so the
   nils only matter if a test opts into the dark/light chain."
  {:real-get-env       (constantly nil)
   :stdout-tty         (constantly true)
   :stderr-tty         (constantly true)
   :stdin-tty         (constantly true)
   :platform           (constantly :unix)
   :os-release         (constantly "0.0.0")
   :os-name            (constantly "Linux")
   :argv               (constantly [])
   :query-terminal     (constantly nil)})

(defn tty [b] (constantly b))
(defn returns [v] (constantly v))

(defn with-platform*
  "Install (merge unix-tty-adapter overrides) as the platform for the duration of
   thunk, then restore whatever adapter was active before."
  [overrides thunk]
  (let [saved (deref (deref #'c/pf))]
    (try
      (c/register-platform! (merge unix-tty-adapter overrides))
      (thunk)
      (finally
        (c/register-platform! saved)))))

(defmacro with-platform [overrides & body]
  `(with-platform* ~overrides (fn [] ~@body)))

;; --- one-call level probe --------------------------------------------------

(defn level
  "Resolved color level (0-3) for env `overrides` under the currently installed
   platform. Flag sniffing is off by default so results depend only on env + the
   installed adapter. Pass opts to change stream/sniffing."
  ([overrides] (level overrides {:sniff-flags false}))
  ([overrides opts]
   (:level (core/profile (assoc opts :context {:env (full-env overrides)})))))
