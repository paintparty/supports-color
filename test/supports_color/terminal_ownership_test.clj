(ns supports-color.terminal-ownership-test
  "Tests that a query never leaves the user's terminal raw when this process
   stops mid-query or when another process owns the terminal (2026-11-08 audit,
   B-01 and B-03).

     exit / SIGTERM  a process that ends while a query holds the terminal raw,
             through System/exit or SIGTERM, still restores it. The restore runs
             from a shutdown hook, because `finally` blocks on other threads never
             run at exit.
     second process  a query that finds the terminal already raw (another
             process, a pager or a TUI holds it) declines: it does not query and
             does not write its raw snapshot back. A second process that starts
             while the first holds the terminal raw therefore leaves it cooked.

   The process-level cases run under the harness :shell option, so `sh` holds the
   pty as a login shell would and ECHO is read after the runners exit. Everything
   is skipped where no controlling-terminal pty is available."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]))

;; --- stty -g snapshot parsing ------------------------------------------------

(def ^:private macos-cooked
  ;; Captured with /bin/stty -g in a fresh pty on macOS 15.
  "gfmt1:cflag=4b00:iflag=2b02:lflag=5cb:oflag=3:discard=f:dsusp=19:eof=4:eol=ff:eol2=ff:erase=7f:intr=3:kill=15:lnext=16:min=1:quit=1c:reprint=12:start=11:status=14:stop=13:susp=1a:time=0:werase=17:ispeed=9600:ospeed=9600")

(def ^:private macos-raw
  ;; The same pty after `stty raw -echo`.
  "gfmt1:cflag=4b00:iflag=801:lflag=41:oflag=2:discard=f:dsusp=19:eof=4:eol=ff:eol2=ff:erase=7f:intr=3:kill=15:lnext=16:min=1:quit=1c:reprint=12:start=11:status=14:stop=13:susp=1a:time=0:werase=17:ispeed=9600:ospeed=9600")

(def ^:private gnu-cooked
  ;; Captured with /usr/bin/stty -g (GNU coreutils 9.7) in a fresh pty on Debian
  ;; 13. Positional format, iflag:oflag:cflag:lflag:cc... lflag 8a3b has ICANON
  ;; (0x2) and ECHO (0x8) set.
  "500:5:bf:8a3b:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0")

(def ^:private gnu-raw
  ;; The same pty after `stty raw -echo`. lflag 8a30: ISIG, ICANON and ECHO
  ;; cleared, IEXTEN (0x8000) kept.
  "0:4:bf:8a30:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0")

(def ^:private gnu-query-mode
  ;; The same pty after the query switch, `stty -icanon -echo min 1 time 0`.
  ;; lflag 8a31: ICANON and ECHO cleared, ISIG kept. A second process sees this
  ;; while the first holds the terminal for its query.
  "500:5:bf:8a31:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0")

(deftest snapshot-cooked-reads-both-formats
  (testing "an stty -g snapshot says whether canonical input and echo are on"
    (if-let [cooked? (requiring-resolve 'supports-color.common/snapshot-cooked?)]
      (do
        (is (true? (cooked? macos-cooked :macos)) "macOS cooked")
        (is (false? (cooked? macos-raw :macos)) "macOS raw -echo")
        (is (true? (cooked? gnu-cooked :unix)) "Linux cooked")
        (is (false? (cooked? gnu-raw :unix)) "Linux raw -echo")
        (is (false? (cooked? gnu-query-mode :unix)) "Linux query mode")
        (is (false? (cooked? "gfmt1:cflag=4b00:lflag=5c3" :macos))
            "echo off alone counts as not cooked")
        (is (nil? (cooked? "not a snapshot" :macos)) "unknown format -> nil")
        (is (nil? (cooked? "" :unix)) "blank -> nil")
        (is (nil? (cooked? nil :unix)) "nil -> nil"))
      (is false "supports-color.common/snapshot-cooked? is not defined yet"))))

;; --- B-03: the process ends mid-query ---------------------------------------

(deftest exit-mid-query-restores-terminal
  (testing "System/exit while a prefetched query holds the terminal raw still
            leaves it cooked"
    (h/when-pty
     (let [r (h/run-pty-scenario
              {:scenario :prefetch-then
               :opts {:timeout-ms 3000 :after-ms 500 :then :exit}}
              {:shell "\"$@\"" :timeout-ms 20000})]
       (is (false? (:timed-out r)) "the shell finished")
       (is (true? (:echo-after r)) "ECHO is on after the process exited")))))

(deftest sigterm-mid-query-restores-terminal
  (testing "SIGTERM while a query holds the terminal raw still leaves it cooked"
    (h/when-pty
     (let [mark (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "sc-mark-" (System/nanoTime))))
           script (str "\"$@\" & P=$!\n"
                       "i=0\n"
                       "while [ ! -e \"$MARK_FILE\" ] && [ $i -lt 500 ]; do\n"
                       "  sleep 0.02; i=$((i+1))\n"
                       "done\n"
                       "kill -TERM $P\n"
                       "wait")
           r (h/run-pty-scenario
              {:scenario :prefetch-then
               :opts {:timeout-ms 5000 :after-ms 500 :then :mark :mark-file mark}}
              {:shell script :env {"MARK_FILE" mark} :timeout-ms 20000})]
       (is (.exists (io/file mark)) "the runner was mid-query when it was killed")
       (is (false? (:timed-out r)) "the shell finished")
       (is (true? (:echo-after r)) "ECHO is on after SIGTERM")))))

;; --- B-01: another process holds the terminal --------------------------------

(defn- raw-then-query [opts]
  (h/run-pty-scenario {:scenario :raw-then-query :opts {:timeout-ms 300}}
                      (merge {:wait-for-query? true :wait-go? true
                              :response-hex h/osc11-reply-hex}
                             opts)))

(defn- declines [r]
  (is (nil? (:return (:result r)))
      "no query is sent to a terminal another program holds raw")
  (is (true? (:untouched? (:result r)))
      "the other program's raw state is left exactly as it was")
  (is (true? (:echo-after r)) "the runner's own restore put ECHO back"))

(deftest declines-when-terminal-already-raw
  (testing "a query that finds the terminal raw declines"
    (h/when-pty (declines (raw-then-query {})))))

(deftest node-declines-when-terminal-already-raw
  (testing "a Node query that finds the terminal raw declines"
    (h/when-node-pty (declines (raw-then-query {:runtime :node})))))

(def ^:private second-starts-when-first-is-raw
  ;; Start one runner, wait until the pty shows -icanon (the first runner is in
  ;; its raw window), then start the second. A fixed delay is not enough: two
  ;; `npx nbb` starts tend to line up and snapshot the terminal at the same instant.
  ;;
  ;; The second runner's stdio is not the pty. Some Node builds (Ubuntu 26.04's
  ;; nodejs 22.22.1) save the stdio terminal settings at startup and write them
  ;; back at exit. A second Node that boots inside the first runner's window and
  ;; exits after its restore would put the raw state back, whatever this library
  ;; does. The :query scenario opens /dev/tty itself, so the second runner still
  ;; snapshots the terminal and declines. README "Things to know" covers the
  ;; production side.
  (str "\"$@\" &\n"
       "i=0\n"
       "while ! stty -a | grep -q -- -icanon && [ $i -lt 1000 ]; do\n"
       "  sleep 0.02; i=$((i+1))\n"
       "done\n"
       "\"$@\" </dev/null >/dev/null 2>&1\n"
       "wait"))

(defn- two-processes
  "Two runners on one pty, each running an OSC 11 query of `timeout-ms` with no
   reply. The second starts while the first holds the terminal raw, and
   `timeout-ms` must cover the second runner's startup."
  [timeout-ms opts]
  (h/run-pty-scenario {:scenario :query :opts {:timeout-ms timeout-ms}}
                      (merge {:shell second-starts-when-first-is-raw
                              :timeout-ms 30000}
                             opts)))

(deftest second-process-does-not-strand-raw
  (testing "a second process that starts while the first holds the terminal raw
            leaves it cooked"
    (h/when-pty
     (let [r (two-processes 4000 {})]
       (is (false? (:timed-out r)) "the shell finished")
       (is (true? (:echo-after r)) "ECHO is on after both processes exit")))))

(deftest node-second-process-does-not-strand-raw
  (testing "a second Node process that starts while the first holds the terminal
            raw leaves it cooked"
    (h/when-node-pty
     (let [r (two-processes 6000 {:runtime :node})]
       (is (false? (:timed-out r)) "the shell finished")
       (is (true? (:echo-after r)) "ECHO is on after both processes exit")))))
