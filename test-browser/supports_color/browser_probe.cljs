(ns supports-color.browser-probe
  "Compiled with :advanced by `bb test:browser`, then run by
   test-browser/run.js in a context with browser globals and no Node globals.
   Prints what profile, theme, and background report."
  (:require [supports-color.core :as sc]))

(let [p (sc/profile)
      t (sc/theme)
      b (sc/background)]
  (js/console.log
   (pr-str {:level               (:level p)
            :host                (get-in p [:runtime :host])
            :polarity            (:polarity t)
            :method              (:detection-method t)
            :background-polarity (:polarity b)})))
