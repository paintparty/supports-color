(ns supports-color.stty-subprocess-handling-test
  "Tests for the waitFor / interrupt / exit-status fixes in core.clj.

   Bounded waits: every wait on an stty child was the unbounded `.waitFor` overload, and
   the snapshot line was read with a blocking `.readLine`, so a hung stty stalls
   the caller far past its timeout budget. The fix waits with a bounded overload,
   force-kills a child that overruns, and reads the snapshot only after the child
   has exited. Discriminated with a shim that sleeps: the call must return within
   the query budget plus an independent restore budget rather than blocking for the
   full sleep on each stty. Compared against a fast baseline run so per-runtime
   startup cost cancels out of the timing assertion.

   Interrupt handling: an interrupt during a wait unwound into the broad `(catch Exception)`,
   which swallowed the InterruptedException and cleared the interrupt flag while
   leaving the terminal raw. The fix restores the tty on the way out and re-raises
   the interrupt. Driven through the pty: a worker interrupted mid-query must end
   up with its interrupt flag set and the terminal back in cooked mode.

   Exit-status checking: the `stty -g` exit status was never checked, so a snapshot from a
   failed child was trusted and raw mode entered on it. The fix rejects a non-zero
   exit before going raw. Discriminated with a shim whose `-g` prints a plausible
   line but exits 1: pre-fix that line is trusted and `stty raw -echo` is spawned;
   post-fix the query declines and never goes raw.

   Everything is skipped where no controlling-terminal pty is available."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(defn- record-file []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "sc-stty-subproc-rec-" (System/nanoTime)))))

(defn- shim-stty [dir] (str (io/file dir "stty")))

;; --- bounded waits + own restore budget -------------------------------------

(deftest hung-stty-is-bounded-not-blocking
  (testing "a sleeping stty is killed after the budget instead of blocking per call"
    (h/when-pty
     (let [dir (shims/make-shim-dir!)
           t   100
           run (fn [mode]
                 (:elapsed-ms
                  (h/run-pty-scenario
                   {:scenario :query :opts {:timeout-ms t} :stty (shim-stty dir)}
                   {:env {"SHIM_MODE" mode
                          "SHIM_SLEEP_SECS" "3"}
                    :wait-go? true})))
           ;; Baseline: a shim that exits immediately. Its elapsed time is almost
           ;; entirely child-runtime startup, which is identical for the sleep run.
           baseline (run "silent-ok")
           ;; The sleep shim makes every stty call sleep 3s. Pre-fix the blocking
           ;; readLine plus the unbounded restore waitFor add ~6s over baseline.
           slept    (run "sleep")
           overhead (- slept baseline)]
       ;; Post-fix the snapshot child is killed after the ~100ms query budget and
       ;; the restore after its own ~2s budget, so the added time stays well under
       ;; a single 3s sleep, let alone the ~6s the pre-fix code spends blocking.
       (is (< overhead 3000)
           (str "sleep-shim overhead " overhead "ms should be within the bounded "
                "query+restore budget, not the 3s-per-call blocking of the old waits"))))))

(deftest sleeping-snapshot-returns-nil-with-echo-on
  (testing "a query whose stty sleeps returns nil and never leaves the tty raw"
    (h/when-pty
     (let [dir (shims/make-shim-dir!)
           r (h/run-pty-scenario
              {:scenario :query :opts {:timeout-ms 100} :stty (shim-stty dir)}
              {:env {"SHIM_MODE" "sleep"
                     "SHIM_SLEEP_SECS" "3"}
               :wait-go? true})]
       (is (false? (:timed-out r))
           "the harness did not have to kill a hung child")
       (is (nil? (:return (:result r)))
           "no usable snapshot means the query returns nil")
       (is (true? (:echo-after r))
           "raw mode is never entered, so ECHO stays on")))))

;; --- interrupt restores the tty and survives --------------------------------

(deftest interrupt-mid-query-restores-tty-and-keeps-flag
  (testing "interrupting a query restores ECHO and re-raises the interrupt"
    (h/when-pty
     ;; Real stty against the pty slave: the worker actually enters raw mode, then
     ;; the parent scenario interrupts it mid-read-loop. The restoring finally must
     ;; put ECHO back and the catch must re-set the thread's interrupt flag rather
     ;; than swallowing it.
     (let [r (h/run-pty-scenario
              {:scenario :interrupt-query :opts {:timeout-ms 3000}}
              {:wait-go? true})
           res (:result r)]
       (is (true? (:interrupt-flag res))
           "the interrupt flag is restored rather than swallowed")
       (is (true? (:echo-after r))
           "the terminal is back in cooked mode after the interrupt")
       (is (false? (:timed-out r))
           "the interrupted query returned rather than hanging")))))

;; --- non-zero stty -g exit is not trusted ------------------------------------

(deftest nonzero-snapshot-exit-does-not-enter-raw
  (testing "a snapshot line from a failed stty -g is rejected before going raw"
    (h/when-pty
     ;; A generous timeout so the (shell-script) shim `-g` runs to completion and
     ;; exits non-zero. The point is to exercise the exit-status rejection, not the
     ;; wait-or-kill! budget. A real stty -g finishes in ~15ms regardless.
     (let [dir (shims/make-shim-dir!)
           rec (record-file)
           r (h/run-pty-scenario
              {:scenario :query :opts {:timeout-ms 1000} :stty (shim-stty dir)}
              {:env {"SHIM_MODE" "g-line-exit1"
                     "SHIM_RECORD_FILE" rec}
               :wait-go? true})
           calls (when (.exists (io/file rec)) (slurp rec))]
       (is (nil? (:return (:result r)))
           "a failed snapshot yields nil, not a query on untrusted settings")
       (is (some? calls) "the shim recorded the child's stty calls")
       (is (str/includes? calls "stty -g")
           "the snapshot was attempted")
       (is (not (str/includes? calls "-icanon"))
           "raw mode is never entered on a snapshot whose stty exited non-zero")
       (is (true? (:echo-after r))
           "ECHO is still on afterward")))))
