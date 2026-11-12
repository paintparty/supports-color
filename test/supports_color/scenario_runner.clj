(ns supports-color.scenario-runner
  "Child entrypoint driven by the pty test harness (`pty_harness.py` and
   `supports-color.harness`). This process is spawned *under a pty* (or in a
   session with no controlling terminal) so that `/dev/tty` inside `core.clj`
   resolves to a terminal the parent test controls.

   Protocol, all via env vars and plain files so it works identically under the
   JVM and Babashka with no extra deps:

     argv[0]       an EDN map: {:scenario <kw> :opts {...}}. Optional :stty and
                   :sh name shims to run in place of the real `stty` and `sh`.
                   They are set through core's private test hooks before the
                   scenario runs. core reads no environment variable for them.
     RESULT_FILE   this process writes its result map (EDN) here, then
     DONE_FILE     touches this file to signal the parent the action is complete
     GO_FILE       if WAIT_GO is set, block until this file appears before
                   exiting, so the parent can snapshot terminal state (ECHO)
                   while this process, and thus the pty slave, is still alive

   No source shortcuts are used: private query vars are reached with
   `requiring-resolve`, and echo-toggle self-tests shell `stty` directly rather
   than calling the (under-test) source."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- env [k] (System/getenv k))

(defn- spit-atomic
  "Write s to path via a temp file + rename so a poller never sees a partial file."
  [path s]
  (let [tmp (str path ".tmp")]
    (spit tmp s)
    (.renameTo (java.io.File. tmp) (java.io.File. path))))

(defn- signal-done! [result]
  (when-let [rf (env "RESULT_FILE")] (spit-atomic rf (pr-str result)))
  (when-let [df (env "DONE_FILE")] (spit df "")))

(defn- wait-go! []
  (when (env "WAIT_GO")
    (let [go (env "GO_FILE")
          deadline (+ (System/currentTimeMillis) 10000)]
      (while (and go
                  (not (.exists (java.io.File. ^String go)))
                  (< (System/currentTimeMillis) deadline))
        (Thread/sleep 20)))))

(defn- stty
  "Run bare `stty` (PATH-resolved, so a shim on PATH is honored) against /dev/tty."
  [& args]
  (let [p (-> (ProcessBuilder. ^java.util.List (into ["stty"] args))
              (.redirectInput (java.lang.ProcessBuilder$Redirect/from
                               (java.io.File. "/dev/tty")))
              .start)
        out (slurp (.getInputStream p))]
    (.waitFor p)
    {:out (str/trim out) :exit (.exitValue p)}))

