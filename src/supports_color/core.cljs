(ns supports-color.core
  "Terminal color profile detection and background color querying (ClojureScript/Node.js).

   Ports the detection logic from:
   - chalk/supports-color  (Node.js env-var / flag heuristics)
   - charmbracelet/colorprofile (Go env-var detection + color adaptation)
   - termbg / lipgloss HasDarkBackground (OSC 11 background color query)

   Color profiles (keyword):
     :no-color  - no ANSI support, ASCII only
     :basic     - 16 color ANSI (level 1)
     :ansi256   - 256 color extended ANSI (level 2)
     :truecolor - 24-bit RGB / 16 million colors (level 3)

   Background detection chain (in priority order):
     1. CLITHEME env var         - user override, instant
     2. CSI ?996 query           - OS theme preference, fast (~5ms)
     3. OSC 11 query             - actual background RGB, widely supported
     4. COLORFGBG env var        - coarse fallback

   All functions are synchronous.
   On Node.js, terminal queries run in this process. The only subprocesses are
   `ps` (the foreground check) and `stty`, both run by absolute path with an
   empty env. `stty` gets /dev/tty as its stdin.
   All public functions accept an optional :debug key in their options map.
   All public functions return :env and :runtime maps for debugging."
  (:require [clojure.string :as str]
            [supports-color.common :as c]
            ["process" :as process]
            ["os" :as os]
            ["tty" :as tty]
            ["child_process" :as child-process]
            ["fs" :as fs]))

;; ---------------------------------------------------------------------------
;; Node.js terminal queries
;; ---------------------------------------------------------------------------
;; The queries run in this process. No `node` child is spawned, so nothing a host
;; assigns into process.env after startup (a project's .env loaded by
;; dotenv.config(), with NODE_OPTIONS or DYLD_INSERT_LIBRARIES in it) can run code
;; or stall a query, and no `node` is looked up on PATH. `background` blocks for
;; the length of the query either way.

(def ^:private setup-ms
  "Budget for each `stty` call that snapshots the terminal or enters raw mode,
   separate from the caller's read timeout."
  2000)

(def ^:private restore-ms
  "Fresh budget for the restoring `stty`, one the read loop cannot have used up.
   Generous on purpose: when it runs out, the `stty` is killed and the terminal
   stays without echo, so a slow restore on a loaded machine must still finish."
  3000)

(def ^:private max-response-chars
  "Cap on accumulated reply chars. A valid frame is well under 256."
  256)

