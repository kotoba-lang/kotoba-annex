#!/usr/bin/env nbb
;; git-annex external special remote — kotobase.net（または directory fallback）へ
;; DataLad/git-annex の大容量バイナリを content-address で永続化する。
;;
;; 使い方（git-annex 側）:
;;   git annex initremote kotobase type=external externaltype=kotobase \
;;       encryption=none store=directory dir=/path/to/blocks
;;   （store=kotobase endpoint=https://kotobase.net graph=<graph> は kotobase 接続時）
;;
;; プロトコルは stdin(git-annex→remote) / stdout(remote→git-annex) の行対話。
;; TRANSFER はファイル IO を伴うのでここで store とファイルを繋ぐ。
(ns git-annex-remote-kotobase
  (:require [kotoba.annex.protocol :as proto]
            [kotoba.annex.store :as store]
            [kotoba.annex.directory :as dir]
            [kotoba.annex.kotobase :as kb]
            ["fs" :as fs]
            ["readline" :as readline]))

(def state (atom {:store nil :config {}}))

(defn out [line] (println line))
(defn dbg [msg] (out (str "DEBUG " msg)))

;; git-annex への同期問い合わせ（GETCONFIG 等）は本 remote では PREPARE 時に
;; まとめて行わず、initremote の externaltype 引数で directory を既定にする簡易版。
;; kotobase 接続は follow-up（endpoint/graph を GETCONFIG で取る）。
(defn ensure-store! []
  (when-not (:store @state)
    ;; KOTOBASE_ENDPOINT があれば kotobase.net store（CACAO 自己 mint で認証、
    ;; 鍵は .kotoba-annex/identity.edn に自己生成 — owner の token 受け渡し不要）。
    ;; 無ければ directory store（KOTOBA_ANNEX_DIR、既定 ./annex-blocks）。
    (let [ep js/process.env.KOTOBASE_ENDPOINT]
      (swap! state assoc :store
             (if (and ep (not= ep ""))
               (kb/kotobase-store {:endpoint ep
                                   :graph js/process.env.KOTOBASE_GRAPH})
               (dir/directory-store (or js/process.env.KOTOBA_ANNEX_DIR "annex-blocks"))))))
  (:store @state))

;; store 契約の関数は directory では同期値、kotobase では Promise を返す。
;; どちらでも動くよう Promise.resolve で正規化してから応答する。
(defn- p [x] (js/Promise.resolve x))

(defn handle-transfer [args]
  (let [[direction key file] args
        s (ensure-store!)]
    (case direction
      "STORE"
      (-> (p (let [bytes (fs/readFileSync file)] ((:store! s) key bytes)))
          (.then (fn [ok] (out (proto/transfer-response "STORE" key (boolean ok)))))
          (.catch (fn [_] (out (proto/transfer-response "STORE" key false)))))
      "RETRIEVE"
      (-> (p ((:retrieve s) key))
          (.then (fn [bytes]
                   (if bytes
                     (do (fs/writeFileSync file bytes)
                         (out (proto/transfer-response "RETRIEVE" key true)))
                     (out (proto/transfer-response "RETRIEVE" key false)))))
          (.catch (fn [_] (out (proto/transfer-response "RETRIEVE" key false)))))
      (out (proto/transfer-response (str direction) key false)))))

(defn handle-line [line]
  (let [{:keys [cmd args] :as parsed} (proto/parse line)
        s (ensure-store!)]
    (case cmd
      :PREPARE (out (if ((:prepare! s) (:config @state)) "PREPARE-SUCCESS"
                        "PREPARE-FAILURE prepare failed"))
      :TRANSFER (handle-transfer args)
      ;; CHECKPRESENT / REMOVE は store が Promise を返しうるので bin で await
      :CHECKPRESENT (let [k (first args)]
                      (-> (p ((:present? s) k))
                          (.then (fn [r] (out (proto/present-response k r))))
                          (.catch (fn [_] (out (proto/present-response k :unknown))))))
      :REMOVE (let [k (first args)]
                (-> (p ((:remove! s) k))
                    (.then (fn [ok] (out (proto/remove-response k (boolean ok)))))
                    (.catch (fn [_] (out (proto/remove-response k false))))))
      :unknown nil
      ;; その他（EXTENSIONS/LISTCONFIGS/GETCOST/INITREMOTE…）は同期
      (if-let [r (proto/simple-response parsed s)]
        (out r)
        ;; 未対応コマンドは UNSUPPORTED-REQUEST（git-annex はスキップする）
        (out "UNSUPPORTED-REQUEST")))))

;; 起動: VERSION を送ってから行ループ
(out proto/hello)
(def rl (readline/createInterface #js {:input js/process.stdin}))
(.on rl "line" handle-line)
(.on rl "close" (fn [] (js/process.exit 0)))