(defn- apply-overrides!
  "Point core's private test hooks at the shims named by the spec's :stty and :sh."
  [{:keys [stty sh]}]
  (when stty (reset! @(requiring-resolve 'supports-color.core/stty-override) stty))
  (when sh (reset! @(requiring-resolve 'supports-color.core/sh-override) sh)))

(defn- run [{:keys [scenario opts]}]
  (case scenario
    ;; Read one byte from the controlling tty. Lets the parent prove it can feed
    ;; a byte down the pty and that the child sees it.
    :echo-probe
    (with-open [in (java.io.FileInputStream. "/dev/tty")]
      {:read-byte (.read in)})

    ;; Flip the tty to raw/-echo, signal DONE, block on GO (parent snapshots ECHO
    ;; here and should see it off), then restore. Validates the harness's echo
    ;; reader independently of the source under test.
    :raw-toggle
    (let [saved (:out (stty "-g"))]
      (stty "raw" "-echo")
      (signal-done! {:saved saved :raw-set true})
      (wait-go!)
      (stty saved)
      ::already-signalled)

    ;; Read the controlling tty until EOF (-1). Lets the parent feed a partial
    ;; frame then close the master, proving close-mid-frame surfaces as -1.
    ;; That is the primitive behind the read-loop EOF test.
    :read-until-eof
    (with-open [in (java.io.FileInputStream. "/dev/tty")]
      ;; Announce readiness (a byte the parent sees) so it only sends the prefix
      ;; and closes the master once we are actually in the read loop.
      (with-open [out (java.io.FileOutputStream. "/dev/tty")]
        (.write out (int \R))
        (.flush out))
      (loop [n 0]
        (let [b (.read in)]
          (if (= b -1)
            {:bytes-read n :last -1}
            (recur (inc n))))))

    ;; Shell bare `stty -g`, used to prove a shim on PATH was invoked.
    :spawn-stty
    (stty "-g")

    ;; Try to open /dev/tty. Under a no-controlling-tty child this must fail.
    :open-tty-probe
    (try
      (with-open [_ (java.io.FileInputStream. "/dev/tty")]
        {:opened? true})
      (catch Exception e {:opened? false :error (.getMessage e)}))

    ;; The one combined terminal query (CSI ?996n, OSC 11, DA1).
    :query
    {:return ((requiring-resolve 'supports-color.core/query-terminal-jvm)
              (:timeout-ms opts 100))}

    :background
    {:return ((requiring-resolve 'supports-color.core/background) (or opts {}))}

    ;; Run a query on a worker thread and interrupt it mid-query. The worker
    ;; records its own interrupt flag *after* the query returns,
    ;; since a terminated thread's flag reads false from outside. A long timeout
    ;; keeps the query in its read loop when the interrupt arrives.
    :interrupt-query
    (let [q (requiring-resolve 'supports-color.core/query-terminal-jvm)
          t (:timeout-ms opts 3000)
          ret (atom :none)
          flag (atom nil)
          th (Thread. (fn []
                        (reset! ret (q t))
                        (reset! flag (.isInterrupted (Thread/currentThread)))))]
      (.start th)
      (Thread/sleep 300)
      (.interrupt th)
      (.join th 8000)
      {:interrupt-flag @flag :query-return @ret})

    ;; Call each registered tty predicate `:reps` times and return the last result
    ;; of each, for the per-descriptor tty predicate test. Loading core installs the
    ;; platform adapter. The predicates are read straight off it. The parent controls
    ;; which fds are ttys via the harness :redir option and counts probe subprocesses
    ;; via a recording `sh` passed as the spec's :sh. Repeated calls exercise the delay
    ;; memoization.
    :tty-predicates
    (do
      (require 'supports-color.core)
      (let [impl @@(requiring-resolve 'supports-color.common/pf)
            reps (:reps opts 1)
            call (fn [k]
                   (let [f (get impl k)]
                     (dotimes [_ (dec reps)] (f))
                     (f)))]
        {:stdout (call :stdout-tty)
         :stderr (call :stderr-tty)
         :stdin  (call :stdin-tty)
         :reps   reps}))

    ;; Turn off ixon (a setting a user might choose), call background with a 0 ms
    ;; timeout, and report whether the terminal settings came back unchanged.
    :settings-survive
    (let [orig   (:out (stty "-g"))
          _      (stty "-ixon")
          before (:out (stty "-g"))
          _      ((requiring-resolve 'supports-color.core/background) {:timeout-ms 0})
          after  (:out (stty "-g"))]
      (stty orig)
      {:same? (= before after) :before before :after after})

    ;; Call background, then read whatever bytes are still queued on /dev/tty, so
    ;; a test can see replies the read loop left behind. :pending-wait-ms (default
    ;; 100) is how long to wait for late bytes before reading.
    :background-then-pending
    (let [ret   ((requiring-resolve 'supports-color.core/background)
                 (dissoc (or opts {}) :pending-wait-ms))
          saved (:out (stty "-g"))]
      (stty "-icanon" "-echo" "min" "0" "time" "0")
      (Thread/sleep (:pending-wait-ms opts 100))
      (let [pending (with-open [in (java.io.FileInputStream. "/dev/tty")]
                      (let [n  (.available in)
                            bs (byte-array n)]
                        (.read in bs 0 n)
                        (String. bs "ISO-8859-1")))]
        (stty saved)
        {:return ret :pending pending}))

    ;; Start a long query on another thread, as a CLI prefetching the background
    ;; would, then end the process mid-query: with System/exit (:then :exit), or by
    ;; touching :mark-file and waiting to be killed (:then :mark).
    :prefetch-then
    (let [q (requiring-resolve 'supports-color.core/query-terminal-jvm)]
      (future (q (:timeout-ms opts 3000)))
      (Thread/sleep (:after-ms opts 500))
      (when (= :mark (:then opts))
        (spit (:mark-file opts) "")
        (Thread/sleep 30000))
      (System/exit 0))

    ;; Put the tty in raw mode first, as another program holding the terminal
    ;; would, then run a query. :untouched? is true when the query left that raw
    ;; state as it found it. The runner restores the tty itself afterward.
    :raw-then-query
    (let [saved    (:out (stty "-g"))
          _        (stty "raw" "-echo")
          raw-snap (:out (stty "-g"))
          ret      ((requiring-resolve 'supports-color.core/query-terminal-jvm)
                    (:timeout-ms opts 300))
          after    (:out (stty "-g"))]
      (stty saved)
      {:return ret :untouched? (= raw-snap after) :saved saved :raw-snapshot raw-snap})

    ;; Two threads querying at once, for the mutual-exclusion test.
    :concurrent
    (let [q1 (requiring-resolve 'supports-color.core/query-terminal-jvm)
          q2 (requiring-resolve 'supports-color.core/query-terminal-jvm)
          t (:timeout-ms opts 100)
          f1 (future (q1 t))
          f2 (future (q2 t))]
      @f1 @f2
      {:done true})

    {:error (str "unknown scenario " scenario)}))

(defn -main [& args]
  (let [spec (edn/read-string (first args))
        result (try (apply-overrides! spec)
                    (run spec)
                    (catch Throwable t {:error (.getMessage t)}))]
    ;; :raw-toggle signals DONE itself (mid-action). Every other scenario signals
    ;; here after completing.
    (when-not (= result ::already-signalled)
      (signal-done! result)
      (wait-go!))
    (shutdown-agents)
    (System/exit 0)))
