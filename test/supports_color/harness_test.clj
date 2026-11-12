(ns supports-color.harness-test
  "Self-tests that prove the pty test harness infrastructure actually works. Covers
   a pty round-trip, ECHO reading, close-mid-frame EOF, the stty shim kit, and the
   no-controlling-tty primitive. They exercise the harness, not the source fixes
   (those are covered by the other test namespaces). Everything is skipped where no pty is available."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(deftest capability-predicate-returns-a-boolean
  (is (boolean? (h/pty-available?))))

(deftest pty-round-trip-and-echo-reader
  (testing "the parent can feed a byte down the pty and read the child's ECHO bit"
    (h/when-pty
     ;; Cooked pty: send 'A' + newline so the line-buffered read delivers 'A'.
     (let [r (h/run-pty-scenario {:scenario :echo-probe}
                                 {:response-hex "410a" :wait-go? true})]
       (is (= 65 (:read-byte (:result r))) "child read the fed byte")
       (is (true? (:echo-after r)) "a fresh pty defaults to ECHO on")))))

(deftest echo-reader-observes-raw-mode
  (testing "flipping the tty to raw/-echo is visible to the termios reader"
    (h/when-pty
     (let [r (h/run-pty-scenario {:scenario :raw-toggle} {:wait-go? true})]
       (is (false? (:echo-after r)) "ECHO is off while the child holds raw mode")))))

(deftest partial-frame-then-eof
  (testing "a prefix followed by EOF is read in full, then the read ends at -1"
    (h/when-pty
     ;; Ctrl-D (VEOF, 0x04) flushes the pending line to the reader, so the child
     ;; reads "ABC" then a clean EOF. (Closing the master would also surface EOF
     ;; but hangs up the session with SIGHUP, killing the child before it returns.)
     (let [r (h/run-pty-scenario {:scenario :read-until-eof}
                                 {:response-hex "4142430404" :wait-for-query? true})]
       (is (= -1 (:last (:result r))) "read terminated at EOF")
       (is (= 3 (:bytes-read (:result r))) "the prefix bytes were read before EOF")
       (is (false? (:timed-out r)) "the child did not hang")))))

(deftest stty-shim-kit-records-invocations
  (testing "a shim on PATH is invoked by the child's bare stty and records its argv"
    (h/when-pty
     (let [shim-dir (shims/make-shim-dir!)
           rec (str (io/file (System/getProperty "java.io.tmpdir")
                             (str "sc-rec-" (System/nanoTime))))
           r (h/run-pty-scenario {:scenario :spawn-stty}
                                 {:env {"PATH" (h/path-with shim-dir)
                                        "SHIM_RECORD_FILE" rec}})]
       (is (= 0 (:exit (:result r))) "silent-ok shim exits 0")
       (is (.exists (io/file rec)) "the shim wrote a record file")
       (is (str/includes? (slurp rec) "stty -g") "it recorded the stty -g call")))))

(deftest no-controlling-tty-fails-the-open
  (testing "in a session with no controlling terminal, opening /dev/tty fails"
    (h/when-pty
     (let [r (h/run-notty-scenario {:scenario :open-tty-probe})]
       (is (false? (:opened? (:result r))) "/dev/tty open did not succeed")))))
