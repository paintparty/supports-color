(ns supports-color.osc11-color-parser-test
  "Tests for the OSC 11 color parser.

   Oversized hex components: parse-osc-color-response matched hex groups with an unbounded `+`
   and fed each group to Integer/parseInt. A reply whose component exceeds an
   int (e.g. \"ffffffff\") threw NumberFormatException. Unlike every
   other failure in this library, that escaped out of the public background
   query rather than degrading to nil. The fix bounds
   the groups to {1,4} (a conforming XParseColor component is 1-4 hex digits),
   so an over-long field no longer matches and the reply falls through to nil.

   Single-hex-digit scaling (same three lines as the fix above): the 8-bit scaling was (bit-shift-right n (- bits 8)),
   a *negative* shift count for a single-hex-digit (4-bit) component. On the JVM
   the shift count is masked, so \"rgb:f/f/f\" scaled to {:r 0 :g 0 :b 0}, pure
   black, and silently inverted background to report a light terminal as
   dark, on a fully conforming reply and with no hostile input. The fix scales
   by proportion instead, so a single \"f\" maps to 255.

   These are pure-parser assertions: plain in-process calls, no pty needed."
  (:require [clojure.test :refer [deftest is testing]]
            [supports-color.common :as c]))

(def ^:private parse #'c/parse-osc-color-response)
(def ^:private luma  #'c/rgb->luma)

(deftest oversized-hex-degrades-to-nil-instead-of-throwing
  (testing "an over-long component no longer reaches parseInt. The reply is nil"
    ;; Pre-fix this threw NumberFormatException out of the parser (and thus out
    ;; of the public fns). The substantive contract is that it does not throw.
    ;; The {1,4} bound means it stops matching and degrades to nil, which the
    ;; caller already treats as an unsupported terminal (falls through to COLORFGBG).
    (is (nil? (parse "rgb:ffffffff/0/0"))))
  (testing "malformed hex still degrades to nil rather than raising"
    (is (nil? (parse "rgb:zz/0/0")))
    (is (nil? (parse "not a color at all")))))

(deftest arb-001-single-hex-digit-scales-to-full-8-bit
  (testing "rgb:f/f/f parses to white, not black"
    ;; Pre-fix: (bit-shift-right 15 -4) → 0 → {:r 0 :g 0 :b 0}, inverting the
    ;; dark/light decision on a conforming reply.
    (is (= {:r 255 :g 255 :b 255} (parse "rgb:f/f/f"))))
  (testing "white background reports light (luma > 0.5), i.e. :polarity \"light\""
    (is (> (luma (parse "rgb:f/f/f")) 0.5)))
  (testing "single-digit black stays black and reports dark"
    (is (= {:r 0 :g 0 :b 0} (parse "rgb:0/0/0")))
    (is (<= (luma (parse "rgb:0/0/0")) 0.5))))

(deftest scaling-unchanged-for-conforming-multi-byte-replies
  (testing "2- and 4-hex-digit components scale to the same 8-bit value as before"
    (is (= {:r 128 :g 128 :b 128} (parse "rgb:80/80/80")))
    (is (= {:r 128 :g 128 :b 128} (parse "rgb:8080/8080/8080")))
    (is (= {:r 255 :g 255 :b 255} (parse "rgb:ffff/ffff/ffff")))
    (is (= {:r 0 :g 0 :b 0} (parse "rgb:0000/0000/0000")))))
