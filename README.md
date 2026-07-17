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

## kotobase.net backend — status

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
