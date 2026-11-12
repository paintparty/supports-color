(ns supports-color.query-window-test
  "Tests for the raw window of a background query (2026-11-08 audit, C-01 to
   C-03, C-05, C-07, B-04, B-06).

     setup      the `stty -g` snapshot and the `stty raw` switch have their own
            budget, not the caller's read timeout. A raw switch that fails sends no
            query. No snapshot means the terminal is left alone, never reset with
            `stty sane`.
     one query  `background` sends one combined query, CSI ?996n, OSC 11 and the
            DA1 sentinel, in one raw window and reads until the DA1 reply. A
            terminal that answers everything at once therefore has every reply
            read, and nothing is left queued for the host or the shell.
     foreground a process in a background job (`cli &`) does not query, because
            `stty raw` from a background process group stops the whole job with
            SIGTTOU.

   Everything is skipped where no controlling-terminal pty is available."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(def ^:private csi997-dark-hex
  ;; ESC [ ? 997 ; 1 n, a dark-mode reply to CSI ?996n.
  "1b5b3f3939373b316e")

(def ^:private no-env-overrides
  ;; Mask CLITHEME and COLORFGBG from the test process's own environment, so the
  ;; chain result depends only on the terminal's replies.
  {:context {:env {"CLITHEME" nil "COLORFGBG" nil}}})

(defn- background-opts [timeout-ms]
  (assoc no-env-overrides :timeout-ms timeout-ms))

(defn- run-background
  "Run `background` in a pty child that answers the first query it sees with
   `reply-hex`, once."
  [spec opts reply-hex]
  (h/run-pty-scenario (merge {:scenario :background :opts (background-opts 400)} spec)
                      (merge {:wait-for-query? true :wait-go? true
                              :response-hex reply-hex}
                             opts)))

;; --- fix 8: setup has its own budget and its result is honored --------------

(defn- without-pendin
  "`stty -g` snapshot `s` with PENDIN (0x20000000) cleared in a macOS lflag. The
   tty driver sets that transient flag when a terminal returns to canonical mode
   with input pending, and clears it on the next read. It is not a user setting."
  [s]
  (str/replace (str s) #"lflag=([0-9a-fA-F]+)"
               (fn [[_ h]]
                 (str "lflag=" (Long/toHexString
                                (bit-and-not (Long/parseLong h 16) 0x20000000))))))

