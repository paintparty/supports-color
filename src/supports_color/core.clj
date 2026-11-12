(ns supports-color.core
  "Terminal color profile detection and background color querying (JVM).

   Ports the detection logic from:
   - chalk/supports-color  (Node.js env-var / flag heuristics)
   - charmbracelet/colorprofile (Go env-var detection + color adaptation)
   - termbg / lipgloss HasDarkBackground (OSC 11 background color query)

   Color profiles (keyword):
     :no-color  - no ANSI support, ASCII only (level 0)
     :basic     - 16 color ANSI (level 1)
     :ansi256   - 256 color extended ANSI (level 2)
     :truecolor - 24-bit RGB / 16 million colors (level 3)

   Background color detection chain (in priority order):
     1. CLITHEME env var         - user override, instant
     2. CSI ?996 query           - OS theme preference, fast (~5ms)
     3. OSC 11 query             - actual background RGB, widely supported
     4. COLORFGBG env var        - coarse fallback

   All functions are synchronous.
   All public functions accept an optional :debug key in their options map.
   All public functions return :env and :runtime maps for debugging."

  (:require [clojure.string :as str]
            [supports-color.common :as c])
  (:import [java.io FileOutputStream InputStreamReader]))

;; ---------------------------------------------------------------------------
;; JVM terminal queries
;; ---------------------------------------------------------------------------

(def ^:private max-read-iterations
  "Hard ceiling on read-loop iterations, defense-in-depth against a deadline that
   never expires. timeout-ms is validated to <= 30000 upstream and each
   idle iteration sleeps >= 2ms, so a well-formed deadline always fires long before
   this bound; it only matters if a future change lets a non-finite deadline slip
   through, so the loop cannot spin forever with the terminal held raw."
  1000000)

(def ^:private max-response-chars
  "Hard cap on how many bytes a read loop will accumulate before returning what it
   has. A conforming CSI ?997 or OSC 11 frame is well under 64 bytes, so
   256 is generous; without it a terminal that streams bytes without ever sending
   a terminator would grow the buffer for the whole timeout window."
  256)

