# hive-kdenlive

Kdenlive / MLT video editing as MCP tools: an `IAddon` (`hive.kdenlive`) that
edits and renders timelines with **no Kdenlive running**, reaches a patched
Kdenlive over HTTP when one is, and drives libmlt natively from clojurust.

Built from the reference project
[kdenlive-mcp](https://github.com/D-Ogi/kdenlive-api) (177 MCP tools over a
Kdenlive 26.03 fork), keeping its vocabulary and not its dependency on that
fork being built.

## Three ways to reach a timeline, one vocabulary

The fork's route catalog (`hive-kdenlive.kdenlive.routes`) names what can be
asked of a timeline: `media/import`, `timeline/add-track`,
`timeline/insert-clip`, `timeline/insert-space`, `timeline/zone-extract`,
`render/start`, and so on. Every transport answers those ids.

| transport | where the timeline lives | needs |
|---|---|---|
| **:http** (`kdenlive.client`) | inside a running scripting-enabled Kdenlive | the fork, listening on `KDENLIVE_SCRIPTING_ADDRESS:PORT` (default 127.0.0.1:9876), `KDENLIVE_SCRIPTING_SECRET` if set |
| **:document** (`kdenlive.document`, `mlt.timeline`) | a `.hkd.edn` file, with the `.mlt` melt renders written beside it | `melt` |
| **native** (`native/`) | libmlt in a clojurust process, over stdio | `cljrs`, libmlt-7 |

Stock Kdenlive (23.08 here) has almost no scripting surface, so the :document
transport is what works out of the box.

## MCP tools

| tool | what it does |
|---|---|
| `kdenlive_call` | one catalog route; with `project` it is answered headlessly against that timeline file, without it over HTTP |
| `render` | MLT XML to a video file with melt |
| `inspect_project` | summarise a `.kdenlive` / MLT file: profile, bin, tracks, duration |
| `routes` | the route catalog |
| `ping` | melt on PATH, fork reachable |

A headless edit, as an MCP client sends it:

```json
{"route": "media/import",         "params": {"paths": ["/clips/a.mkv", "/clips/b.mkv"]}, "project": "/work/cut.hkd.edn"}
{"route": "timeline/add-track",   "params": {"name": "V1", "isAudio": false},              "project": "/work/cut.hkd.edn"}
{"route": "timeline/insert-clip", "params": {"binId": "1", "trackId": "3", "position": 0},  "project": "/work/cut.hkd.edn"}
{"route": "render/start",         "params": {"outputFile": "/work/cut.mkv"},               "project": "/work/cut.hkd.edn"}
```

The headless verbs refuse what they cannot do faithfully instead of
approximating it. Examples: an insert that would overlap a clip, space or a
zone that would cut a clip in two, or an import with one unreadable file (all
or nothing). Media lengths come from `melt <file> -consumer xml`, the engine
that renders, converted to the project's frame rate.

### Layers: titles, effects, motion, audio

The same transport composes layered clips, again with the fork's route ids and
params (`clipId` or `id` names a timeline clip):

