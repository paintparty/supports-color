(ns supports-color.stty-path-resolution-test
  "Test for bare `stty` resolved through an inherited PATH.

   core.clj spawned `stty` by bare name, so any directory on PATH ahead of the
   real binary could shadow it. That is a subprocess the library consumer never asked
   for, firing on a startup call, in exactly the environments where PATH is least
   trusted (CI runners, dev containers, project-local node_modules/.bin). The fix
   resolves the executable once to an absolute path from the ordered list in
   `supports-color.common/stty-candidates` (/bin/stty, /usr/bin/stty, then the
   NixOS, Guix and Termux locations), so a shim on PATH is bypassed. Where stty is
   at none of them the query refuses to run and detection falls through to
   COLORFGBG rather than executing a bare `stty`. No environment variable can
   choose the binary either. SUPPORTS_COLOR_STTY, once honored, is now ignored.

   Discriminating case: put a recording `stty` shim first on the child's PATH and
   assert it is never invoked. Pre-fix the shim's record file is written; post-fix
   the real /bin/stty runs and the shim file stays absent. The var is reached with
   `resolve` so this namespace still compiles against the pre-fix source.

   The shim-on-PATH case needs the controlling-terminal pty child and is skipped
   where no pty (or no absolute stty) is available."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [supports-color.common :as c]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(defn- absolute-stty-exists? []
  (boolean (some #(.exists (io/file ^String %)) c/stty-candidates)))

(defn- record-file []
  (str (io/file (System/getProperty "java.io.tmpdir")
                (str "sc-stty-path-rec-" (System/nanoTime)))))

(deftest stty-path-resolves-to-an-absolute-binary
  (testing "stty is resolved to an existing absolute path, not a bare name"
    (if-let [v (requiring-resolve 'supports-color.core/stty-path)]
      (let [p @@v]
        ;; On any machine with a standard stty this is /bin/stty or /usr/bin/stty;
        ;; where neither exists it is nil and the query declines rather than
        ;; shelling out to a PATH-resolved `stty`.
        (is (or (nil? p)
                (and (.isAbsolute (io/file ^String p))
                     (.exists (io/file ^String p))))
            "resolved stty path is an existing absolute file, or nil"))
      (is false "supports-color.core/stty-path is not defined yet"))))

(deftest shim-on-path-is-not-invoked
  (testing "a recording stty shim first on PATH is bypassed by absolute resolution"
    (h/when-pty
     ;; Only exercisable when an absolute stty actually exists, since the point is
     ;; that the absolute binary is preferred over the PATH shim.
     (if-not (absolute-stty-exists?)
       (do (println "  [skip] no stty at any fixed candidate path on this machine")
           (is true))
       (let [rec (record-file)
             r (h/run-pty-scenario
                {:scenario :background :opts {:timeout-ms 100}}
                {:env {"PATH" (h/path-with (shims/make-shim-dir!))
                       "SHIM_RECORD_FILE" rec}
                 :wait-go? true})]
         (is (not (.exists (io/file rec)))
             "the PATH shim's stty was never invoked")
         (is (map? (:return (:result r)))
             "background still returns its result map via the real stty")
         (is (true? (:echo-after r))
             "ECHO is restored after the real query"))))))

(deftest stty-candidates-include-nix-guix-termux
  (testing "the fixed stty list covers NixOS, Guix and Termux, and every entry is
            an absolute path"
    (if-let [v (requiring-resolve 'supports-color.common/stty-candidates)]
      (let [cs @v]
        (is (= "/bin/stty" (first cs)) "/bin/stty is tried first")
        (is (every? (set cs) ["/bin/stty"
                              "/usr/bin/stty"
                              "/run/current-system/sw/bin/stty"
                              "/run/current-system/profile/bin/stty"
                              "/data/data/com.termux/files/usr/bin/stty"])
            "the standard, NixOS, Guix and Termux locations are all listed")
        (is (every? #(.isAbsolute (io/file ^String %)) cs)
            "no candidate is a bare name or a relative path"))
      (is false "supports-color.common/stty-candidates is not defined yet"))))

(deftest stty-env-override-is-ignored
  (testing "SUPPORTS_COLOR_STTY in the launch env does not choose the stty binary"
    (h/when-pty
     (if-not (absolute-stty-exists?)
       (do (println "  [skip] no stty at any fixed candidate path on this machine")
           (is true))
       (let [rec (record-file)
             r (h/run-pty-scenario
                {:scenario :query :opts {:timeout-ms 400}}
                {:env {"SUPPORTS_COLOR_STTY" (str (io/file (shims/make-shim-dir!) "stty"))
                       "SHIM_RECORD_FILE" rec}
                 :wait-for-query? true
                 :wait-go? true
                 :response-hex h/osc11-reply-hex})]
         (is (not (.exists (io/file rec)))
             "the shim named by SUPPORTS_COLOR_STTY never ran")
         (is (h/osc11-reply? (:return (:result r)))
             "the query ran through the real stty")
         (is (true? (:echo-after r))
             "ECHO is restored after the query"))))))
