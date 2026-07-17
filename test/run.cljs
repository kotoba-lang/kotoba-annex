;; nbb テストランナー: nbb --classpath src:test test/run.cljs
(ns run
  (:require [clojure.test :as t]
            [kotoba.annex.protocol-test]
            [kotoba.annex.kotobase-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m) (set! (.-exitCode js/process) 1)))

(t/run-tests 'kotoba.annex.protocol-test 'kotoba.annex.kotobase-test)
