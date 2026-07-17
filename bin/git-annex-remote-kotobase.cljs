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
    ;; 既定は directory store（環境変数 KOTOBA_ANNEX_DIR、無ければ ./annex-blocks）
    (let [d (or (some-> js/process.env.KOTOBA_ANNEX_DIR) "annex-blocks")]
      (swap! state assoc :store (dir/directory-store d))))
  (:store @state))

(defn handle-transfer [args]
  (let [[direction key file] args
        s (ensure-store!)]
    (case direction
      "STORE"
      (let [ok (try (let [bytes (fs/readFileSync file)]
                      ((:store! s) key bytes))
                    (catch :default _ false))]
        (out (proto/transfer-response "STORE" key (boolean ok))))
      "RETRIEVE"
      (let [ok (try (let [bytes ((:retrieve s) key)]
                      (when bytes (fs/writeFileSync file bytes) true))
                    (catch :default _ false))]
        (out (proto/transfer-response "RETRIEVE" key (boolean ok))))
      (out (proto/transfer-response (str direction) key false)))))

(defn handle-line [line]
  (let [{:keys [cmd args] :as parsed} (proto/parse line)
        s (ensure-store!)]
    (case cmd
      :PREPARE (out (if ((:prepare! s) (:config @state)) "PREPARE-SUCCESS"
                        "PREPARE-FAILURE prepare failed"))
      :TRANSFER (handle-transfer args)
      :unknown nil
      ;; その他はプロトコルの simple-response で処理
      (if-let [r (proto/simple-response parsed s)]
        (out r)
        ;; 未対応コマンドは UNSUPPORTED-REQUEST（git-annex はスキップする）
        (out "UNSUPPORTED-REQUEST")))))

;; 起動: VERSION を送ってから行ループ
(out proto/hello)
(def rl (readline/createInterface #js {:input js/process.stdin}))
(.on rl "line" handle-line)
(.on rl "close" (fn [] (js/process.exit 0)))
