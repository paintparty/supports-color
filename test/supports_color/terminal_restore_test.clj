(ns supports-color.terminal-restore-test
  "Tests for the shared save/raw/query/restore restructure in core.clj: a blank
   snapshot must never enter raw mode, and the terminal must always come back
   cooked, whether the query timed out, was interrupted before its try block, or
   ran concurrently with another query.

   The strongest, fully-discriminating case is a `stty -g` that prints no
   snapshot: it must not enter raw mode, must not touch the terminal at all (no
   `stty sane`, which would wipe settings the user chose), and must return nil.
   The shim kit lets us assert exactly which stty calls the child made, so
   this fails on the pre-fix code (which built `[\"stty\" nil]`, threw an NPE
   inside the finally, and skipped restoration) and passes on the fix.

   The raw-before-try / close-before-restore ordering and the mutual exclusion
   between concurrent queries are checked by the restoration invariant. After a real-pty run,
   whether a lone query that times out or two queries driven concurrently, the
   terminal must be back in cooked mode with ECHO on. Injecting a throw into the narrow
   pre-try window is not something the pty primitives can do, so these assert the
   invariant the restructure exists to keep rather than the race itself.

   Everything is skipped where no controlling-terminal pty is available."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(defn- record-file []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "sc-restore-rec-" (System/nanoTime)))))

(defn- blank-snapshot-env [rec]
  ;; Steers the shim named by the spec's :stty. The JVM's stty inherits the env.
  {"SHIM_MODE" "g-empty-raw-ok"
   "SHIM_RECORD_FILE" rec})

(deftest blank-snapshot-leaves-the-terminal-alone
  (testing "with `stty -g` yielding no snapshot, the query returns nil, never runs
            `stty raw`, and never resets the terminal with `stty sane`"
    (h/when-pty
     (let [rec (record-file)
         ;; core resolves stty to a fixed absolute path, so a PATH shim is not
         ;; reachable. The scenario runner puts this one in place through core's
         ;; private test hook.
           r (h/run-pty-scenario {:scenario :query :opts {:timeout-ms 100}
                                  :stty (str (io/file (shims/make-shim-dir!) "stty"))}
                                 {:env (blank-snapshot-env rec)
                                  :wait-go? true})
           calls (when (.exists (io/file rec)) (slurp rec))]
       (is (nil? (:return (:result r)))
           "a query with no restorable snapshot returns nil")
       (is (some? calls) "the shim recorded the child's stty calls")
       (is (str/includes? calls "stty -g") "it snapshotted the tty first")
       (is (not (str/includes? calls "sane"))
           "the user's settings are never reset with `stty sane`")
       (is (not (str/includes? calls "-icanon"))
           "raw mode is never entered without a valid snapshot")
       (is (true? (:echo-after r))
           "ECHO is still on afterward")))))

(deftest restores-terminal-after-a-real-pty-run
  (testing "after entering real raw mode and timing out, ECHO is restored"
    (h/when-pty
     ;; No shim: the child drives the real stty against the pty slave, actually
     ;; switching it to raw/-echo. With no reply sent, the query times out and the
     ;; restoring finally must put ECHO back. That is the restoration invariant.
     (let [r (h/run-pty-scenario {:scenario :query :opts {:timeout-ms 150}}
                                 {:wait-go? true})]
       (is (true? (:echo-after r))
           "the terminal is back in echoing cooked mode after the query")
       (is (false? (:timed-out r))
           "the child returned on its own rather than hanging")))))

(deftest concurrent-queries-leave-the-terminal-cooked
  (testing "two queries driven concurrently still leave ECHO on afterward"
    (h/when-pty
     ;; Both query functions contend for the same /dev/tty. The shared lock must
     ;; serialize their save/raw/restore steps so the terminal is not left raw.
     (let [r (h/run-pty-scenario {:scenario :concurrent :opts {:timeout-ms 150}}
                                 {:wait-go? true})]
       (is (true? (:done (:result r))) "both concurrent queries completed")
       (is (true? (:echo-after r))
           "ECHO is on after both queries restore under the shared lock")
       (is (false? (:timed-out r)) "neither query hung")))))
