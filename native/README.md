# hive-kdenlive native MLT bridge

libmlt-7, the engine Kdenlive renders with, reached from **clojurust** (cljrs)
with no `melt` process and no JVM.

    cljrs ──► libhive_kdenlive_native.so ──► libmlt-7.so.7 ──► avformat, xml, color, ...
    (Clojure)   (Rust cdylib, dlopens MLT)

| file | role |
|---|---|
| `rust/src/lib.rs` | `hive-kdenlive.native/*`: `available?`, `version`, `probe`, `render`, `stdin-line` |
| `src/hive_kdenlive/native_probe.cljrs` | end-to-end proof: render, probe back, document, render again |
| `src/hive_kdenlive/native_sidecar.cljrs` | libmlt as a long-lived process: one EDN request per stdin line |
| `verify.sh` | the probe, then ffprobe and ffmpeg pixel samples as an oracle that is not MLT |

`cljrs.edn` also puts `../src` on the path, so a cljrs program can build a
project with the portable `hive-kdenlive.mlt.*` core and render it in the same
process.

## Build and verify

    ./build.sh      # debug profile, pairs with the DEBUG cljrs binary (see below)
    ./verify.sh     # probe + ffprobe frame count + pixel colours

## The sidecar protocol

    cd native && cljrs run src/hive_kdenlive/native_sidecar.cljrs

    -> {:id 1 :op :probe :resource "clip.mkv"}
    <- {:id 1 :ok true :result "{\"ok\":{\"length\":25,...}}"}
    -> {:id 2 :op :render :resource "edit.mlt" :target "out.mkv" :opts-json "{\"vcodec\":\"mpeg4\"}"}
    <- {:id 2 :ok true :result "{\"ok\":{\"frames\":75,\"bytes\":...}}"}
    -> {:id 3 :op :quit}

The first protocol line is `{:ready true :mlt "..."}`. Results pass the
bridge's JSON envelope through as text rather than re-shaping it, so there is
one vocabulary, the bridge's.

`render` options: any MLT avformat consumer property (`vcodec`, `acodec`, `f`,
`an`, ...) plus the bridge's own `in`, `out` and `timeout_ms`.

## Measured

**Stop the consumer before closing it.** `mlt_consumer_is_stopped` turns true
when the render loop leaves, not when the consumer's threads are joined;
`mlt_consumer_stop` joins them, and `melt` calls it unconditionally. Closing
without it made the next render in the same process die with SIGFPE in 2 of 4
runs; with it, 8 of 8 green.

**Frame counts are not enough.** A document whose colour producer was spelled
`color:#ff0000` under `mlt_service=color` rendered the right number of frames
in near-black; a blank with no background track rendered white. `verify.sh`
samples pixels for that reason.

**The profile trap.** The cdylib statically links its own cljrs runtime crates,
so it must match the cargo profile of the cljrs binary loading it; cljrs loads
project libraries from `target/debug`.

**clojurust has no stdin reader**, so `stdin-line` comes from the cdylib. And on
clojurust `true?`/`false?`/`identical?` answer wrongly once a fn is hot; the
sidecar compares booleans with `=`.
