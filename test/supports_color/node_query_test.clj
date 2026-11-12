(ns supports-color.node-query-test
  "Coverage for the ClojureScript/Node terminal queries (core.cljs),
   driven through the pty test harness with a Node scenario runner
   (`:runtime :node`). These assert the query-robustness fixes ported from the
   JVM tests:

     ESC-led frame requirement  the CSI ?996 read requires an ESC-led frame, so a stray keystroke
             during the raw window is not returned as the response.
     blank snapshot handling  a blank `stty -g` snapshot never enters raw mode and restores via
             `stty sane`, so the tty is not stranded raw.
     timeout handling  a query that times out with no reply still exits
             cleanly and leaves ECHO on (raw mode never leaks).

   And that the query runs in the Node host itself, with no `node -e` child
   (2026-11-08 audit, A-01 to A-03, A-06, C-08):

     runtime env  values a host assigns into process.env after startup (as
             dotenv.config() does) reach no subprocess: NODE_OPTIONS runs no
             payload and cannot stall the query, and stty gets an empty env.
     PATH         no `node` is resolved through PATH.
     stdin        with stdin redirected from a file, stty still targets /dev/tty
             and the query runs.
     latency      an answering terminal replies within one Node-startup-free
             round trip.

   The parent (this JVM/bb process) spawns the Node child via nbb, so the whole
   file depends on `when-node-pty`. It skips gracefully where node/nbb or a pty
   is unavailable (e.g. the JVM-only CI jobs)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(defn- record-file []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "sc-cnode-rec-" (System/nanoTime)))))

(defn- tmp-dir! []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "sc-cnode-dir-" (System/nanoTime)))
    .mkdirs))

(def ^:private osc11-reply? h/osc11-reply?)

(defn- run-osc11-answered
  "Run :query under Node against a pty that answers with
   `h/osc11-reply-hex` once the query is seen. `spec` and `opts` merge into the
   defaults."
  [spec opts]
  (h/run-pty-scenario (merge {:scenario :query :opts {:timeout-ms 400}} spec)
                      (merge {:runtime :node :wait-for-query? true :wait-go? true
                              :response-hex h/osc11-reply-hex}
                             opts)))

