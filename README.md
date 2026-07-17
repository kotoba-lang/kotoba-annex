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

## kotobase.net backend — status (2026-07-17 実測)

**訂正**: 当初は kotobase-server に blob 面を足す設計だったが、net-kotobase の live
worker は kotobase-server ではなく `kotobase.istore` を使っており、
**`net.kotobase.store.{put,get,list,append,read}` は既に kotobase.net で live**
（未認証で叩くと `{"ok":false,"error":"Unauthorized"}` = 面は存在、認証待ち）。
よって **server 変更も deploy も不要**。

| 検証項目 | 実測 |
|---|---|
| CACAO 自己発行（自鍵 → did:key、aud=`did:web:kotobase.net`） | ✓ mint 成功 |
| 実 kotobase.net へ direct store→present→retrieve（5B / 1KB / 32KB） | ✓ 完全往復 |
| 実 kotobase.net へ direct store（191KB） | ⚠ 2 分超で timeout（大きい値は実用外） |
| **git-annex → kotobase.net（STORE/RETRIEVE）** | ✗ **未達**（下記） |

**未解決**: git-annex 経由の STORE が **実際には永続化されていない**（tenant を
`store.list` すると chunk キーが 1 つも無い）のに、`git annex copy` が ok を返した
ケースがある = bin の async 応答が実完了前に success を返している疑い。RETRIEVE は
20ms で TRANSFER-FAILURE（データ不在）。**「動いている」とは言えない状態**なので、
kotobase backend は実運用に使わないこと。directory store は全操作検証済みで実用可。

次の一手: bin の STORE 応答が store! の Promise 完了を正しく待つか検証（readline の
行ハンドラと process 終了の競合を疑う）、および大きい値の実用サイズ上限の確定。

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