(def ^:private resolved-stty
  ;; The first existing entry of c/stty-candidates, resolved once. Never a bare
  ;; PATH lookup and never read from the environment.
  (delay (some #(when (.existsSync fs %) %) c/stty-candidates)))

(defonce ^:private
  ^{:doc "Test hook: when set, the path used instead of the resolved `stty`. Not
          read from the environment. Only the Node test scenario runner sets it."}
  stty-override
  (atom nil))

(defn- stty-path
  "The `stty` to run: the test override when set, else the first existing
   candidate. nil when none exists, and the query is then skipped."
  []
  (or @stty-override @resolved-stty))

(def ^:private nap-cell
  ;; Never notified, so Atomics.wait always runs to its timeout.
  (js/Int32Array. (js/SharedArrayBuffer. 4)))

(defn- nap!
  "Block this thread for `ms`, so an EAGAIN poll on the non-blocking fd does not
   spin hot."
  [ms]
  (js/Atomics.wait nap-cell 0 0 ms))

(defn- run-stty
  "Runs `stty` with `args` and returns its stdout. Throws on a non-zero exit or
   when `ms` runs out.

   stdin is `tty-fd`, an open /dev/tty, not this process's stdin, so `cli < file`
   on a real terminal still snapshots, switches and restores the controlling
   terminal. The environment is empty, so nothing a host put into process.env at
   runtime (LD_PRELOAD, DYLD_*) reaches the subprocess. stty needs none of it."
  [stty tty-fd args ms]
  (.toString
   (.execFileSync child-process stty (clj->js args)
                  #js {:stdio   #js [tty-fd "pipe" "pipe"]
                       :timeout ms
                       :env     #js {}})
   "utf-8"))

(defn- read-reply
  "Polls the non-blocking `fd` and returns the accumulated reply. Stops when
   `done?` accepts the last 64 chars, on EOF or a read error, at
   `max-response-chars`, or when `timeout-ms` passes on the monotonic clock. EAGAIN
   naps 2 ms. The deadline, not a blocking read, ends the loop, so the caller's
   restore always runs in-process.

   Bytes before the first ESC are typeahead, not a reply. They are dropped, so a
   pasted block cannot fill the cap before the replies arrive.

   At the deadline, a reply that has started (`result` is not empty) but is not
   done gets one more `timeout-ms`. Over a slow link the rest is usually in
   flight, and once the terminal is restored it would be echoed on screen and
   queued as the host's input."
  [fd timeout-ms done?]
  (let [buf (.alloc js/Buffer 64)]
    (loop [result   ""
           deadline (+ (js/performance.now) timeout-ms)
           grace    timeout-ms]
      (if (>= (js/performance.now) deadline)
        (if (and (pos? grace) (seq result))
          (recur result (+ deadline grace) 0)
          result)
        (let [n (try (.readSync fs fd buf 0 64)
                     (catch :default e
                       (if (= "EAGAIN" (.-code e)) :again :error)))]
          (cond
            (= n :again)                  (do (nap! 2) (recur result deadline grace))
            (or (= n :error) (zero? n))   result
            :else
            (let [result (str result (.toString buf "utf-8" 0 n))
                  result (let [i (.indexOf result "\u001b")]
                           (if (neg? i) "" (subs result i)))
                  len    (count result)]
              (cond
                (>= len max-response-chars)      (subs result 0 max-response-chars)
                (done? (subs result (max 0 (- len 64)))) result
                :else                            (recur result deadline grace)))))))))

(defn- open-tty
  "Opens /dev/tty with `flags` and returns the fd, or nil when there is no
   controlling terminal."
  [flags]
  (try (.openSync fs "/dev/tty" flags)
       (catch :default _e nil)))

(def ^:private resolved-ps
  ;; The first existing entry of (c/tool-candidates "ps"), resolved once.
  (delay (some #(when (.existsSync fs %) %) (c/tool-candidates "ps"))))

(defn- ps-foreground?
  "Asks `ps`, whose stat column has `+` for a process in the foreground group
   (BSD and procps both print it). Returns true, false, or nil when it cannot
   tell (no `ps`, or `ps` failed)."
  []
  (when-let [ps @resolved-ps]
    (try
      (str/includes?
       (.toString
        (.execFileSync child-process ps
                       #js ["-o" "stat=" "-p" (str (.-pid process))]
                       #js {:stdio   #js ["ignore" "pipe" "ignore"]
                            :timeout setup-ms
                            :env     #js {}}))
       "+")
      (catch :default _e nil))))

(defn- proc-self-stat
  "The contents of /proc/self/stat, or nil where there is no /proc."
  []
  (try (.readFileSync fs "/proc/self/stat" "utf8")
       (catch :default _e nil)))

(defn- foreground?
  "Whether this process is in its terminal's foreground process group. Returns
   true, false, or nil when it cannot tell.

   `stty raw` from a background process group (`cli &` in a job-control shell)
   makes the kernel stop the whole job with SIGTTOU until the user types `fg`.
   Node exposes no tcgetpgrp. On Linux this reads /proc/self/stat, which needs
   no subprocess and works with busybox, whose `ps` has no `-p`. Elsewhere, or
   when /proc cannot be read, it asks `ps`."
  []
  (if-some [fg (c/proc-stat-foreground? (proc-self-stat))]
    fg
    (ps-foreground?)))

(defn- with-raw-tty-query
  "Runs one terminal query against /dev/tty and returns the reply string, nil
   when no query could be sent. Throws if an `stty` call fails.

   Opens /dev/tty for stty's stdin first. Without a controlling terminal the open
   fails and nothing runs. Then snapshots the line discipline with `stty -g`. An
   empty snapshot cannot be restored, so raw mode is never entered. Otherwise
   switches to raw/no-echo, writes `query`, and reads the reply until `done?`
   accepts it or `timeout-ms` passes.

   The query uses a second, non-blocking open of /dev/tty. It cannot share the
   stty fd: libuv clears O_NONBLOCK on any fd it passes to a child as stdin, and
   that flag belongs to the open file, so the read would block with the terminal
   raw. Two opens give two open files with their own flags.

   A snapshot that shows canonical input or echo already off means another
   process holds the terminal raw. The query then declines: no raw, no query, and
   no restore, since restoring would write that process's raw state back after
   it restored.

   Otherwise the `finally` restores the snapshot with its own budget, then closes
   the stty fd. An empty snapshot means raw was never entered, so the terminal is
   left alone. It is never reset with `stty sane`, which would wipe settings the
   user chose.

   Before any `stty`, a process outside its terminal's foreground group declines,
   because `stty raw` would stop it with SIGTTOU."
  [timeout-ms query done?]
  (when-let [stty (when-not (false? (foreground?)) (stty-path))]
    (when-let [stty-fd (open-tty (.. fs -constants -O_RDONLY))]
      (let [saved    (volatile! "")
            declined (volatile! false)]
        (try
          (vreset! saved (.trim (run-stty stty stty-fd ["-g"] setup-ms)))
          (cond
            (empty? @saved)
            nil

            ;; Another process holds the terminal raw. Leave it alone entirely.
            (false? (c/snapshot-cooked? @saved))
            (do (vreset! declined true) nil)

            :else
            (do
              ;; Non-canonical and no echo, but ISIG kept, so Ctrl-C and Ctrl-Z
              ;; typed during the query still interrupt or suspend the process.
              (run-stty stty stty-fd ["-icanon" "-echo" "min" "1" "time" "0"] setup-ms)
              (when-let [fd (open-tty (bit-or (.. fs -constants -O_RDWR)
                                              (.. fs -constants -O_NONBLOCK)))]
                (try
                  (.writeSync fs fd (.from js/Buffer query))
                  (read-reply fd timeout-ms done?)
                  (finally (.closeSync fs fd))))))
          (finally
            (when (and (seq @saved) (not @declined))
              (try (run-stty stty stty-fd [@saved] restore-ms)
                   (catch :default _e nil)))
            (.closeSync fs stty-fd)))))))

(defn- query-terminal-node
  "Sends the combined terminal query from Node.js in one raw window and returns
   the raw replies, or nil on error or timeout.

   CSI ?996n asks for the dark/light preference (supporting terminals answer
   CSI ?997;1n for dark or CSI ?997;2n for light). OSC 11 with a `?` payload asks
   for the default background colour (for example
   `\\u001b]11;rgb:3030/4444/5555\\u001b\\`). ESC[c is a Primary Device Attributes
   (DA1) query used as the sentinel: terminals answer in order, so once the DA1
   reply arrives everything earlier has too. Nothing else ends the read, not a
   CSI ?997 reply and not BEL or ESC backslash, which the user can type. The
   shared parsers pick the replies out of the buffer."
  [timeout-ms]
  (try
    (let [result (with-raw-tty-query
                   timeout-ms
                   "\u001b[?996n\u001b]11;?\u001b\\\u001b[c"
                   (fn [tail] (boolean (re-find #"\x1b\[\?[0-9;]*c$" tail))))]
      (when (seq result) result))
    (catch :default _e nil)))

;; ---------------------------------------------------------------------------
;; Platform registration
;; ---------------------------------------------------------------------------

(c/register-platform!
 {:real-get-env       (fn [k] (aget (.-env process) k))
  :stdout-tty         (fn [] (.isatty tty 1))
  :stderr-tty         (fn [] (.isatty tty 2))
  :stdin-tty         (fn [] (.isatty tty 0))
  :platform           (fn [] (case (.-platform process)
                               "win32"  :win32
                               "darwin" :macos
                               :unix))
  :os-release         (fn [] (.release os))
  :argv               (fn [] (vec (.-argv process)))
  :os-name            (fn [] (.-platform process))
  :query-terminal     query-terminal-node})

;; ---------------------------------------------------------------------------
;; Public API re-exports
;; ---------------------------------------------------------------------------

(def profile
  "Detect the terminal color profile for a given stream."
  c/profile)

(def background
  "Detect the terminal's background polarity and color (:polarity \"dark\"/\"light\"/nil, :background-color {:r :g :b} or nil)."
  c/background)

(def theme
  "Detect the terminal's background polarity only (:polarity \"dark\"/\"light\"/nil). Sends no terminal query when CLITHEME is dark or light."
  c/theme)

(def get-env
  "Reads env var k, consulting mock-env first if bound."
  c/get-env)

(def env-var-keys
  "All env var keys checked during detection."
  c/env-var-keys)
