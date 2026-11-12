(ns supports-color.harness
  "The pty test harness for supports-color's terminal-handling tests.

   The JVM query functions in `core.clj` hardcode `/dev/tty`, which the kernel
   resolves to the session's controlling terminal. It cannot be redirected with
   fds. So to stub the reply source, force ECHO on/off, or fail the tty open, the
   code must run in a *child process whose controlling terminal is a pty we
   drive*. `pty_harness.py` allocates that pty. `scenario_runner.clj` is the
   child. This namespace is the Clojure API over both.

   Runtimes: the child runs under the same runtime as the parent test. The JVM
   (`java clojure.main`) reuses this process's classpath, Babashka uses
   `--classpath src:test`, and jolt uses `-A:jolt-test` to put `test` on its
   source roots. All find `supports-color.scenario-runner`.

   Everything is skipped gracefully where a real pty is unavailable
   (`pty-available?`), keeping `bb test:all` green on Windows / minimal CI."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]))

(defn bb? [] (some? (System/getProperty "babashka.version")))

(defn jolt? [] (some? (System/getProperty "jolt.version")))

(defn- project-root [] (System/getProperty "user.dir"))

(defn- pty-script [] (str (io/file (project-root) "test/supports_color/pty_harness.py")))

(defn- child-cmd
  "Command vector that runs a scenario runner with `spec-edn` as its argument.
   `runtime` :node runs the ClojureScript queries under nbb (Node scenario
   runner). Anything else runs the runner under the same runtime as the parent:
   Babashka, jolt, or the JVM."
  [spec-edn runtime]
  (case runtime
    :node ["npx" "--yes" "nbb" "-cp" "src:test:test-node"
           "test-node/supports_color/node_scenario_runner.cljs" spec-edn]
    (cond
      (bb?)   ["bb" "--classpath" "src:test" "-m" "supports-color.scenario-runner" spec-edn]
      (jolt?) ["jolt" "-A:jolt-test" "-m" "supports-color.scenario-runner" spec-edn]
      :else   ["java" "-cp" (System/getProperty "java.class.path")
               "clojure.main" "-m" "supports-color.scenario-runner" spec-edn])))

;; --- capability check --------------------------------------------------------

(defn- unix? []
  (not (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win")))

(defn- python3-pty-ok? []
  (try
    (let [p (-> (ProcessBuilder. ["python3" "-c"
                                  "import pty,termios,os; m,s=pty.openpty(); os.close(m); os.close(s)"])
                (.redirectErrorStream true))
          proc (.start p)]
      (.waitFor proc)
      (zero? (.exitValue proc)))
    (catch Exception _ false)))

(def ^:private pty-cap (delay (and (unix?) (.exists (io/file (pty-script))) (python3-pty-ok?))))

(defn pty-available?
  "True when a real controlling-terminal pty child can be spawned here."
  []
  @pty-cap)

(defn- nbb-ok? []
  (try
    (let [p (-> (ProcessBuilder. ["npx" "--yes" "nbb" "--version"])
                (.redirectErrorStream true))
          proc (.start p)]
      (.waitFor proc)
      (zero? (.exitValue proc)))
    (catch Exception _ false)))

(def ^:private nbb-cap (delay (and (unix?) (nbb-ok?))))

(defn nbb-available?
  "True when the Node scenario runner can be driven here (Unix + nbb resolvable
   via npx). The probe is memoized since `npx nbb --version` is slow."
  []
  @nbb-cap)

;; --- running scenarios -----------------------------------------------------

(defn- read-if [f] (when (.exists (io/file f)) (slurp f)))

(defn- parse-status [s]
  (when s (edn/read-string (str "{" s "}"))))

(defn- run-process
  "Start pb, capturing merged stdout/stderr, and return {:exit :out}."
  [^ProcessBuilder pb]
  (let [proc (-> pb (.redirectErrorStream true) .start)
        out (slurp (.getInputStream proc))]
    (.waitFor proc)
    {:exit (.exitValue proc) :out out}))

(defn- env-into! [^ProcessBuilder pb m]
  (let [e (.environment pb)]
    (doseq [[k v] m] (.put e (str k) (str v))))
  pb)

(defn- shell-wrapper
  "The `sh -c` script for the :shell option. It runs `script`, in which \"$@\" is
   the runner command, with the runners' own done/go/result signalling unset.
   Then it signals done itself and waits for go. The shell stays the session
   leader holding the pty, as a login shell would, so ECHO is read after the
   runners exit. On macOS the pty's settings reset once its last slave fd closes."
  [script]
  (str "unset DONE_FILE GO_FILE WAIT_GO RESULT_FILE\n"
       script "\n"
       "touch \"$PTY_DONE_FILE\"\n"
       "while [ ! -e \"$PTY_GO_FILE\" ]; do sleep 0.02; done\n"))

(defn- run-via-python
  "Shared driver for both pty and notty modes. `spec` is the scenario map;
   `opts` may contain :mode (\"pty\"|\"notty\"), :env (child+python env overrides,
   e.g. PATH), :response-hex, :wait-for-query?, :close-after (:eof), :wait-go?,
   :timeout-ms, :wait-for-hex (send the reply only once the child's output
   contains these bytes), :response-delay-ms (send the reply that much later, as
   over a slow link), :response2-hex and :response2-delay-ms (a second reply
   chunk, sent that long after the first), and :shell, a script run by `sh -c` as the pty child
   with \"$@\" standing for the runner command (see `shell-wrapper`).
   Returns {:result :echo-after :exit :timed-out :elapsed-ms}."
  [spec {:keys [mode env response-hex wait-for-query? wait-for-hex close-after wait-go? timeout-ms redir runtime shell
                response-delay-ms response2-hex response2-delay-ms]
         :or {mode "pty" timeout-ms 8000}}]
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "sc-scn-" (System/nanoTime))) .mkdirs)
        path #(str (io/file dir %))
        result-file (path "result.edn")
        done-file (path "done")
        go-file (path "go")
        echo-file (path "echo")
        status-file (path "status")
        pb-env (merge
                {"RESULT_FILE" result-file
                 "DONE_FILE" done-file
                 "GO_FILE" go-file
                 "PTY_MODE" mode
                 "PTY_ECHO_FILE" echo-file
                 "PTY_STATUS_FILE" status-file
                 "PTY_DONE_FILE" done-file
                 "PTY_GO_FILE" go-file
                 "PTY_TIMEOUT_MS" (str timeout-ms)
                 "PTY_RESPONSE_HEX" (or response-hex "")
                 "PTY_WAIT_FOR_QUERY" (if wait-for-query? "1" "0")
                 "PTY_CLOSE_AFTER" (if (= close-after :eof) "eof" "")
                 ;; A known TERM, so tests do not depend on the test process's own.
                 ;; background skips its query when TERM is unset or dumb.
                 "TERM" "xterm-256color"}
                (when wait-go? {"WAIT_GO" "1"})
                (when redir {"PTY_REDIR" redir})
                (when wait-for-hex {"PTY_WAIT_FOR_HEX" wait-for-hex})
                (when response-delay-ms {"PTY_RESPONSE_DELAY_MS" (str response-delay-ms)})
                (when response2-hex {"PTY_RESPONSE2_HEX" response2-hex
                                     "PTY_RESPONSE2_DELAY_MS" (str (or response2-delay-ms 0))})
                env)
        cmd (child-cmd (pr-str spec) runtime)
        argv (into ["python3" (pty-script) "--"]
                   (if shell
                     (into ["sh" "-c" (shell-wrapper shell) "sh"] cmd)
                     cmd))
        {:keys [out]} (run-process (env-into! (ProcessBuilder. ^java.util.List argv) pb-env))
        echo (case (some-> (read-if echo-file) str/trim)
               "1" true "0" false nil)
        status (parse-status (read-if status-file))]
    (merge {:result (some-> (read-if result-file) edn/read-string)
            :echo-after echo
            :python-out out}
           status)))