(deftest tiny-timeout-keeps-user-settings
  (testing "background with :timeout-ms 0 leaves the user's terminal settings as
            they were (no `stty sane`)"
    (h/when-pty
     (let [r (h/run-pty-scenario {:scenario :settings-survive} {:wait-go? true})
           res (:result r)]
       (is (= (without-pendin (:before res)) (without-pendin (:after res)))
           (str "-ixon survived. before " (:before res) " after " (:after res)))
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

(deftest failed-raw-sends-no-query
  (testing "when `stty raw` fails, no query is written, so no reply is echoed or
            left queued for the host"
    (h/when-pty
     (let [r (run-background {:scenario :background-then-pending
                              :stty (str (io/file (shims/make-shim-dir!) "stty"))}
                             {:env {"SHIM_MODE" "raw-exit1"}}
                             (str csi997-dark-hex h/osc11-reply-hex))
           res (:result r)]
       (is (map? (:return res)) "background still returns its result map")
       (is (= "" (:pending res))
           (str "no reply is queued on /dev/tty, saw " (pr-str (:pending res))))
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

(deftest slow-raw-still-queries
  (testing "a `stty raw` slower than :timeout-ms still completes, and the query runs"
    (h/when-pty
     (let [r (run-background {:stty (str (io/file (shims/make-shim-dir!) "stty"))
                              :opts (background-opts 100)}
                             {:env {"SHIM_MODE" "slow-raw"}}
                             (str csi997-dark-hex h/osc11-reply-hex))
           ret (:return (:result r))]
       (is (= :csi996 (:detection-method ret)) "the CSI ?997 reply was read")
       (is (= "dark" (:polarity ret)))
       (is (some? (:background-color ret)) "the OSC 11 colour was read")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

;; --- fix 7: one combined query, read until DA1 --------------------------------

(defn- burst-gives-colour [opts]
  ;; An xterm-like terminal: no CSI ?997 reply, then OSC 11, then DA1, sent once.
  (let [r (run-background {} opts h/osc11-reply-hex)
        ret (:return (:result r))]
    (is (= :osc11 (:detection-method ret)) "the OSC 11 reply decided the polarity")
    (is (= {:r 28 :g 28 :b 28} (:background-color ret)) "and gave the colour")
    (is (true? (:echo-after r)) "ECHO is on afterward")))

(deftest one-burst-answer-gives-colour
  (testing "a terminal that answers every query in one burst has every reply read"
    (h/when-pty (burst-gives-colour {}))))

(deftest node-one-burst-answer-gives-colour
  (testing "a terminal that answers every query in one burst has every reply read (Node)"
    (h/when-node-pty (burst-gives-colour {:runtime :node}))))

(deftest nothing-left-pending-after-background
  (testing "after a CSI ?997 reply, the OSC 11 and DA1 replies are read too and
            nothing is left queued for the host or the shell"
    (h/when-pty
     (let [r (run-background {:scenario :background-then-pending}
                             {}
                             (str csi997-dark-hex h/osc11-reply-hex))
           res (:result r)]
       (is (= :csi996 (:detection-method (:return res))))
       (is (some? (:background-color (:return res))))
       (is (= "" (:pending res))
           (str "nothing pending on /dev/tty, saw " (pr-str (:pending res))))))))

;; --- #7: slow links -------------------------------------------------------------

(def ^:private query-start-hex
  ;; ESC [ ? 996 n, the first bytes of the combined query. Delays count from here.
  "1b5b3f3939366e")

(def ^:private osc11-only-hex
  ;; The OSC 11 part of h/osc11-reply-hex, without the DA1 reply.
  "1b5d31313b7267623a316331632f316331632f316331631b5c")

(def ^:private da1-hex
  ;; ESC [ ? 62 ; 22 c, the DA1 part of h/osc11-reply-hex.
  "1b5b3f36323b323263")

(defn- slow-link [runtime]
  ;; The whole reply arrives 400 ms after the query, past the old 100 ms default.
  (let [r   (h/run-pty-scenario {:scenario :background-then-pending
                                 :opts     (assoc no-env-overrides :pending-wait-ms 600)}
                                {:runtime           runtime
                                 :wait-for-hex      query-start-hex
                                 :wait-go?          true
                                 :response-hex      h/osc11-reply-hex
                                 :response-delay-ms 400})
        res (:result r)]
    (is (= :osc11 (:detection-method (:return res))) "the late reply was read")
    (is (= {:r 28 :g 28 :b 28} (:background-color (:return res))))
    (is (= "" (:pending res)) (str "nothing pending on /dev/tty, saw " (pr-str (:pending res))))
    (is (true? (:echo-after r)) "ECHO is on afterward")))

(deftest slow-link-default-timeout
  (testing "with the default timeout, a reply 400 ms late is read, not left queued"
    (h/when-pty (slow-link nil))))

(deftest node-slow-link-default-timeout
  (testing "with the default timeout, a reply 400 ms late is read, not left queued (Node)"
    (h/when-node-pty (slow-link :node))))

(defn- split-reply [runtime]
  ;; The OSC 11 reply arrives at once and the DA1 reply 450 ms later, past the
  ;; 300 ms timeout. A reply has started, so the read gets one more window.
  (let [r   (h/run-pty-scenario {:scenario :background-then-pending
                                 :opts     (assoc no-env-overrides
                                                  :timeout-ms 300 :pending-wait-ms 600)}
                                {:runtime            runtime
                                 :wait-for-hex       query-start-hex
                                 :wait-go?           true
                                 :response-hex       osc11-only-hex
                                 :response2-hex      da1-hex
                                 :response2-delay-ms 450})
        res (:result r)]
    (is (= {:r 28 :g 28 :b 28} (:background-color (:return res))))
    (is (= "" (:pending res)) (str "the late DA1 reply was read, saw " (pr-str (:pending res))))
    (is (true? (:echo-after r)) "ECHO is on afterward")))

(deftest split-reply-gets-grace
  (testing "a reply that has started gets one more window to finish"
    (h/when-pty (split-reply nil))))

(deftest node-split-reply-gets-grace
  (testing "a reply that has started gets one more window to finish (Node)"
    (h/when-node-pty (split-reply :node))))

;; --- fix 6: no query from a background job ------------------------------------

;; /proc/self/stat lines captured from `cat` inside the harness pty on Debian 13.
;; Fields after the command name: state, ppid, pgrp, session, tty_nr, tpgid.

(def ^:private proc-stat-fg
  ;; Foreground: pgrp 13745 is the terminal's foreground group (tpgid 13745).
  "13746 (cat) R 13745 13745 13745 34817 13745 4194304 58 0 0 0 0 0 0 0 20 0 1 0 1272413 5988352 506 18446744073709551615 94259756863488 94259756886665 140731996879280 0 0 0 0 16781312 0 0 0 0 17 1 0 0 0 0 0 94259756902896 94259756904640 94260511039488 140731996880018 140731996880038 140731996880038 140731996880875 0")

(def ^:private proc-stat-bg
  ;; A `set -m` background job: pgrp 13747, foreground group 13745.
  "13747 (cat) R 13745 13747 13745 34817 13745 4194304 80 0 0 0 0 0 0 0 20 0 1 0 1272413 5988352 518 18446744073709551615 94046421295104 94046421318281 140735033668000 0 0 0 0 16781312 0 0 0 0 17 4 0 0 0 0 0 94046421334512 94046421336256 94046771875840 140735033674898 140735033674918 140735033674918 140735033675755 0")

(def ^:private proc-stat-notty
  ;; setsid with no controlling terminal: tty_nr 0, tpgid -1.
  "13751 (cat) R 13750 13750 13750 0 -1 4194304 57 0 0 0 0 0 0 0 20 0 1 0 1272415 5988352 540 18446744073709551615 94105196290048 94105196313225 140723226996272 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0 94105196329456 94105196331200 94105350754304 140723227004071 140723227004091 140723227004091 140723227004907 0")

(deftest proc-stat-says-foreground
  (testing "a /proc/<pid>/stat line says whether the process is in its
            terminal's foreground group (pgrp equals tpgid)"
    (if-let [fg? (requiring-resolve 'supports-color.common/proc-stat-foreground?)]
      (do
        (is (true? (fg? proc-stat-fg)) "foreground")
        (is (false? (fg? proc-stat-bg)) "background job")
        (is (false? (fg? proc-stat-notty)) "no controlling terminal")
        (is (false? (fg? "42 (a) b) R 1 42 42 34817 7 0"))
            "a command name with spaces and ) is skipped as a whole")
        (is (true? (fg? "42 (x (y) 2 3) S 1 42 42 34817 42 0"))
            "fields are counted from the last )")
        (is (nil? (fg? "42 (cat) R 1")) "too few fields -> nil")
        (is (nil? (fg? "not a stat line")) "unknown format -> nil")
        (is (nil? (fg? "")) "blank -> nil")
        (is (nil? (fg? nil)) "nil -> nil"))
      (is false "supports-color.common/proc-stat-foreground? is not defined yet"))))

(deftest proc-self-stat-is-readable-on-linux
  (testing "where /proc exists, this process reads its own stat line, so the
            foreground check never falls back to `ps` there. `slurp` throws on
            /proc files (they report size 0) on the JVM and bb, and the fallback
            hid it."
    (if (.exists (io/file "/proc/self/stat"))
      (if-let [v (requiring-resolve 'supports-color.core/proc-self-stat)]
        (is (boolean? (@(requiring-resolve 'supports-color.common/proc-stat-foreground?) (@v)))
            "/proc/self/stat read and parsed")
        (is false "supports-color.core/proc-self-stat is not defined yet"))
      (is true "no /proc here"))))

(defn- background-job
  "Run `background` as a background job of a job-control shell (`set -m`, then
   `runner &`). The shell polls the job and writes `exited` or `stopped` to
   STATE_FILE, killing a stopped job so the test ends.

   The job's state comes from /proc/$P/stat where /proc exists, and from `ps`
   otherwise. Busybox `ps` has no `-p`. When neither answers while the job is
   still alive (`kill -0`), the state is `unknown` and the test fails, rather
   than an empty answer reading as `exited`."
  [opts]
  (let [state (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "sc-job-" (System/nanoTime))))
        script (str "set -m\n"
                    "\"$@\" &\n"
                    "P=$!\n"
                    "state=timeout\n"
                    "i=0\n"
                    "while [ $i -lt 1000 ]; do\n"
                    "  if [ -r /proc/$P/stat ]; then\n"
                    "    l=; read -r l < /proc/$P/stat 2>/dev/null; s=${l##*\") \"}; s=${s%% *}\n"
                    "  else\n"
                    "    s=$(ps -o stat= -p $P 2>/dev/null)\n"
                    "  fi\n"
                    "  case \"$s\" in\n"
                    "    '') if kill -0 $P 2>/dev/null; then state=unknown; kill -KILL $P; else state=exited; fi; break ;;\n"
                    "    *Z*) state=exited; break ;;\n"
                    "    *T*) state=stopped; kill -KILL $P; break ;;\n"
                    "  esac\n"
                    "  sleep 0.02; i=$((i+1))\n"
                    "done\n"
                    "echo $state > \"$STATE_FILE\"\n"
                    "wait")
        r (h/run-pty-scenario {:scenario :background :opts (background-opts 300)}
                              (merge {:shell script :env {"STATE_FILE" state}
                                      :timeout-ms 30000}
                                     opts))]
    (assoc r :state (when (.exists (io/file state)) (str/trim (slurp state))))))

(deftest background-job-is-not-stopped
  (testing "`cli &` from a job-control shell finishes instead of stopping on SIGTTOU"
    (h/when-pty
     (let [r (background-job {})]
       (is (= "exited" (:state r)) "the job ran to completion")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

(deftest node-background-job-is-not-stopped
  (testing "`cli &` from a job-control shell finishes instead of stopping on SIGTTOU (Node)"
    (h/when-node-pty
     (let [r (background-job {:runtime :node})]
       (is (= "exited" (:state r)) "the job ran to completion")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

;; --- fix 9: Ctrl-C still interrupts during the window -------------------------

(defn- ctrl-c-during-query
  "Run a 3 s query whose only reply is Ctrl-C (0x03). The shell traps SIGINT with
   a handler, so it survives to record the runner's exit status, while the runner
   keeps the default disposition."
  [opts]
  (let [state (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "sc-intr-" (System/nanoTime))))
        r (h/run-pty-scenario {:scenario :query :opts {:timeout-ms 3000}}
                              (merge {:shell (str "trap 'true' INT\n"
                                                  "\"$@\"\n"
                                                  "echo $? > \"$STATE_FILE\"")
                                      :env {"STATE_FILE" state}
                                      ;; Reply only once ESC[?996n is on the
                                      ;; pty, not on a launcher's earlier output.
                                      :wait-for-hex "1b5b3f3939366e"
                                      :response-hex "03"
                                      :timeout-ms 30000}
                                     opts))]
    (assoc r :status (when (.exists (io/file state)) (str/trim (slurp state))))))

(deftest ctrl-c-during-query-interrupts
  (testing "Ctrl-C typed while a query holds the terminal interrupts the process
            and the terminal is restored"
    (h/when-pty
     (let [r (ctrl-c-during-query {})]
       (is (= "130" (:status r)) "the runner died of SIGINT (exit 130)")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

(deftest node-ctrl-c-during-query-interrupts
  (testing "Ctrl-C typed while a query holds the terminal interrupts the process
            and the terminal is restored (Node)"
    (h/when-node-pty
     (let [r (ctrl-c-during-query {:runtime :node})]
       (is (some-> (:status r) (not= "0")) "the runner was interrupted, not completed")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))
