(ns supports-color.runner
  "nbb entrypoint for the CLJS/Node test suite (`bb test:node`).

   Runs, on the real Node runtime:
   - supports-color.node-smoke-test  - the shared-code fixes that were JVM-only idioms.
   - supports-color.detect-level-test - the in-process detection tree, reused from
     the JVM/bb suite. Its windows-version-detection deftest exercises the
     str/split fix on Node, and loading it proves core.cljs (with its node
     require of process/os/tty/child_process/fs) loads under nbb at all.

   Under nbb, `run-tests` returns nil and the summary is delivered asynchronously
   through the `:end-run-tests` report method, so the process exit code is set
   from there rather than from the `run-tests` return value."
  (:require [cljs.test :as t]
            [supports-color.node-smoke-test]
            [supports-color.detect-level-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(t/run-tests 'supports-color.node-smoke-test
             'supports-color.detect-level-test)
