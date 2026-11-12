(ns supports-color.node-scenario-runner
  "Node/nbb analog of supports-color.scenario-runner: the child entrypoint the
   pty test harness (`pty_harness.py` + `supports-color.harness`) drives to
   exercise the *Node* terminal queries under a real controlling terminal.

   `pty_harness.py` execs any argv, so the JVM parent (`harness.clj`) can spawn
   this Node child unchanged once `child-cmd` emits the nbb argv (`:runtime
   :node`). This process honors the exact same file/env contract as the JVM
   runner:

     argv[0]      an EDN map {:scenario <kw> :opts {...}}
     RESULT_FILE  this process writes its result map (EDN) here, then
     DONE_FILE    touches this file to signal the parent the action is complete
     GO_FILE      if WAIT_GO is set, block until this file appears before exiting,
                  so the parent can snapshot ECHO while the pty slave is alive

   The spec may also carry `:runtime-env {k v}`. Those entries are assigned into
   `process.env` after startup and before the scenario runs, the way a host CLI
   calling `dotenv.config()` would. Unlike the harness `:env`, they never reached
   this process's own startup, so `NODE_OPTIONS` there cannot affect nbb itself.
   `:chdir <dir>` then moves this process into `dir`, the way a CLI changes into a
   project directory before doing its work. `:stty <path>` puts a shim in place of
   the real `stty` through core's private test hook.

   Query scenarios return `:elapsed-ms`, measured around the query call only, so
   nbb startup is not counted.

   Private query vars are reached with `resolve`. The echo-toggle self-test shells
   `stty` directly rather than calling the code under test."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ;; Loading core installs the Node platform adapter and defines the
            ;; query vars. Requiring it here (not at runtime) lets `resolve` find
            ;; them under nbb/SCI.
            [supports-color.core]
            ["fs" :as fs]
            ["child_process" :as cp]))

(defn- env [k] (aget (.-env js/process) k))

(defn- spit-atomic
  "Write s to path via a temp file + rename so a poller never sees a partial file."
  [path s]
  (let [tmp (str path ".tmp")]
    (fs/writeFileSync tmp s)
    (fs/renameSync tmp path)))

(defn- touch [path] (fs/writeFileSync path ""))

(defn- signal-done! [result]
  (when-let [rf (env "RESULT_FILE")] (spit-atomic rf (pr-str result)))
  (when-let [df (env "DONE_FILE")] (touch df)))

(defn- sleep-ms
  "Block the single Node thread for ms, matching the JVM runner's Thread/sleep."
  [ms]
  (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 ms))

(defn- wait-go! []
  (when (env "WAIT_GO")
    (let [go (env "GO_FILE")
          deadline (+ (js/Date.now) 10000)]
      (while (and go
                  (not (fs/existsSync go))
                  (< (js/Date.now) deadline))
        (sleep-ms 20)))))

(defn- stty
  "Run bare `stty` against /dev/tty and return {:out :exit}. Used only by the
   echo self-test, so it stays independent of the query code under test."
  [& args]
  (let [tty-fd (fs/openSync "/dev/tty" "r")]
    (try
      (let [out (.toString
                 (cp/execFileSync "stty" (clj->js args)
                                  #js {:stdio #js [tty-fd "pipe" "pipe"]}))]
        {:out (str/trim out) :exit 0})
      (catch :default e
        {:out "" :exit (or (.-status e) 1)})
      (finally (fs/closeSync tty-fd)))))

(defn- query-fn [sym] @(resolve sym))

(defn- apply-runtime-env!
  "Assign each entry of `m` into process.env, as dotenv.config() does."
  [m]
  (doseq [[k v] m]
    (aset (.-env js/process) k v)))

(defn- timed-query
  "Call the query var `sym` with `timeout-ms` and return {:return :elapsed-ms}."
  [sym timeout-ms]
  (let [t0  (js/performance.now)
        ret ((query-fn sym) timeout-ms)]
    {:return ret :elapsed-ms (js/Math.round (- (js/performance.now) t0))}))

(defn- run [{:keys [scenario opts]}]
  (case scenario
    ;; Flip the tty to raw/-echo, signal DONE, block on GO (parent snapshots ECHO
    ;; here and should see it off), then restore. Validates the harness's echo
    ;; reader against the Node child independently of the source under test.
    :raw-toggle
    (let [saved (:out (stty "-g"))]
      (stty "raw" "-echo")
      (signal-done! {:saved saved :raw-set true})
      (wait-go!)
      (stty saved)
      ::already-signalled)

    ;; Put the tty in raw mode first, as another program holding the terminal
    ;; would, then run a query. :untouched? is true when the query left that raw
    ;; state as it found it. The runner restores the tty itself afterward.
    :raw-then-query
    (let [saved    (:out (stty "-g"))
          _        (stty "raw" "-echo")
          raw-snap (:out (stty "-g"))
          ret      ((query-fn 'supports-color.core/query-terminal-node)
                    (:timeout-ms opts 300))
          after    (:out (stty "-g"))]
      (stty saved)
      {:return ret :untouched? (= raw-snap after) :saved saved :raw-snapshot raw-snap})

    ;; The one combined terminal query (CSI ?996n, OSC 11, DA1).
    :query
    (timed-query 'supports-color.core/query-terminal-node (:timeout-ms opts 100))

    :background
    {:return ((query-fn 'supports-color.core/background) (or opts {}))}

    ;; Call background, then read whatever bytes are still queued on /dev/tty, so
    ;; a test can see replies the read loop left behind. :pending-wait-ms (default
    ;; 100) is how long to wait for late bytes before reading.
    :background-then-pending
    (let [ret   ((query-fn 'supports-color.core/background)
                 (dissoc (or opts {}) :pending-wait-ms))
          saved (:out (stty "-g"))]
      (stty "-icanon" "-echo" "min" "0" "time" "0")
      (sleep-ms (:pending-wait-ms opts 100))
      (let [fd      (fs/openSync "/dev/tty" (bit-or (.. fs -constants -O_RDONLY)
                                                    (.. fs -constants -O_NONBLOCK)))
            buf     (.alloc js/Buffer 256)
            pending (loop [acc ""]
                      (let [n (try (.readSync fs fd buf 0 256) (catch :default _ 0))]
                        (if (pos? n)
                          (recur (str acc (.toString buf "latin1" 0 n)))
                          acc)))]
        (fs/closeSync fd)
        (stty saved)
        {:return ret :pending pending}))

    {:error (str "unknown scenario " scenario)}))

(defn -main [& args]
  (let [spec (edn/read-string (first args))
        _ (apply-runtime-env! (:runtime-env spec))
        _ (some->> (:chdir spec) (.chdir js/process))
        _ (when-let [p (:stty spec)]
            (reset! @(resolve 'supports-color.core/stty-override) p))
        result (try (run spec) (catch :default t {:error (.-message t)}))]
    ;; :raw-toggle signals DONE itself mid-action. Every other scenario signals
    ;; here after completing.
    (when-not (= result ::already-signalled)
      (signal-done! result)
      (wait-go!))
    (js/process.exit 0)))

(apply -main *command-line-args*)
