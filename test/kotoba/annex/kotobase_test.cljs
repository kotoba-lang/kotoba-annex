(ns kotoba.annex.kotobase-test
  (:require [clojure.test :refer [deftest is testing async]]
            [kotoba.annex.kotobase :as kb]))

;; in-memory な blob サーバを mock fetch で表現（kotobase blob 面の HTTP 契約を模す）
(defn mock-fetch [backing]
  (fn [u & [opts]]
    (let [o (or opts #js {})
          method (or (.-method o) "GET")
          key (second (re-find #"key=([^&]+)" u))
          k (js/decodeURIComponent key)]
      (js/Promise.resolve
       (cond
         (re-find #"blob\.put" u)
         (do (swap! backing assoc k (.-body o)) #js {:ok true})
         (re-find #"blob\.head" u)
         #js {:ok (contains? @backing k)}
         (re-find #"blob\.get" u)
         (if (contains? @backing k)
           #js {:ok true :arrayBuffer (fn [] (js/Promise.resolve (get @backing k)))}
           #js {:ok false})
         (re-find #"blob\.remove" u)
         (do (swap! backing dissoc k) #js {:ok true})
         :else #js {:ok false})))))

(deftest kotobase-store-roundtrip
  (testing "kotobase store が blob 面契約で store→present→retrieve→remove を回す"
    (async done
      (let [backing (atom {})
            s (kb/kotobase-store {:endpoint "https://kotobase.net"
                                  :graph "test-assets"
                                  :fetch (mock-fetch backing)})
            key "SHA256E-s3--abc"
            bytes (js/Buffer.from #js [1 2 3])]
        (-> ((:store! s) key bytes)
            (.then (fn [ok] (is ok "store ok")))
            (.then (fn [_] ((:present? s) key)))
            (.then (fn [p] (is (= :yes p) "present after store")))
            (.then (fn [_] ((:retrieve s) key)))
            (.then (fn [b] (is (= (vec (js/Array.from b)) (vec (js/Array.from bytes))) "retrieved bytes match")))
            (.then (fn [_] ((:remove! s) key)))
            (.then (fn [_] ((:present? s) key)))
            (.then (fn [p] (is (= :no p) "absent after remove") (done))))))))

(deftest prepare-requires-config
  (is (thrown? js/Error ((:prepare! (kb/kotobase-store {:fetch (fn [& _])})) {}))))
