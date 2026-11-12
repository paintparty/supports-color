(ns supports-color.read-loop-test
  "Tests for two read-loop-independent fixes: raw terminal
   bytes printed unescaped in debug mode, and no tty precondition before
   spawning the terminal queries.

   Unescaped debug output: the debug trace printed raw terminal replies through `pr-str`, which
   does not escape control bytes. Verified: (mapv int (pr-str (str (char 27)
   \"x\" (char 7)))) => [34 27 120 7 34], ESC and BEL pass through. A hostile
   writer to the tty (or a keystroke stolen during the raw window) could inject
   cursor/OSC sequences into the trace or a log someone later cats. The fix routes
   every raw reply through `dbg-raw`, which renders each byte below 0x20 or above
   0x7e as \\uXXXX and truncates to the response cap. Tested in-process against the
   private helper.

   Missing tty precondition: run-dark-light-chain and background spawned the stty-backed
   queries whenever the platform was non-Windows, never checking for a controlling
   terminal. A daemon or CI job with no tty paid two or three failing stty
   spawns plus startup latency, and reached the stty PATH-resolution surface where PATH is
   least trusted. The fix checks `have-terminal?` (a tty on stdout or stderr) at
   the query call sites. Tested under a pty child whose stdout and stderr are both
   redirected off the tty, so the query is skipped and no stty is spawned even
   though /dev/tty is still openable."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

;; --- raw replies are escaped before reaching the debug trace -----------------

(defn- dbg-raw []
  (some-> (requiring-resolve 'supports-color.common/dbg-raw) deref))

(deftest control-bytes-are-escaped
  (testing "a raw reply with ESC and BEL renders with no byte below 0x20"
    (if-let [f (dbg-raw)]
      (let [out (f (str (char 27) "x" (char 7) (char 0x9b) "y"))]
        (is (not (some #(< (int %) 0x20) out))
            (str "no control byte survives into the trace, got " (pr-str out)))
        (is (re-find #"\\u001[bB]" out) "ESC is shown as its \\u escape")
        (is (re-find #"\\u0007" out) "BEL is shown as its \\u escape"))
      (is false "supports-color.common/dbg-raw is not defined yet"))))

(deftest output-is-capped
  (testing "an over-long reply is truncated to the response cap"
    (if-let [f (dbg-raw)]
      ;; 500 ESC bytes, each rendered as the 6-char ; capped at 256 input
      ;; chars => 256*6 escaped chars + the two surrounding quotes.
      (let [out (f (apply str (repeat 500 (char 27))))]
        (is (= (+ 2 (* 256 6)) (count out))
            "input truncated to 256 chars before escaping"))
      (is false "supports-color.common/dbg-raw is not defined yet"))))

(deftest nil-reply-preserved
  (testing "a nil reply still prints as nil, matching the old pr-str output"
    (if-let [f (dbg-raw)]
      (is (= "nil" (f nil)))
      (is false "supports-color.common/dbg-raw is not defined yet"))))

;; --- no controlling terminal means no stty spawn ------------------------------

(defn- record-file []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "sc-readloop-rec-" (System/nanoTime)))))

(deftest no-terminal-no-stty-spawn
  (testing "with no tty on stdout or stderr, background spawns no stty"
    (h/when-pty
     ;; The child keeps its controlling pty (so /dev/tty is still openable, the
     ;; pre-fix code would spawn stty here), but fds 1 and 2 are redirected off
     ;; the tty, so `have-terminal?` is false and the query must be skipped.
     (let [rec (record-file)
           r (h/run-pty-scenario
              {:scenario :background :opts {:timeout-ms 100}
               :stty (str (shims/make-shim-dir!) "/stty")}
              {:env {"SHIM_RECORD_FILE" rec}
               :redir "1=null,2=null"})]
       (is (not (.exists (io/file rec)))
           "no stty was invoked when neither stdout nor stderr is a terminal")
       (is (map? (:return (:result r)))
           "background still returns its result map via COLORFGBG/none")))))

(deftest terminal-present-still-queries
  (testing "a real terminal on stdout/stderr is still queried (the check does not over-suppress)"
    (h/when-pty
     ;; Default pty child: fds 0/1/2 are the pty slave, so have-terminal? is true
     ;; and the query runs, proving the check does not suppress legitimate cases.
     (let [rec (record-file)
           r (h/run-pty-scenario
              {:scenario :background :opts {:timeout-ms 100}
               :stty (str (shims/make-shim-dir!) "/stty")}
              {:env {"SHIM_RECORD_FILE" rec}})]
       (is (.exists (io/file rec))
           "stty was invoked because a terminal is present on stdout/stderr")
       (is (map? (:return (:result r)))
           "background returns its result map")))))

;; --- the read loop: length cap, EOF handling, terminator matching, deadline --
;;
;; The read loop is a named private fn that takes an InputStream, a StringBuilder
;; and a monotonic nanoTime deadline, so its cap / EOF / terminator behavior is
;; driven directly with a stub stream: no pty, no SIGHUP fight (the pty test
;; harness cannot force a raw-mode EOF through the master).
;;
;; One combined query (CSI ?996n, OSC 11, DA1) is answered in order, so the loop
;; reads until the DA1 reply and nothing else ends it: not a CSI ?997 reply, not
;; the OSC terminators BEL or ESC backslash. Bytes before the first ESC are
;; typeahead and are discarded, so they never count toward the cap (audit C-05,
;; C-07).

(def ^:private ESC (char 27))
(def ^:private BEL (char 7))

(defn- read-terminal [] (some-> (requiring-resolve 'supports-color.core/read-terminal-response) deref))
(defn- parse-csi     [] (some-> (requiring-resolve 'supports-color.common/parse-csi996-response) deref))

(def ^:private da1 (str ESC "[?62;22c"))
(def ^:private osc11-frame (str ESC "]11;rgb:3030/4444/5555" ESC "\\"))

(defn- nano-deadline
  "A monotonic deadline `ms` in the future, matching what with-raw-tty-query
   builds."
  [ms]
  (+ (System/nanoTime) (* ms 1000000)))

(defn- bais [^String s]
  (java.io.ByteArrayInputStream. (.getBytes s "ISO-8859-1")))

(defn- eof-after
  "InputStream that yields the bytes of `s`, then reports a byte is available while
   `.read` returns -1, simulating the source closing mid-frame, the one shape a
   plain ByteArrayInputStream cannot produce (its `.read` is only reached once
   `.available` is 0)."
  [^String s]
  (let [bs  (.getBytes s "ISO-8859-1")
        n   (alength bs)
        idx (atom 0)]
    (proxy [java.io.InputStream] []
      (available [] (if (< @idx n) (- n @idx) 1))
      (read ([] (if (< @idx n)
                  (let [b (aget bs @idx)] (swap! idx inc) (bit-and b 0xff))
                  -1))))))

(defn- run-loop [f in ms] (f in (StringBuilder.) (nano-deadline ms)))

(deftest read-loop-is-defined
  (is (some? (read-terminal)) "supports-color.core/read-terminal-response is defined"))

(deftest length-cap
  (testing "an unterminated stream returns at most the response cap, promptly"
    (when-let [rd (read-terminal)]
      (let [start (System/nanoTime)
            out   (run-loop rd (bais (apply str ESC (repeat 400 "x"))) 100)]
        (is (<= (count out) 256) "the buffer is capped at 256")
        (is (< (- (System/nanoTime) start) (* 100 1000000))
            "and the cap fires before the deadline rather than polling it out")))))

(deftest typeahead-does-not-count-toward-the-cap
  (testing "a 300-byte paste queued before the replies is discarded, and the
            replies are still read in full"
    (when-let [rd (read-terminal)]
      (let [out (run-loop rd (bais (str (apply str (repeat 300 "a")) osc11-frame da1)) 5000)]
        (is (= (str osc11-frame da1) out))))))

(deftest eof-returns-partial
  (testing "EOF mid-frame returns the accumulated partial rather than nil"
    (when-let [rd (read-terminal)]
      (let [out (run-loop rd (eof-after (str ESC "[?99")) 5000)]
        (is (= (str ESC "[?99") out) "the partial prefix is returned on EOF, not nil")
        ;; The safety net: a partial that does not parse still degrades to nil,
        ;; so it never reaches the caller as a bogus reply.
        (is (nil? ((parse-csi) out))
            "the incomplete partial parses to nil (degrades cleanly)")))))

(deftest only-the-da1-reply-ends-the-read
  (testing "the loop reads every reply and stops at the DA1 reply"
    (when-let [rd (read-terminal)]
      (is (= (str ESC "[?997;1n" osc11-frame da1)
             (run-loop rd (bais (str ESC "[?997;1n" osc11-frame da1 "zz")) 5000))
          "a CSI ?997 reply does not end the read. The DA1 reply does, and
           nothing after it is consumed")
      (is (= (str ESC "[?997;2n") (run-loop rd (bais (str "n" ESC "[?997;2n")) 100))
          "a leading stray `n` is discarded. With no DA1, the read runs to the deadline")
      (is (= (str osc11-frame da1)
             (run-loop rd (bais (str BEL osc11-frame da1)) 5000))
          "a BEL typed before the replies neither ends the read nor is returned")
      (is (= (str ESC "\\" osc11-frame da1)
             (run-loop rd (bais (str ESC "\\" osc11-frame da1)) 5000))
          "ESC backslash (Alt-\\) before the replies does not end the read"))))

(deftest past-deadline-returns-immediately
  (testing "a deadline already in the past exits the loop at once (monotonic compare)"
    (when-let [rd (read-terminal)]
      (is (= "" (rd (bais "ignored") (StringBuilder.) (- (System/nanoTime) (* 10 1000000))))))))
