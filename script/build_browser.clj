;; Compiles the browser probe with :advanced optimizations and no :target, as a
;; browser app would. Used by `bb test:browser`.
;;
;; Usage: clojure -M:cljs-latest script/build_browser.clj <output-dir>

(require '[cljs.build.api :as api])

(let [[out-dir] *command-line-args*]
  (api/build (api/inputs "src" "test-browser")
             {:main          'supports-color.browser-probe
              :optimizations :advanced
              :output-dir    out-dir
              :output-to     (str out-dir "/main.js")}))
