(ns kotoba.annex.kotobase
  "kotobase.net backed の store — git-annex の annex key（content-address）を
   kotobase の content-addressed block store に永続化する。**同じ store 契約**
   （kotoba.annex.store）なので directory store と差し替え可能。

   kotobase-server の store 契約（handler.cljc で確認）:
     :put!     (fn [cid bytes])  block を cid で書く
     :get-fn   (fn [cid])        block bytes | nil
     :head-put!(fn [graph chain]) 名前付き head の CAS
     :head-get (fn [graph])      chain-cid | nil
   annex key はそれ自体が内容ハッシュ（SHA256E-s<size>--<hash>）なので、
   cid にそのまま使え衝突しない。present? は blob head で判定。

   ⚠ kotobase-server の**公開 XRPC 面は datomic 系**（datoms/transact/q/...）で、
   raw block の put/get は注入 store の内部契約。git-annex から直接使うには
   kotobase-server に blob 面（ai.gftd.apps.kotobase.blob.{put,get,head,remove}、
   store の put!/get-fn を薄く公開）を1つ追加する必要がある — これは server 側の
   小追加 + deploy（owner-gated、ADR の follow-up）。本 client はその blob 面の
   HTTP 契約に対して書いてあり、mock fetch で単体テスト可能。"
  (:require ["fs" :as fs]))

;; --- blob XRPC 契約（kotobase-server に追加する面。put!/get-fn/head の薄い公開）---
;; POST <endpoint>/xrpc/ai.gftd.apps.kotobase.blob.put   body: bytes  ?graph=&key=
;; GET  <endpoint>/xrpc/ai.gftd.apps.kotobase.blob.get   ?graph=&key=  -> bytes|404
;; GET  <endpoint>/xrpc/ai.gftd.apps.kotobase.blob.head  ?graph=&key=  -> 200|404
;; POST <endpoint>/xrpc/ai.gftd.apps.kotobase.blob.remove ?graph=&key=

(defn- url [endpoint method graph key]
  (str endpoint "/xrpc/ai.gftd.apps.kotobase.blob." method
       "?graph=" (js/encodeURIComponent graph) "&key=" (js/encodeURIComponent key)))

(defn kotobase-store
  "opts: {:endpoint \"https://kotobase.net\" :graph \"dougaka-kagaku-assets\"
          :fetch <fn>（省略時 js/fetch。テストで mock 注入）
          :sync? bool（true で await 同期化。既定は Promise を返すので bin 側で await）}。
   本 store の関数は Promise を返す — bin 側で await して応答を組む。"
  [{:keys [endpoint graph fetch] :or {fetch js/fetch}}]
  {:endpoint endpoint :graph graph
   :store!   (fn [key bytes]
               (-> (fetch (url endpoint "put" graph key)
                          #js {:method "POST" :body bytes})
                   (.then #(.-ok %))))
   :retrieve (fn [key]
               (-> (fetch (url endpoint "get" graph key))
                   (.then (fn [r] (if (.-ok r) (.arrayBuffer r) nil)))
                   (.then (fn [buf] (when buf (js/Buffer.from buf))))))
   :present? (fn [key]
               (-> (fetch (url endpoint "head" graph key))
                   (.then (fn [r] (if (.-ok r) :yes :no)))
                   (.catch (fn [_] :unknown))))
   :remove!  (fn [key]
               (-> (fetch (url endpoint "remove" graph key) #js {:method "POST"})
                   (.then #(.-ok %))))
   :init!    (fn [] true)
   :prepare! (fn [config]
               (when-not (and endpoint graph)
                 (throw (js/Error. "kotobase store needs :endpoint and :graph")))
               true)})