(deftest r1-012-csi996-requires-esc-led-frame
  (testing "a stray leading 'n' does not terminate the read. The ESC-led
            CSI ?997 frame does, and is returned in full"
    (h/when-node-pty
     (let [;; 'n' (6e) then ESC[?997;1n. The pre-fix loop stopped on the bare n.
           hex "6e1b5b3f3939373b316e"
           r (h/run-pty-scenario {:scenario :query :opts {:timeout-ms 400}}
                                 {:runtime :node :wait-for-query? true
                                  :response-hex hex :wait-go? true})
           ret (:return (:result r))]
       (is (some? ret) "a response was returned")
       (is (str/includes? ret "[?997;1n")
           "the full ESC-led frame is present in the returned bytes")
       (is (not= ret "n") "the bare stray keystroke is not the response")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))

(deftest r1-006-blank-snapshot-does-not-leak-raw
  (testing "with stty -g yielding no snapshot, the Node query returns nil, never
            enters raw, and never resets the terminal with `stty sane`"
    (h/when-node-pty
     (let [rec (record-file)
           r (h/run-pty-scenario
              {:scenario :query :opts {:timeout-ms 150}
               :stty (str (io/file (shims/make-shim-dir! {:mode "g-empty-raw-ok" :record rec})
                                   "stty"))}
              {:runtime :node :wait-go? true})
           calls (when (.exists (io/file rec)) (slurp rec))]
       (is (nil? (:return (:result r))) "a query with no restorable snapshot returns nil")
       (is (some? calls) "the shim recorded the stty calls")
       (is (str/includes? calls "stty -g") "it snapshotted the tty first")
       (is (not (str/includes? calls "sane")) "the user's settings are never reset with `stty sane`")
       (is (not (str/includes? calls "-icanon")) "raw mode is never entered without a valid snapshot")
       (is (true? (:echo-after r)) "ECHO is still on afterward")))))

(deftest timeout-does-not-leak-raw
  (testing "a query that gets no reply times out, returns nil, and leaves ECHO on"
    (h/when-node-pty
     (let [r (h/run-pty-scenario {:scenario :query :opts {:timeout-ms 120}}
                                 {:runtime :node :wait-go? true})]
       (is (nil? (:return (:result r))) "no reply -> nil")
       (is (false? (:timed-out r)) "the query returns at its own deadline, not the harness kill")
       (is (true? (:echo-after r)) "ECHO is restored after a timed-out query")))))

(defn- slow-restore-keeps-echo [runtime]
  ;; The restoring `stty` takes 2.2 s, as on a heavily loaded machine. A restore
  ;; budget shorter than that kills it and leaves the terminal without echo. Both
  ;; runtimes allow 3 s.
  (let [stty (str (io/file (shims/make-shim-dir! {:mode "slow-restore"}) "stty"))
        r    (h/run-pty-scenario {:scenario :query :opts {:timeout-ms 120} :stty stty}
                                 {:runtime runtime :wait-go? true})]
    (is (false? (:timed-out r)) "the query returns")
    (is (true? (:echo-after r)) "ECHO is restored by the slow restore")))

(deftest node-slow-restore-keeps-echo
  (testing "a restoring stty that takes 2.2 s still restores the terminal (Node)"
    (h/when-node-pty (slow-restore-keeps-echo :node))))

(deftest slow-restore-keeps-echo-jvm
  (testing "a restoring stty that takes 2.2 s still restores the terminal"
    (h/when-pty (slow-restore-keeps-echo nil))))

(deftest a01-runtime-node-options-runs-nothing
  (testing "NODE_OPTIONS assigned into process.env after startup, as dotenv.config()
            does with a cloned repo's .env, runs no payload during a query"
    (h/when-node-pty
     (let [dir (tmp-dir!)
           rec (str (io/file dir "record"))
           payload (io/file dir "payload.js")
           _ (spit payload (str "require('fs').appendFileSync(" (pr-str rec)
                                ", 'payload ran\\n');\n"))
           r (run-osc11-answered
              {:runtime-env {"NODE_OPTIONS" (str "--require " (.getAbsolutePath payload))}}
              {})]
       (is (not (.exists (io/file rec))) "the payload named in NODE_OPTIONS never ran")
       (is (osc11-reply? (:return (:result r))) "the terminal's reply is returned")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))

(deftest a02-node-not-resolved-through-path
  (testing "a `node` placed first on PATH at runtime is never run by a query"
    (h/when-node-pty
     (let [{:keys [dir record]} (shims/make-node-recorder!)
           r (run-osc11-answered {:runtime-env {"PATH" (h/path-with dir)}} {})]
       (is (not (.exists (io/file record))) "the PATH `node` was never run")
       (is (osc11-reply? (:return (:result r))) "the terminal's reply is returned")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))

(deftest a03-inspect-brk-does-not-stall
  (testing "NODE_OPTIONS=--inspect-brk assigned at runtime does not stall a query"
    (h/when-node-pty
     (let [r (h/run-pty-scenario
              {:scenario :query :opts {:timeout-ms 100}
               :runtime-env {"NODE_OPTIONS" "--inspect-brk=127.0.0.1:0"}}
              ;; Room for the pre-fix 5.1 s stall, so a red run still reports.
              {:runtime :node :wait-go? true :timeout-ms 15000})
           res (:result r)]
       (is (some-> (:elapsed-ms res) (< 1500))
           "a 100 ms query with no reply returns within 1.5 s")
       (is (nil? (:return res)) "no reply -> nil")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

(deftest a06-stdin-redirected-still-queries
  (testing "with stdin redirected from a file, stty targets /dev/tty and the
            query still runs (`cli < file` on a real terminal)"
    (h/when-node-pty
     (let [r (run-osc11-answered {} {:redir "0=file"})]
       (is (osc11-reply? (:return (:result r))) "the terminal's reply is returned")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))

(deftest c08-answering-terminal-is-fast
  (testing "a query against a terminal that answers at once costs no Node startup"
    (h/when-node-pty
     (let [r (run-osc11-answered {} {})
           res (:result r)]
       (is (osc11-reply? (:return res)) "the terminal's reply is returned")
       (is (some-> (:elapsed-ms res) (< 150))
           "the answered query returns in under 150 ms")))))

(deftest stty-gets-no-runtime-env
  (testing "values assigned into process.env at runtime do not reach the stty
            subprocess (the LD_PRELOAD / DYLD_* route from a loaded .env)"
    (h/when-node-pty
     (let [rec (record-file)
           r (h/run-pty-scenario
              {:scenario :query :opts {:timeout-ms 100}
               :runtime-env {"SHIM_ENV_PROBE" "leaked"}
               :stty (str (io/file (shims/make-shim-dir! {:record rec}) "stty"))}
              {:runtime :node :wait-go? true})
           calls (when (.exists (io/file rec)) (slurp rec))]
       (is (some-> calls (str/includes? "stty -g")) "the stty shim ran")
       (is (not (some-> calls (str/includes? "env-probe")))
           "the runtime env var did not reach stty")
       (is (true? (:echo-after r)) "ECHO is on afterward")))))

;; --- SUPPORTS_COLOR_STTY is not read (DEC-1, audit A-04 and A-05) -----------

(deftest node-stty-env-override-is-ignored
  (testing "SUPPORTS_COLOR_STTY in the launch env does not choose the stty binary"
    (h/when-node-pty
     (let [rec (record-file)
           r (run-osc11-answered
              {}
              {:env {"SUPPORTS_COLOR_STTY"
                     (str (io/file (shims/make-shim-dir! {:record rec}) "stty"))}})]
       (is (not (.exists (io/file rec))) "the shim named by SUPPORTS_COLOR_STTY never ran")
       (is (osc11-reply? (:return (:result r))) "the query ran through the real stty")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))

(deftest a04-relative-override-runs-no-cwd-file
  (testing "SUPPORTS_COLOR_STTY=./stty assigned at runtime does not run an stty
            from the directory the host has moved into"
    (h/when-node-pty
     (let [rec (record-file)
           cwd (shims/make-shim-dir! {:record rec})
           r (run-osc11-answered {:chdir cwd
                                  :runtime-env {"SUPPORTS_COLOR_STTY" "./stty"}}
                                 {})]
       (is (not (.exists (io/file rec))) "the cwd ./stty never ran")
       (is (osc11-reply? (:return (:result r))) "the query ran through the real stty")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))

(deftest a05-sh-override-runs-no-sane-script
  (testing "SUPPORTS_COLOR_STTY=/bin/sh assigned at runtime does not turn the
            `stty sane` fallback into `/bin/sh sane`, which runs a cwd file"
    (h/when-node-pty
     (let [dir (tmp-dir!)
           rec (str (io/file dir "record"))
           _ (spit (io/file dir "sane") (str "echo sane ran >> " (pr-str rec) "\n"))
           r (run-osc11-answered {:chdir (str dir)
                                  :runtime-env {"SUPPORTS_COLOR_STTY" "/bin/sh"}}
                                 {})]
       (is (not (.exists (io/file rec))) "the cwd `sane` script never ran")
       (is (osc11-reply? (:return (:result r))) "the query ran through the real stty")
       (is (true? (:echo-after r)) "ECHO is restored after the query")))))
