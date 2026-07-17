# kotoba-annex

**git-annex external special remote** that persists DataLad/git-annex large
binaries to a **content-addressed block store** — either `kotobase.net`
(kotobase's block store, the `put!/get-fn/head` contract) or a local directory.

Written in `.cljc`/`.cljs` (nbb-first, per the workspace runtime priority). The
protocol engine is pure and testable; the block store is an **injected seam**
(same design as `kotobase-server`'s injectable `store` map) so directory /
kotobase.net / B2 backends are interchangeable.

## Why

Large binaries (rendered video, synthesized audio, model weights, datasets) must
not go into git history. The workspace standard is DataLad + git-annex + a
Backblaze B2 S3 special remote (see `m365-archive`). This adds **kotobase.net as
an alternative git-annex backend**: because a git-annex *key* is itself a content
hash (`SHA256E-s<size>--<hash>`), it maps 1:1 onto kotobase's content-addressed
block store — the annex key *is* the block cid.

## Verified

The special remote is **verified end-to-end against real `git-annex`** (v10) with
the directory store:

```
git annex initremote kotobase type=external externaltype=kotobase encryption=none
git annex add sample.wav
git annex copy sample.wav --to kotobase      # STORE  -> block SHA256E-s191020--… .wav
git annex drop sample.wav --force
git annex get  sample.wav --from kotobase    # RETRIEVE -> checksum ok
git annex fsck sample.wav                     # content integrity ok
```

### Verification matrix (all against real `git-annex` v10)

| operation | encryption=none | encryption=shared |
|---|---|---|
| STORE (`copy --to`)        | ✓ block keyed by annex key | ✓ block keyed by `GPGHMACSHA256--…` |
| RETRIEVE (`get --from`)    | ✓ checksum ok | ✓ decrypt + checksum ok |
| CHECKPRESENT (`fsck`/`whereis`) | ✓ | ✓ |
| REMOVE (`drop --from`)     | ✓ block store 1→0 | — |
| **at rest**                | plaintext block | **encrypted block** (block sha ≠ plaintext sha) |

encryption is transparent to the remote: git-annex encrypts before STORE and
decrypts after RETRIEVE; the remote stores/serves opaque blocks. Use
`encryption=shared` for non-public assets (audio/video with proprietary voices,
private datasets), `encryption=none` for public/regenerable content.

## Layout

- `src/kotoba/annex/protocol.cljc` — pure external-special-remote protocol
  (parse + response strings). No IO.
- `src/kotoba/annex/store.cljc` — store contract (`store!/retrieve/present?/
  remove!/init!/prepare!`) + in-memory impl for tests.
- `src/kotoba/annex/directory.cljs` — local directory block store (fallback /
  verification).
- `src/kotoba/annex/kotobase.cljs` — kotobase.net block store (maps the store
  contract to a blob XRPC surface).
- `bin/git-annex-remote-kotobase.cljs` — the executable git-annex invokes
  (stdin/stdout protocol loop).

## kotobase.net backend — **動作確認済み**（2026-07-17 実測）

**訂正**: 当初は kotobase-server に blob 面を足す設計だったが、net-kotobase の live
worker は kotobase-server ではなく `kotobase.istore` を使っており、
**`net.kotobase.store.{put,get,list,append,read}` は既に kotobase.net で live**。
よって **server 変更も deploy も不要**だった。

認証は **CACAO 自己発行** — actor が自分の Ed25519 鍵を生成し、鍵由来 did:key が
自分の graph（skill build-actor）。`resolve-viewer` は CACAO を検証して did を取り
出すだけなので、**自分の鍵 → 自分の tenant** で完結し owner の token 受け渡しは不要。

```sh
export KOTOBASE_ENDPOINT=https://kotobase.net       # これだけで kotobase backend
git annex initremote kb type=external externaltype=kotobase \
    encryption=none chunk=32KiB                      # chunk は必須（下記）
git annex copy asset.wav --to kb                     # STORE  -> ok
git annex drop asset.wav --force
git annex get  asset.wav --from kb                   # RETRIEVE -> (checksum...) ok
git annex fsck asset.wav                             # content integrity ok
```

**実測（kagaku の合成音声 95,788 bytes、実 git-annex v10 → 実 kotobase.net）**:
STORE ok / RETRIEVE (checksum) ok / fsck ok。tenant を `store.list` すると chunk
`SHA256E-s95788-S32768-C1..C3--…` が実在。

### 実装上の必須事項（実測で判明した罠 2 つ）

1. **`chunk=` は必須**。istore の `max-value-bytes` は 900000 だが、実測では 191KB の
   単一 put が 2 分超で timeout。**32KiB chunk で安定**（1KB/32KB は即応）。
2. **不在キーでも HTTP 200 が返る**（body が `{"ok":false,"error":"NotFound"}`）。
   HTTP status だけで present 判定すると **不在キーを present と誤答 → git-annex が
   STORE を丸ごとスキップし「保存したのに空」**になる。必ず **body の `ok`/`val`** を
   見る（回帰テスト `absent-key-returns-http200-with-error-body` で固定）。
3. **stdin close で即 `process.exit` しない**。実行中の非同期 STORE/RETRIEVE が応答
   前に殺される。in-flight を追跡し全完了後に exit する（bin の `track!`）。

## backend 一覧

The directory backend is fully working & verified. The **kotobase.net backend
client is written and unit-tested (mock fetch)** but needs one server-side piece:

- kotobase-server's public XRPC surface is Datomic-oriented
  (`datoms/transact/q/…`); raw block put/get is the *internal* injected store.
  To let git-annex store blobs directly, kotobase-server needs a thin **blob
  surface** exposing its `put!/get-fn/head` as
  `ai.gftd.apps.kotobase.blob.{put,get,head,remove}`. This client is written
  against exactly that contract.
- That server addition + deploy + credentials are **owner-gated** (see
  ADR-2607175000). Until then, use `store=directory` (or B2 as in `m365-archive`).

## Test

```sh
nbb --classpath src:test test/run.cljs
```