(defn run-pty-scenario
  "Run `spec` under a controlling-terminal pty child."
  ([spec] (run-pty-scenario spec {}))
  ([spec opts] (run-via-python spec (assoc opts :mode "pty"))))

(defn run-notty-scenario
  "Run `spec` in a session with no controlling terminal (so /dev/tty open fails)."
  ([spec] (run-notty-scenario spec {}))
  ([spec opts] (run-via-python spec (assoc opts :mode "notty"))))

;; --- helpers ---------------------------------------------------------------

(def osc11-reply-hex
  "Hex for an OSC 11 reply, ESC ] 11 ; rgb:1c1c/1c1c/1c1c ESC \\, followed by a
   DA1 reply, ESC [ ? 62 ; 22 c, to pass as :response-hex. A real terminal
   answers the DA1 sentinel after the OSC 11 query, and the read loop stops at it."
  (str "1b5d31313b7267623a316331632f316331632f316331631b5c"
       "1b5b3f36323b323263"))

(defn osc11-reply?
  "True when `ret` contains the reply that `osc11-reply-hex` sends."
  [ret]
  (str/includes? (str ret) "]11;rgb:1c1c/1c1c/1c1c"))

(defn path-with
  "PATH string with `dir` prepended, for the :env of a scenario."
  [dir]
  (str dir java.io.File/pathSeparator (System/getenv "PATH")))

(defmacro when-pty
  "Body runs only when a pty is available. Otherwise logs a skip and passes."
  [& body]
  `(if (pty-available?)
     (do ~@body)
     (do (println "  [skip] pty harness unavailable on this platform")
         (is true))))

(defmacro when-node-pty
  "Body runs only when both a pty and nbb are available (the Node query tests
   drive a Node child through the pty). Otherwise logs a skip and passes."
  [& body]
  `(if (and (pty-available?) (nbb-available?))
     (do ~@body)
     (do (println "  [skip] node/nbb pty harness unavailable on this platform")
         (is true))))
