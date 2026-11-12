(ns supports-color.shims
  "Generates a fake `stty` so tests can observe and steer the subprocess calls
   made by `core.clj` and `core.cljs`. The source resolves stty to a fixed
   absolute path, so a shim cannot shadow it on PATH. Pass `<make-shim-dir!>/stty`
   as the scenario spec's `:stty`, and the scenario runner puts it in place
   through core's private test hook.

   One shell template, parameterised by env vars read at invocation time:

     SHIM_MODE        silent-ok       print nothing, exit 0 (default)
                      exit1           print nothing, exit 1
                      sleep           sleep SHIM_SLEEP_SECS, then exit 0
                      g-empty-raw-ok  `-g` prints nothing & exits 0, but
                                      `raw -echo` and a restore still succeed
                      g-line-exit1    `-g` prints a plausible snapshot line but
                                      exits 1. Only code that ignores the exit
                                      status trusts the snapshot
                      raw-exit1       the raw switch (`-icanon ...`) exits 1
                                      without changing anything, every other
                                      call runs the real /bin/stty
                      slow-raw        the raw switch sleeps 0.3 s, then every
                                      call runs the real /bin/stty
                      slow-restore    the restore (`stty <snapshot>`) sleeps
                                      2.2 s, as on a heavily loaded machine,
                                      then every call runs the real /bin/stty
     SHIM_RECORD_FILE if set, append this call's argv (one line) before acting
     SHIM_SLEEP_SECS  seconds for SHIM_MODE=sleep (default 3)
     SHIM_ENV_PROBE   if set, also append `env-probe <value>` to the record file,
                      so a test can see whether an env var reached stty

   `make-shim-dir!` writes the script to a fresh temp dir and returns that dir.
   Pass `<dir>/stty` as the spec's `:stty`. The Node query runs stty
   with an empty environment, so for it pass `{:mode .. :record ..}` to
   `make-shim-dir!`, which writes those values into the script itself."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private shim-script
  "#!/bin/sh
if [ -n \"$SHIM_RECORD_FILE\" ]; then
  echo \"stty $*\" >> \"$SHIM_RECORD_FILE\"
  if [ -n \"$SHIM_ENV_PROBE\" ]; then
    echo \"env-probe $SHIM_ENV_PROBE\" >> \"$SHIM_RECORD_FILE\"
  fi
fi
mode=\"${SHIM_MODE:-silent-ok}\"
case \"$mode\" in
  exit1)
    exit 1 ;;
  sleep)
    sleep \"${SHIM_SLEEP_SECS:-3}\"
    exit 0 ;;
  g-empty-raw-ok)
    # `-g` yields no snapshot line but still exits 0; everything else succeeds,
    # so raw mode is entered on an empty snapshot (the degraded path).
    exit 0 ;;
  g-line-exit1)
    # `-g` emits a plausible snapshot line but the process still fails. Code that
    # trusts a non-blank line without checking the exit status enters raw mode on
    # it; code that checks the status rejects it and never goes raw.
    if [ \"$1\" = \"-g\" ]; then echo \"fake:settings:1234\"; fi
    exit 1 ;;
  raw-exit1)
    if [ \"$1\" = \"-icanon\" ]; then exit 1; fi
    exec /bin/stty \"$@\" ;;
  slow-raw)
    if [ \"$1\" = \"-icanon\" ]; then sleep 0.3; fi
    exec /bin/stty \"$@\" ;;
  slow-restore)
    case \"$1\" in -g|-icanon) ;; *) sleep 2.2 ;; esac
    exec /bin/stty \"$@\" ;;
  *)
    # silent-ok: print nothing, succeed.
    exit 0 ;;
esac
")

(defn- make-executable!
  "Mark file `f` as executable. Shells out to `chmod` rather than calling
   `File.setExecutable`, which jolt's `java.io.File` does not implement. These
   shims are Unix-only (the pty harness is), so `chmod +x` is always available."
  [^java.io.File f]
  (-> (ProcessBuilder. ["chmod" "+x" (.getAbsolutePath f)])
      .start
      .waitFor))

(defn- sh-quote
  "Single-quote `s` for a POSIX shell."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn make-shim-dir!
  "Create a temp dir containing an executable `stty` shim and return its path.
   With `{:mode m :record path}`, those values are set inside the script instead
   of being read from SHIM_MODE and SHIM_RECORD_FILE. Use this when the code under
   test runs stty with an empty environment."
  ([] (make-shim-dir! {}))
  ([{:keys [mode record]}]
   (let [dir (io/file (System/getProperty "java.io.tmpdir")
                      (str "sc-shim-" (System/nanoTime)))
         baked (str (when mode (str "SHIM_MODE=" (sh-quote mode) "\n"))
                    (when record (str "SHIM_RECORD_FILE=" (sh-quote record) "\n")))]
     (.mkdirs dir)
     (let [f (io/file dir "stty")]
       (spit f (str/replace-first shim-script "#!/bin/sh\n" (str "#!/bin/sh\n" baked)))
       (make-executable! f))
     (.getAbsolutePath dir))))

(defn make-node-recorder!
  "Create a temp dir holding an executable `node` that appends its argv to a
   record file and exits 1. Put the dir first on PATH to see whether anything
   runs `node` through a PATH lookup. Returns {:dir <path> :record <path>}."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "sc-node-" (System/nanoTime)))
        _ (.mkdirs dir)
        rec (str (io/file dir "record"))
        f (io/file dir "node")]
    (spit f (str "#!/bin/sh\n"
                 "echo \"node $*\" >> " (sh-quote rec) "\n"
                 "exit 1\n"))
    (make-executable! f)
    {:dir (.getAbsolutePath dir) :record rec}))

(defn make-sh-recorder!
  "Create an executable `sh` wrapper that appends each invocation's argv to a record
   file, then `exec`s the real /bin/sh so exit status and inherited descriptors are
   preserved. Pass the returned :sh as the scenario spec's :sh to count how many
   times the tty probe actually shells out. Returns {:sh <path> :record <path>}."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "sc-sh-" (System/nanoTime)))
        _ (.mkdirs dir)
        rec (str (io/file dir "record"))
        f (io/file dir "sh")]
    (spit f (str "#!/bin/sh\n"
                 "echo \"sh $*\" >> \"" rec "\"\n"
                 "exec /bin/sh \"$@\"\n"))
    (make-executable! f)
    {:sh (.getAbsolutePath f) :record rec}))