(def ^:private stty-path
  "Absolute path to the `stty` binary, resolved once as the first existing entry
   of `c/stty-candidates` rather than trusting the inherited PATH. A library
   consumer never asked to spawn a subprocess, least of all one resolved through
   a PATH they may not control (CI runners, dev containers, project-local
   node_modules/.bin), so every terminal-querying `stty` invocation goes through
   this absolute path. nil when `stty` is at none of those locations, in which
   case the query declines to run and detection falls through to COLORFGBG
   instead of executing a bare `stty`.

   No environment variable can change the path. An earlier SUPPORTS_COLOR_STTY
   override let the environment pick any binary, including a cwd-relative file,
   and `/bin/sh` turned the `stty sane` fallback into `/bin/sh sane`."
  (delay (->> c/stty-candidates
              (filter #(.exists (java.io.File. ^String %)))
              first)))

(defonce ^:private
  ^{:doc "Test hook: when set, the path used instead of `stty-path`. Not read
          from the environment. Only the test scenario runner sets it, to put a
          recording shim in place of the real `stty`."}
  stty-override
  (atom nil))

(defn- stty-bin
  "The `stty` to run: the test override when set, else `stty-path`."
  []
  (or @stty-override @stty-path))

(def ^:private sh-path
  "Absolute path to a POSIX `sh`, resolved once from an ordered candidate list
   rather than the inherited PATH, for the same reason `stty` is: the tty
   probe spawns `sh -c 'test -t <fd>'` and the foreground probe runs `ps` through
   `sh`, and a library must not resolve a
   subprocess a consumer never asked for through a PATH they may not control. nil
   when `sh` is at none of these locations, in which case the tty check declines
   and reports not-a-tty rather than running a bare `sh`.

   NixOS and Guix System provide /bin/sh. Termux has no /bin, so its `sh` is
   listed explicitly. No environment variable can change the path."
  (delay (->> ["/bin/sh"
               "/usr/bin/sh"
               "/data/data/com.termux/files/usr/bin/sh"]  ; Termux
              (filter #(.exists (java.io.File. ^String %)))
              first)))

(defonce ^:private
  ^{:doc "Test hook: when set, the path used instead of `sh-path`. Not read from
          the environment. Only the test scenario runner sets it."}
  sh-override
  (atom nil))

(defn- wait-or-kill!
  "Waits up to `budget-ms` for `proc` to exit, force-killing it if it overruns so a
   hung or wedged stty cannot stall the caller past its timeout. Every
   wait on an stty child once used the unbounded `.waitFor` overload; this replaces
   them with the timed overload plus a `destroyForcibly` on expiry.

   Returns true if the process exited on its own within the budget, false if it was
   killed. On interrupt it force-kills the child and re-raises InterruptedException
   so the caller can restore the terminal and re-set the interrupt flag rather than
   leaving a subprocess running behind a swallowed interrupt."
  [^Process proc budget-ms]
  (try
    (or (.waitFor proc budget-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (do (.destroyForcibly proc) false))
    (catch InterruptedException e
      (.destroyForcibly proc)
      (throw e))))

(def ^:private setup-timeout-ms
  "Wait budget for the `stty -g` snapshot, the `stty raw` switch and the `ps`
   foreground probe, separate from the caller's read timeout. A caller's tiny
   :timeout-ms once killed these children before they finished, so a busy machine
   or a 0 ms timeout either ran the query in cooked mode or reset the terminal
   with `stty sane`. The read timeout now bounds only the read."
  2000)

(defn- enable-raw-terminal-mode!
  "Switches /dev/tty to non-canonical, no-echo mode (`-icanon -echo min 1 time 0`)
   so terminal response bytes are immediately readable. Unlike `raw` this keeps
   ISIG, so Ctrl-C and Ctrl-Z typed during the query still interrupt or suspend
   the process, and the shutdown hook restores the terminal on the way out. Waits up to `setup-timeout-ms` for the `stty raw -echo`
   child, force-killing it if it overruns. Returns true only when the child
   exited 0 within the budget. Anything else means the terminal may still be
   cooked and echoing, so the caller must not write the query. A child killed
   mid-flight can leave the tty partially changed, so the caller runs its restore
   afterward either way, which is why this is invoked inside the restoring try."
  [stty]
  (let [proc (-> (ProcessBuilder. [stty "-icanon" "-echo" "min" "1" "time" "0"])
                 (.redirectInput (java.lang.ProcessBuilder$Redirect/from
                                  (java.io.File. "/dev/tty")))
                 ;; Nothing reads stderr, so discard it rather than leave a
                 ;; diagnostic to accumulate in an undrained pipe.
                 (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
                 .start)]
    (and (wait-or-kill! proc setup-timeout-ms)
         (zero? (.exitValue proc)))))

(def ^:private ps-path
  "Absolute path to `ps`, the first existing entry of `(c/tool-candidates \"ps\")`,
   or nil."
  (delay (->> (c/tool-candidates "ps")
              (filter #(.exists (java.io.File. ^String %)))
              first)))

(defn- ps-foreground?
  "Asks `ps`, whose stat column has `+` for a process in the foreground group
   (BSD and procps both print it). Returns true, false, or nil when it cannot
   tell (no `ps` or `sh`, or `ps` failed). bb has no way to get this process's
   pid, so `sh` execs `ps` on its own pid ($$): a child inherits the caller's
   process group, so the answer is the same. The env is empty, so no shell
   function or option is imported."
  []
  (when-let [ps @ps-path]
    (when-let [sh (or @sh-override @sh-path)]
      (try
        (let [pb   (ProcessBuilder. ^java.util.List [sh "-c" "exec \"$0\" -o stat= -p $$" ps])
              _    (.clear (.environment pb))
              proc (-> pb
                       (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
                       .start)]
          (when (and (wait-or-kill! proc setup-timeout-ms)
                     (zero? (.exitValue proc)))
            (str/includes? (slurp (.getInputStream proc)) "+")))
        (catch InterruptedException _e
          (.interrupt (Thread/currentThread))
          nil)
        (catch Exception _e nil)))))

(defn- proc-self-stat
  "The first line of /proc/self/stat, or nil where there is no /proc. Read with
   `.readLine`, because `slurp` throws `Invalid argument` on /proc files (they
   report size 0) on the JVM and bb."
  []
  (try
    (with-open [r (java.io.BufferedReader. (java.io.FileReader. "/proc/self/stat"))]
      (.readLine r))
    (catch Exception _e nil)))

(defn- foreground?
  "Whether this process is in its terminal's foreground process group. Returns
   true, false, or nil when it cannot tell.

   `stty raw` from a background process group (`cli &` in a job-control shell)
   makes the kernel stop the whole job with SIGTTOU until the user types `fg`.
   Neither the JVM nor Node exposes tcgetpgrp. On Linux this reads
   /proc/self/stat, which needs no subprocess and works with busybox, whose `ps`
   has no `-p`. Elsewhere, or when /proc cannot be read, it asks `ps`."
  []
  (if-some [fg (c/proc-stat-foreground? (proc-self-stat))]
    fg
    (ps-foreground?)))

(defonce ^:private terminal-lock
  ;; JVM-wide monitor serializing every /dev/tty save/raw/query/restore sequence.
  ;; Both query functions contend for the same controlling terminal, so a single
  ;; shared lock, not a per-function one, is what stops one query's restore from
  ;; racing another's raw switch and leaving the terminal stuck raw/no-echo.
  (Object.))

(def ^:private restore-timeout-ms
  "Independent wait budget for the restoring `stty`, separate from the caller's
   query timeout. By the time the restore runs, the read loop may have
   burned the entire query deadline, so budgeting the restore against the same
   deadline would force-kill the restoring stty and strand the terminal raw. The
   restore therefore gets its own fresh budget, generous enough for `stty` to
   finish on a heavily loaded machine. When it runs out, the `stty` is killed
   and the terminal stays without echo. Same 3 s as on Node."
  3000)

(defn- restore-terminal!
  "Restores /dev/tty's line discipline from `saved-settings`, the opaque string
   captured by `stty -g`. Only called once raw mode was attempted, which needs a
   trusted snapshot, so there is never a blank one to fall back from.

   Waits on its own `restore-timeout-ms` budget rather than the caller's query
   timeout. Unlike the query waits it does not abort on interrupt: leaving
   the terminal raw is worse than a delayed shutdown, so it keeps waiting for the
   restore to finish and re-raises the interrupt only afterward."
  [stty saved-settings]
  (let [^Process proc
        (-> (ProcessBuilder. [stty saved-settings])
            (.redirectInput (java.lang.ProcessBuilder$Redirect/from
                             (java.io.File. "/dev/tty")))
            ;; Discard stderr so a diagnostic from a failing restore cannot fill an
            ;; undrained pipe and block the child before it exits.
            (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
            .start)
        deadline (+ (System/nanoTime) (* restore-timeout-ms 1000000))]
    (loop [interrupted? false]
      (let [remaining-ms (quot (- deadline (System/nanoTime)) 1000000)]
        (cond
          ;; Restore finished on its own. Propagate any interrupt seen while waiting.
          (not (.isAlive proc))
          (when interrupted? (.interrupt (Thread/currentThread)))

          ;; The restore itself overran its budget. Kill it and give up, still
          ;; re-raising a pending interrupt.
          (<= remaining-ms 0)
          (do (.destroyForcibly proc)
              (when interrupted? (.interrupt (Thread/currentThread))))

          ;; Keep waiting. An interrupt here is remembered, not obeyed, so the
          ;; restore is never abandoned half-done with the terminal raw.
          :else
          (recur (or interrupted?
                     (try (.waitFor proc remaining-ms
                                    java.util.concurrent.TimeUnit/MILLISECONDS)
                          false
                          (catch InterruptedException _ true)))))))))

(defonce ^:private
  ^{:doc "The snapshot and stty of a query between entering raw mode and
          restoring, else nil. Read by the shutdown hook."}
  in-flight
  (atom nil))

(defn- restore-in-flight!
  "Shutdown hook body. When the process ends while a query holds the terminal raw
   (System/exit, SIGTERM, a bb script ending under a `future` prefetch), the
   query thread's `finally` never runs, so restore from `in-flight` here. Never
   takes `terminal-lock`, which the query thread holds. Restoring twice to the
   same snapshot is harmless."
  []
  (when-let [{:keys [stty saved]} @in-flight]
    (try (restore-terminal! stty saved)
         (catch Exception _e nil))))

(defonce ^:private shutdown-hook
  ;; Registered once, on the first query rather than at load, so a process that
  ;; never queries never gets a hook.
  (delay (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable restore-in-flight!))))

(defn- with-raw-tty-query
  "Runs one terminal query with the controlling terminal's line discipline saved,
   switched to raw/no-echo, and restored on every exit path.

   Serializes against `terminal-lock` so concurrent callers cannot interleave
   their save/raw/restore steps and strand the tty. Snapshots the terminal with
   `stty -g`, waits for that child on a bounded budget, then reads its line only
   after it has exited so a hung stty cannot block on the read. A snapshot
   is trusted only when the child exited cleanly with a non-blank line. A non-zero
   exit or an empty line means raw mode is never entered, so the terminal is left
   exactly as it was and the query returns nil. It is never reset with
   `stty sane`, which would wipe settings the user chose (-ixon, iutf8).

   The snapshot and the raw switch have their own `setup-timeout-ms` budget. The
   query is written only when the raw switch succeeded, since a cooked, echoing
   terminal would print the replies and queue them as the host's input.

   Before any `stty`, a process outside its terminal's foreground group declines,
   because `stty raw` would stop it with SIGTTOU.

   A snapshot that shows canonical input or echo already off means another
   process holds the terminal raw (a second copy of this library, a pager, a
   TUI). The query declines and returns nil without entering raw or restoring,
   since restoring would write that process's raw state back after it restored.

   While raw, the snapshot is kept in `in-flight` so the shutdown hook can
   restore it if the process ends mid-query.

   Otherwise it enters raw mode as the first statement inside the restoring try, so
   even a raw-switch child killed mid-flight on timeout is followed by the restore.
   It then writes `query-bytes` to /dev/tty and calls `read-loop` to collect the
   reply. `timeout-ms` bounds only that read.

   `read-loop` receives an open FileInputStream on /dev/tty, a StringBuilder, a
   monotonic `System/nanoTime` deadline, and the window length in nanoseconds (for
   the one extension a started reply gets), and returns the accumulated reply
   string.

   Returns the reply string, or nil on any error, interrupt, or missing snapshot.
   On interrupt the terminal is still restored and the interrupt flag re-set."
  [timeout-ms query-bytes read-loop]
  (locking terminal-lock
    ;; Only proceed if `stty` resolved to an absolute path. With no trusted binary
    ;; there is nothing safe to spawn, so decline and let detection fall through to
    ;; COLORFGBG rather than run a PATH-resolved `stty`.
    (when-let [stty (when-not (false? (foreground?)) (stty-bin))]
      @shutdown-hook
      (try
        (let [;; Ask stty for an opaque snapshot of the controlling terminal's line
              ;; discipline. Stdin may be redirected, so stty explicitly reads /dev/tty.
              ;; stderr is discarded so a diagnostic cannot accumulate unread.
              saved (-> (ProcessBuilder. [stty "-g"])
                        (.redirectInput (java.lang.ProcessBuilder$Redirect/from
                                         (java.io.File. "/dev/tty")))
                        (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
                        .start)
              ;; Wait for the snapshot child on a bounded budget, force-killing a
              ;; hung stty. Reading the line only once it has exited means
              ;; the blocking readLine cannot stall the caller either.
              exited?  (wait-or-kill! saved setup-timeout-ms)
              ;; The snapshot is emitted by stty on stdout and can later be passed back
              ;; verbatim to restore every terminal setting changed by this query.
              saved-settings (when exited?
                               (-> saved .getInputStream
                                   (java.io.InputStreamReader.)
                                   (java.io.BufferedReader.)
                                   .readLine))
              ;; Trust the snapshot only if stty actually succeeded. A non-zero
              ;; exit means the line, if any, is not a restorable setting.
              trusted? (and exited?
                            (zero? (.exitValue saved))
                            (not (str/blank? saved-settings)))]
          (cond
            ;; No usable snapshot: entering raw now would leave a terminal we cannot
            ;; put back. Leave the terminal alone and give up.
            (not trusted?)
            nil

            ;; Another process holds the terminal raw. Leave it alone entirely.
            (false? (c/snapshot-cooked? saved-settings))
            nil

            :else
            (try
              ;; Publish the snapshot for the shutdown hook, then disable canonical
              ;; input and echo. Both sit inside the try, so a raw-switch child killed
              ;; on timeout is still followed by the restore in the finally, which
              ;; the pre-try placement could skip.
              (reset! in-flight {:stty stty :saved saved-settings})
              ;; A failed or killed raw switch leaves the terminal cooked and
              ;; echoing. Writing the query then would print the replies and queue
              ;; them as the host's input, so skip straight to the restore.
              (when (enable-raw-terminal-mode! stty)
                ;; Write directly to the controlling terminal rather than System/out,
                ;; which may point at a file or pipe. with-open guarantees closure if
                ;; either the write or flush fails.
                (with-open [tty-out (FileOutputStream. "/dev/tty")]
                  (.write tty-out query-bytes)
                  (.flush tty-out))
                ;; Read responses from the same controlling TTY, independently of stdin.
                (let [tty-in   (java.io.FileInputStream. "/dev/tty")
                      ;; Replies are short but can arrive byte-by-byte, so retain them
                      ;; in a mutable buffer until a recognized final byte appears.
                      buf      (StringBuilder.)
                      ;; Bound polling so unsupported terminals do not stall startup.
                      ;; Use a monotonic nanoTime deadline so a backward wall-
                      ;; clock step (NTP, operator change) landing in the read window
                      ;; cannot extend the raw/no-echo period. timeout-ms is millis.
                      deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
                  (try
                    (read-loop tty-in buf deadline (* timeout-ms 1000000))
                    ;; Close the reader in its own finally so a close throw cannot
                    ;; pre-empt the restore in the outer finally below.
                    (finally (.close tty-in)))))
              (finally
                ;; Always restore the exact saved settings, including on timeout or
                ;; error, so the caller is never left with a raw, non-echoing tty.
                ;; Clear `in-flight` only after the restore, so an exit in between
                ;; still restores from the hook.
                (restore-terminal! stty saved-settings)
                (reset! in-flight nil)))))
        ;; An interrupt during any wait unwinds here after the finally has already
        ;; restored the tty. Re-set the flag the wait cleared rather than swallowing
        ;; it, so shutdown logic keyed on the interrupt still fires.
        (catch InterruptedException _e
          (.interrupt (Thread/currentThread))
          nil)
        (catch Exception _e nil)))))

(defn- read-terminal-response
  "Read loop for the combined terminal query: accumulate bytes from `in` into
   `buf` until the DA1 reply ESC [ ? ... c arrives, then return the accumulated
   string. `deadline` is a monotonic `System/nanoTime` value.

   The query is CSI ?996n, OSC 11 and DA1, and terminals answer in order, so once
   the DA1 reply arrives every earlier reply has arrived too. Nothing else ends
   the read: not a CSI ?997 reply, which would leave the OSC 11 and DA1 replies
   queued for the host, and not BEL or ESC backslash, which the user can type.

   Bytes before the first ESC are typeahead, not a reply. They are discarded, so
   a pasted block cannot fill the `max-response-chars` cap before the replies
   arrive. (The bytes are already consumed from /dev/tty, so they cannot reach
   the shell either way.) On EOF (`.read` = -1) the partial buffer is returned
   rather than nil, matching the timeout branch, so the parsers decide.

   At the deadline, a reply that has started (`buf` is not empty) but has not
   reached its DA1 reply gets one more window of `grace-ns`. Over a slow link the
   rest is usually in flight, and once the terminal is restored it would be
   echoed on screen and queued as the host's input. The 3-arity reads with no
   extension."
  ([in buf deadline] (read-terminal-response in buf deadline 0))
  ([^java.io.InputStream in ^StringBuilder buf deadline grace-ns]
   (loop [iters 0 deadline deadline grace-ns grace-ns]
     (cond
       ;; Hard iteration ceiling so a misbehaving (e.g. non-finite) deadline can
       ;; never spin this loop forever with the terminal held raw.
       (>= iters max-read-iterations)
       (str buf)

       ;; Length cap: a conforming set of replies is well under this, so an
       ;; unterminated stream returns here instead of growing without bound.
       (>= (.length buf) max-response-chars)
       (str buf)

       ;; A reply has started but DA1 has not arrived: read one more window.
       (and (>= (System/nanoTime) deadline) (pos? grace-ns) (pos? (.length buf)))
       (recur (inc iters) (+ deadline grace-ns) 0)

       ;; A timeout can still return partial replies. The parsers reject anything
       ;; incomplete.
       (>= (System/nanoTime) deadline)
       (str buf)

       (pos? (.available in))
       (let [b (.read in)]
         (if (= b -1)
           ;; EOF mid-read: return the partial buffer rather than nil, matching
           ;; the timeout branch above.
           (str buf)
           (do
             (.append buf (char b))
             (cond
               ;; Discard anything that does not start a reply. Only the first byte
               ;; can fail this and `buf` is one char here, so it is not a
               ;; full-buffer scan.
               (not= (int (.charAt buf 0)) 27)
               (do (.setLength buf 0) (recur (inc iters) deadline grace-ns))

               ;; The DA1 reply can only complete when the new byte is `c`, so the
               ;; regex runs only then, anchored at the end of the buffer.
               (and (= b (int \c)) (re-find #"\x1b\[\?[0-9;]*c$" (str buf)))
               (str buf)

               :else
               (recur (inc iters) deadline grace-ns)))))

       ;; No byte is ready yet. Briefly yield the thread before polling again.
       :else
       (do (Thread/sleep 2) (recur (inc iters) deadline grace-ns))))))

(defn- query-terminal-jvm
  "Sends the combined terminal query from the JVM in one raw window and returns
   the raw replies, or nil on error.

   CSI ?996n asks for the dark/light preference (supporting terminals answer
   CSI ?997;1n for dark or CSI ?997;2n for light). OSC 11 with a `?` payload asks
   for the default background colour (for example
   `\\u001b]11;rgb:3030/4444/5555\\u001b\\`). ESC[c is a Primary Device Attributes
   (DA1) query that nearly every terminal answers, used as the sentinel: it is
   answered last, so the read stops as soon as everything has arrived instead of
   waiting out the timeout on a query the terminal does not support. The shared
   parsers pick the replies out of the buffer."
  [timeout-ms]
  (with-raw-tty-query
    timeout-ms
    (.getBytes (str (char 27) "[?996n"
                    (char 27) "]11;?" (char 27) "\\"
                    (char 27) "[c")
               "UTF-8")
    read-terminal-response))

;; ---------------------------------------------------------------------------
;; Platform registration
;; ---------------------------------------------------------------------------

(defn- windows?
  "True on Windows, where there is no `test -t` and the tty check falls back to a
   JDK-version-specific System/console probe."
  []
  (-> (System/getProperty "os.name" "") c/lower-case-ascii (.contains "win")))

(def ^:private tty-probe-timeout-ms
  "Bounded wait for the `test -t` probe child, following the same convention as
   every other spawned child: no wait is unbounded. `test` exits immediately, so this is
   only a safety net against a wedged shell."
  2000)

(defn- unix-tty?
  "True when file descriptor `fd` (0=stdin, 1=stdout, 2=stderr) is a terminal in
   this process, determined by spawning `sh -c 'test -t <fd>'` with this process's
   descriptors inherited (.inheritIO), so the child sees exactly our fds, and
   reading its exit status. 0 means `fd` is a tty.

   The env is empty. `test` is a shell builtin, so it needs no PATH, and bash
   (the /bin/sh of macOS and most Linux distributions other than Debian) would
   otherwise import an exported function named `test` (BASH_FUNC_test%%) or
   SHELLOPTS=xtrace with PS4, and answer wrongly or print trace lines on the
   user's terminal.

   Replaces the old `(not= nil (System/console))`, which was one process-wide check
   that could not tell the three descriptors apart and is silently wrong on JDK
   22-24, where System.console returns non-null under redirection. Shelling out
   behaves identically on every JDK and under GraalVM native-image, which rules out
   JNA/FFM here. A missing `sh` or any failure degrades to false."
  [fd]
  (boolean
   (when-let [sh (or @sh-override @sh-path)]
     (try
       (let [pb            (ProcessBuilder. ^java.util.List [sh "-c" (str "test -t " fd)])
             _             (.clear (.environment pb))
             ^Process proc (-> pb .inheritIO .start)]
         (if (.waitFor proc tty-probe-timeout-ms
                       java.util.concurrent.TimeUnit/MILLISECONDS)
           (zero? (.exitValue proc))
           (do (.destroyForcibly proc) false)))
       (catch Exception _e false)))))

(defn- windows-tty?
  "Windows fallback for the tty check: no `test -t` exists, so probe System/console.
   Prefer Console.isTerminal on JDK 22+, where a bare null check is unreliable, and
   fall back to a non-null check on older JDKs."
  []
  (let [^java.io.Console console (System/console)]
    (cond
      (nil? console)                       false
      (>= (.feature (Runtime/version)) 22) (.isTerminal console)
      :else                                true)))

(defn- tty?
  "Whether descriptor `fd` is a terminal, dispatched by platform: Unix shells out to
   `test -t`, Windows probes System/console."
  [fd]
  (if (windows?) (windows-tty?) (unix-tty? fd)))

;; One delay per descriptor so the probe subprocess is paid at most once per
;; descriptor per process. The registered predicates just deref theirs.
(def ^:private stdout-tty (delay (tty? 1)))
(def ^:private stderr-tty (delay (tty? 2)))
(def ^:private stdin-tty  (delay (tty? 0)))

(c/register-platform!
 {:real-get-env       (fn [k] (System/getenv k))
  :stdout-tty         (fn [] @stdout-tty)
  :stderr-tty         (fn [] @stderr-tty)
  :stdin-tty         (fn [] @stdin-tty)
  :platform           (fn [] (let [os-name (-> (System/getProperty "os.name" "") c/lower-case-ascii)]
                               (cond
                                 (.contains os-name "win") :win32
                                 (.contains os-name "mac") :macos
                                 :else                     :unix)))
  :os-release         (fn [] (System/getProperty "os.version" "0.0.0"))
  :argv               (fn [] (vec *command-line-args*))
  :os-name            (fn [] (System/getProperty "os.name" ""))
  :query-terminal     query-terminal-jvm})

;; ---------------------------------------------------------------------------
;; Public API re-exports
;; ---------------------------------------------------------------------------

(def profile
  "Detect the terminal color profile for a given stream."
  c/profile)

(def background
  "Detect the terminal's background polarity and color (:polarity \"dark\"/\"light\"/nil, :background-color {:r :g :b} or nil)."
  c/background)

(def get-env
  "Reads env var k, consulting mock-env first if bound."
  c/get-env)

(def env-var-keys
  "All env var keys checked during detection."
  c/env-var-keys)
