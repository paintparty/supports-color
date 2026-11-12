(ns supports-color.csi996-terminator-test
  "Test for the CSI ?996 read loop terminator.

   The csi996 read loop used to stop as soon as the accumulated buffer ended in
   `n` (`(.endsWith s \"n\")`). During the raw window echo is off, so a keystroke
   the user types is invisible and never reaches the shell, and a single typed
   `n` satisfied that terminator, so the loop returned \"n\" to the caller as if it
   were the terminal's reply. parse-csi996-response then rejected it (nil), but a
   bogus value had already been handed back and, with :debug on, logged.

   The fix requires the buffer to begin with ESC and to contain a complete frame,
   either the CSI ?997;{1,2}n theme reply or the DA1 ESC[?...c sentinel, before
   stopping, discarding anything that does not start a frame. This narrows what
   is *returned*. It does not give the consumed
   keystroke back to the shell (the byte is already off /dev/tty by then).

   Driven through the pty harness: the child runs the real query-terminal-jvm and
   the parent injects the reply only after seeing the query bytes."
  (:require [clojure.test :refer [deftest is]]
            [supports-color.harness :as h]))

(defn- ->hex [^String s]
  (apply str (map #(format "%02x" (int %)) s)))

(def ^:private valid-frame (str (char 27) "[?997;1n"))

(def ^:private da1 (str (char 27) "[?62;22c"))

;; A stray "n" (an invisible keystroke) followed by the real dark-mode reply and
;; the DA1 reply that ends the read.
(def ^:private noisy-reply (str "n" valid-frame da1))

(deftest leading-stray-byte-is-discarded-not-returned
  (h/when-pty
   ;; Pre-fix the loop returns "n" the instant it arrives (trailing-n terminator).
   ;; Post-fix "n" fails the ESC-prefix check and is discarded, so the loop keeps
   ;; reading and returns the full valid frame instead.
   (let [r (h/run-pty-scenario
            {:scenario :query :opts {:timeout-ms 500}}
            {:wait-for-query? true
             :response-hex    (->hex noisy-reply)
             :wait-go?        true})
         ret (get-in r [:result :return])]
     (is (= (str valid-frame da1) ret)
         "the loop returns the complete CSI ?997;1n frame and the DA1 reply, not
          the stray \"n\"")
     (is (not= "n" ret)
         "a bare stray byte is never returned as the terminal reply")
     (is (true? (:echo-after r))
         "the terminal is left in cooked mode with ECHO on"))))
