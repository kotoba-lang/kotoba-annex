(ns kotoba.annex.protocol
  "git-annex external special remote プロトコルの純パーサ/ディスパッチ。
   IO を持たず、1 コマンド行 → 応答行（+ git-annex への問い合わせ要求）を返す。
   実 IO（stdin/stdout、ファイル読み書き、store 呼び出し）は bin 側が行う。

   git-annex external special remote protocol（公式仕様、実 git-annex で検証）:
     起動時に remote が `VERSION 1` を送る。
     git-annex → remote のコマンド行を処理:
       EXTENSIONS ...      -> `EXTENSIONS`（本 remote は拡張なし）
       LISTCONFIGS         -> `CONFIGEND`
       INITREMOTE          -> :init! -> INITREMOTE-SUCCESS|FAILURE
       PREPARE             -> :prepare! -> PREPARE-SUCCESS|FAILURE
       TRANSFER STORE Key File    -> :store!   -> TRANSFER-SUCCESS STORE Key|FAILURE
       TRANSFER RETRIEVE Key File -> :retrieve -> TRANSFER-SUCCESS RETRIEVE Key|FAILURE
       CHECKPRESENT Key    -> :present? -> CHECKPRESENT-SUCCESS|FAILURE|UNKNOWN Key
       REMOVE Key          -> :remove! -> REMOVE-SUCCESS|FAILURE Key
       GETCOST             -> COST <n>
       GETAVAILABILITY     -> AVAILABILITY GLOBAL
   TRANSFER 系はファイル IO を伴うので bin 側で実行し、ここでは応答文字列だけを組む。")

(defn parse
  "コマンド行を {:cmd kw :args [..]} に。空行/未知は {:cmd :unknown}。"
  [line]
  (let [toks (when (string? line) (vec (.split (.trim line) #"\s+")))]
    (if (empty? (remove #(= "" %) (or toks [])))
      {:cmd :unknown :args []}
      (let [[c & r] toks]
        {:cmd (keyword c) :args (vec r)}))))

;; 起動時に remote が最初に送る行
(def hello "VERSION 1")

(defn present-response
  "present? の結果（:yes|:no|:unknown）→ CHECKPRESENT 応答行。"
  [key result]
  (case result
    :yes (str "CHECKPRESENT-SUCCESS " key)
    :no  (str "CHECKPRESENT-FAILURE " key)
    (str "CHECKPRESENT-UNKNOWN " key " store unavailable")))

(defn transfer-response [direction key ok?]
  (str (if ok? "TRANSFER-SUCCESS " "TRANSFER-FAILURE ") direction " " key
       (when-not ok? " transfer failed")))

(defn remove-response [key ok?]
  (str (if ok? "REMOVE-SUCCESS " "REMOVE-FAILURE ") key
       (when-not ok? " remove failed")))

(defn simple-response
  "ファイル IO を伴わない単純コマンドの応答行（無ければ nil）。
   TRANSFER は bin 側が store とファイルを繋いで別途応答するので、ここでは nil。"
  [{:keys [cmd] :as parsed} store]
  (case cmd
    :EXTENSIONS "EXTENSIONS"
    :LISTCONFIGS "CONFIGEND"
    :GETCOST "COST 200"
    :GETAVAILABILITY "AVAILABILITY GLOBAL"
    :INITREMOTE (if ((:init! store)) "INITREMOTE-SUCCESS"
                    "INITREMOTE-FAILURE init failed")
    :ERROR nil
    :CHECKPRESENT (present-response (first (:args parsed))
                                   ((:present? store) (first (:args parsed))))
    :REMOVE (let [k (first (:args parsed))]
              (remove-response k ((:remove! store) k)))
    nil))