| route | headless meaning |
|---|---|
| `project/profile` | `width` `height` `fpsNum` `fpsDen`; the display aspect follows the frame (1080x1920 is 9:16) |
| `media/create-title` | a Kdenlive title clip, `duration` frames: the fork's `xml`, or a text spec (`text` `font` `size` `weight` `color` `background` `align` `x` `y` `boxWidth`) that builds the same title XML |
| `clip/append-effect` | Kdenlive effect ids keep Kdenlive's meaning: `fade_from_black` / `fade_to_black` (`duration`, and `alpha` to fade to transparent), `fadein` / `fadeout` for audio; any other id is an MLT service with its params |
| `clip/transform-keyframe` | position, size and opacity at a clip-relative `frame` (Kdenlive's qtblend `rect`) |
| `clip/opacity`, `clip/volume`, `clip/audio-fade` | constant opacity, gain in dB, audio fade in/out frames |

Every video track is composited onto the tracks below and every track is
mixed, as Kdenlive's internal transitions do, so a transparent PNG or a title
on V2 sits on V1 and all audio tracks are heard. An `.mp4` renders as H.264
yuv420p with AAC. A 15 s vertical promo (background with a slow push-in,
cards that rise and fade, a music bed) is about twenty of these calls.

The addon mounts lazily (`:addon/lifecycle {:policy :lazy :idle-ms 900000}`):
on the first call to one of its tools, released after fifteen idle minutes.

## Layout

```
mlt/xml.cljc        XML text <-> nodes, by hand; byte-stable round trip      portable
mlt/model.cljc      MLT builders: profile, producer, playlist, tractor ...    portable
mlt/time.cljc       frames <-> clock at a profile fps                          portable
mlt/project.cljc    read a .kdenlive / MLT file back into data                 portable
mlt/timeline.cljc   the headless timeline: verbs keyed by route id, ->document portable
kdenlive/routes.cljc  the fork's route catalog as data                        portable
kdenlive/client.clj   IKdenlive port + HttpKdenlive                            JVM
kdenlive/document.clj IKdenlive over a timeline file, melt probe and render    JVM
render.clj            IRender + melt                                           JVM
addon.clj             the IAddon
native/               cljrs + Rust cdylib over libmlt-7 (see native/README.md)
```

"Portable" means the same source runs on the JVM, ClojureWasm (`cljw`) and
clojurust (`cljrs`), and the gates below prove it on all three.

## Verify

```sh
clojure -M:test                                         # 87 tests: unit + real melt integration (self-skips without melt/ffmpeg)
cljw -cp src dev/portability.cljw                       # mlt.* on ClojureWasm
cljw -cp src:test dev/oracle.cljw                       # byte oracle: cljw emission == JVM fixture, 200 passes
cljrs run dev/oracle.cljrs --src-path src --src-path test   # same, clojurust
cljrs run --src-path src dev/timeline_portability.cljc  # headless timeline, 60 passes (also cljw / JVM)
native/build.sh && native/verify.sh                     # libmlt from cljrs, checked by ffprobe and pixel samples
```

## Measured along the way

- **Frame counts are not enough.** Pixel samples show two cases where the frame
  count was right and the picture was wrong. A colour producer spelled
  `color:#ff0000` under `mlt_service=color` renders near-black. A `<blank>` with
  nothing below it renders white, which is why every document gets a black
  background track. The render tests sample pixels with ffmpeg.
- **The HTTP transport had never reached the fork.** It defaulted to port 4700
  (the fork listens on 9876). It also read the JDK's response through reflection
  on a package-private class, which threw on every response. A stubbed port hid
  both. `http_wire_test` now drives the real client against a local server that
  answers like the fork.
- **The manifest was invisible to discovery.** `:addon/maturity :alpha` is not a
  MountSpec value, so schema-validated discovery found zero specs. Now
  `:experimental`, and `manifest_test` holds it.
- **clojurust defects**, all worked around in this code: `assoc-in` through a
  vector turns it into a map; `true?`/`false?`/`identical?` go wrong once a fn is
  hot; no exit primitive; a callback from a cdylib breaks on vectors past 32
  elements; the MLT consumer must be stopped before it is closed.
- **Decoding, not the timeline.** A 1080p render has colorspace 709. ffmpeg
  decodes it untagged as BT.601, so full red reads back as (216,0,0). The
  integration test classifies colours rather than demanding 255.
- **Tracks do not composite or mix by themselves.** With no transition the top
  video track replaces everything under it, alpha included (a transparent
  overlay rendered on black), and only the top track's audio is heard (a second
  tone measured -46 dB against -9 dB). Hence a qtblend and a mix per track.
- **Keyframes in a nested filter count from the filter's in**, and without an
  in/out they are read as source frames: a fade on a clip trimmed to start at
  frame 60 never happened. Every entry filter carries in/out.
- **melt renders "INVALID" and exits 0 when its Qt module cannot load**, which
  it refuses to do without `DISPLAY` or `WAYLAND_DISPLAY`; titles, qtext and
  qtblend all go. `QT_QPA_PLATFORM=offscreen` alone does not help. Every melt
  this addon spawns gets the offscreen platform plus a placeholder `DISPLAY`,
  and a render whose stderr says `failed to load` is refused.
- **Titles speak Qt5.** `font-weight="700"` renders regular; Qt5's bold is 75.
  The text spec takes CSS weights and converts. MLT's own 8-digit colours are
  `#AARRGGBB`, so `#ff0000ff` is blue; the text spec takes CSS `#rrggbbaa`.
- **Probe lengths are at melt's rate, not the project's.** A 3 s WAV probes as
  75 frames (25 fps); in a 30 fps project it is 90.

## Not verified here

- The HTTP transport against a live scripting-enabled Kdenlive. The fork is not
  built on this machine; `http_wire_test` holds the client to the fork's source.
- Opening the generated `.mlt` in the Kdenlive GUI. It is an MLT document melt
  renders, not a `.kdenlive` project with Kdenlive's bin metadata.
- The layer checks of `dev/timeline_portability.cljc` on clojurust. JVM and
  cljw pass them 60 times; the clojurust debug build aborts in pass 29 inside
  `mlt.xml/emit` with `GcPtr::get() on freed object`, a clojurust GC defect
  (the original checks pass 100+ times on it).
- Same-track mixes and compositions (`/timeline/transitions`,
  `/timeline/compositions`) are not headless verbs yet.

## License

MIT.
