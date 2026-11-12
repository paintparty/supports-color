(ns supports-color.tty-predicates-test
  "Tests for the per-descriptor tty predicates in core.clj.

   :stdout-tty, :stderr-tty and :stdin-tty were three byte-for-byte
   copies of `(not= nil (System/console))`, one process-wide check that cannot
   tell the descriptors apart and is silently wrong on JDK 22-24, where
   System.console is non-null under redirection. Each predicate now
   spawns `sh -c 'test -t <fd>'` with this process's descriptors inherited and
   reads the exit status, memoized in its own delay.

   The predicates read the *actual* descriptors of the process they run in, so they
   are exercised in a pty child whose individual fds the harness redirects:

   - stdin redirected to a file, stdout on the pty  -> stdout? true,  stdin? false
   - only stderr redirected                          -> stderr? false, others true
   - full redirection (no controlling terminal)      -> all three false

   Memoization is checked by passing a recording `sh` wrapper as the scenario
   spec's :sh, which the runner puts in place through core's private test hook,
   and asserting that calling every predicate three times shells out exactly three
   times, once per descriptor.

   Everything is skipped where no controlling-terminal pty is available."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supports-color.harness :as h]
            [supports-color.shims :as shims]))

(defn- tty-result [opts]
  (:result (h/run-pty-scenario {:scenario :tty-predicates :opts {}} opts)))

;; --- predicates are per-descriptor -------------------------------------------

(deftest stdout-tty-independent-of-stdin
  (testing ":stdout is true with stdin redirected from a file"
    (h/when-pty
     ;; fd 0 -> a regular file (not a tty), fds 1/2 stay the pty slave.
     (let [r (tty-result {:redir "0=file"})]
       (is (true? (:stdout r))
           "stdout is still the pty, so :stdout must be true")
       (is (false? (:stdin r))
           (str "stdin was redirected to a file, so :stdin must be false. The old "
                "single System/console check could not make this distinction"))))))

(deftest stderr-tty-independent-of-others
  (testing ":stderr is false when only stderr is redirected"
    (h/when-pty
     ;; fd 2 -> /dev/null, fds 0/1 stay the pty slave.
     (let [r (tty-result {:redir "2=null"})]
       (is (false? (:stderr r))
           "stderr was redirected, so :stderr must be false")
       (is (true? (:stdout r)) "stdout is untouched, still a tty")
       (is (true? (:stdin r)) "stdin is untouched, still a tty")))))

(deftest full-redirection-all-false
  (testing "all three predicates are false with no controlling terminal"
    (h/when-pty
     (let [r (:result (h/run-notty-scenario
                       {:scenario :tty-predicates :opts {}}))]
       (is (false? (:stdout r)))
       (is (false? (:stderr r)))
       (is (false? (:stdin r)))))))

;; --- each predicate memoizes its probe ----------------------------------------

(deftest each-predicate-probes-at-most-once
  (testing "calling every predicate repeatedly shells out once per descriptor"
    (h/when-pty
     (let [{:keys [sh record]} (shims/make-sh-recorder!)
           r (:result (h/run-pty-scenario
                       {:scenario :tty-predicates :opts {:reps 3} :sh sh}))
           calls (when (.exists (io/file record))
                   (->> (str/split-lines (slurp record))
                        (remove str/blank?)))]
       (is (= 3 (:reps r)) "the child called each predicate three times")
       (is (some? calls) "the recording sh wrapper was invoked")
       (is (= 3 (count calls))
           (str "one probe per descriptor despite " (:reps r)
                " calls each. The delay memoizes. Saw: " (pr-str calls)))
       (is (every? #(str/includes? % "test -t") calls)
           "each recorded invocation is a `test -t <fd>` probe")))))

;; --- SUPPORTS_COLOR_SH is not read (DEC-1, audit A-04) -----------------------

(deftest sh-env-override-is-ignored
  (testing "SUPPORTS_COLOR_SH in the launch env does not choose the shell that
            runs the tty probe"
    (h/when-pty
     (let [{:keys [sh record]} (shims/make-sh-recorder!)
           r (tty-result {:env {"SUPPORTS_COLOR_SH" sh}})]
       (is (not (.exists (io/file record)))
           "the shell named by SUPPORTS_COLOR_SH never ran")
       (is (true? (:stdout r)) "stdout is the pty, and the real probe says so")
       (is (true? (:stderr r)) "stderr is the pty, and the real probe says so")))))

;; --- the probe imports nothing from the environment (DEC-2, audit A-07) ------

(deftest bash-function-import-does-not-fake-a-tty
  (testing "an exported shell function named `test` cannot make redirected
            descriptors look like terminals"
    (h/when-pty
     (let [r (tty-result {:redir "0=file,1=file,2=file"
                          :env {"BASH_FUNC_test%%" "() { return 0; }"}})]
       (is (false? (:stdout r)) "stdout is a file")
       (is (false? (:stderr r)) "stderr is a file")
       (is (false? (:stdin r)) "stdin is a file")))))
