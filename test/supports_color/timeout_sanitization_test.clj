(ns supports-color.timeout-sanitization-test
  "Tests for :timeout-ms validation.

   Bad input: a bad :timeout-ms (a string, a fraction, an out-of-range number) must
   be coerced to a sane integer in [0, 30000] before any terminal query, so a
   caller's type error cannot reach the deadline arithmetic and, via a throw or a
   non-terminating loop, leave the tty raw. The chosen handling is clamp-and-warn,
   not throw, keeping the library's degrade-gracefully contract. Asserted directly
   on the sanitizer.

   Non-finite input: ##NaN / ##Inf produce a deadline that never expires, so the read loop
   spins forever with the terminal raw and the caller hung. That is a distinct
   failure signature from the bad-input case above (a silent nil), so it gets its own assertion. Checked
   through the pty: background with ##NaN must return promptly with ECHO
   restored, where the pre-fix child hangs until the harness kills it and the
   terminal is left raw.

   Sanitizer cases are plain in-process calls; the hang / raw-mode assertions need
   the pty child so an infinite loop is time-bounded and cannot wedge the suite."
  (:require [clojure.test :refer [deftest is testing]]
            [supports-color.common :as c]
            [supports-color.harness :as h]))

(def ^:private sanitize #'c/sanitize-timeout-ms)

(deftest sanitize-clamps-and-coerces
  (testing "a valid in-range integer is unchanged"
    (is (= 100 (sanitize 100)))
    (is (= 0 (sanitize 0)))
    (is (= 30000 (sanitize 30000))))
  (testing "a non-numeric value falls back to the 2000 default"
    (is (= 2000 (sanitize "100")))
    (is (= 2000 (sanitize nil)))
    (is (= 2000 (sanitize :fast))))
  (testing "an out-of-range number is clamped to the nearest bound"
    (is (= 0 (sanitize -5)))
    (is (= 30000 (sanitize 999999))))
  (testing "a fractional millisecond is floored to an integer"
    (is (= 100 (sanitize 100.7))))
  (testing "NaN and infinities cannot survive to the deadline arithmetic"
    (is (= 2000 (sanitize ##NaN)))
    (is (= 30000 (sanitize ##Inf)))
    (is (= 0 (sanitize ##-Inf)))))

(deftest sanitized-deadline-is-always-finite
  ;; The root defect is a deadline that is either a type error or non-finite;
  ;; after sanitizing, (+ now (sanitize x)) is a finite integer for every input.
  (doseq [x [100 "100" -5 999999 ##NaN ##Inf ##-Inf 100.7 :nope nil]]
    (let [d (+ (System/currentTimeMillis) (sanitize x))]
      (is (integer? d) (str "finite integer deadline for " (pr-str x))))))

(deftest nan-timeout-does-not-hang-or-leak-raw
  (testing "background with ##NaN returns promptly and restores ECHO"
    (h/when-pty
     ;; No shim: the child drives the real stty against the pty slave and enters
     ;; real raw mode. Pre-fix the NaN deadline never expires, so the read loop
     ;; spins forever, the child never signals done, and the harness kills it with
     ;; the tty left raw. Post-fix the value clamps to the 2000ms default, the
     ;; query times out, and the restoring finally puts ECHO back.
     (let [r (h/run-pty-scenario
              {:scenario :background :opts {:timeout-ms ##NaN}}
              {:wait-go? true})]
       (is (false? (:timed-out r))
           "the query returns rather than spinning forever on a NaN deadline")
       (is (map? (:return (:result r)))
           "background still returns its result map")
       (is (true? (:echo-after r))
           "the terminal is left in cooked mode with ECHO on")))))

(deftest string-timeout-does-not-leak-raw
  (testing "background with a string :timeout-ms completes and keeps ECHO"
    (h/when-pty
     ;; A string reaches the deadline arithmetic as a ClassCastException pre-fix;
     ;; the terminal-restore fix already restores the tty on that throw, so this pins
     ;; the invariant under the clamp-and-warn handling rather than discriminating
     ;; on its own. The sanitizer unit tests carry the bad-input-value coverage.
     (let [r (h/run-pty-scenario
              {:scenario :background :opts {:timeout-ms "100"}}
              {:wait-go? true})]
       (is (false? (:timed-out r)) "the query completes")
       (is (map? (:return (:result r))) "returns the result map")
       (is (true? (:echo-after r)) "ECHO is restored / never lost")))))
