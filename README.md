# hive-kdenlive

Kdenlive / MLT video editing as MCP tools.

- `hive-kdenlive.mlt.*` — portable MLT document core (JVM, ClojureWasm, cljrs):
  XML parse/emit (`mlt.xml`), document builders (`mlt.model`), time/frame
  arithmetic (`mlt.time`). Byte-deterministic, no IO, core + string only.
- `hive-kdenlive.render` — headless melt boundary (JVM): `IRender` port,
  `MeltRenderer` process adapter, `render!` / `render-doc!` facade.
- `hive-kdenlive.kdenlive.*` — HTTP transport to the Kdenlive scripting fork,
  driven by the route catalog (`kdenlive.routes`) as data.
- `hive-kdenlive.addon` — the `hive.kdenlive` IAddon: 4 MCP tools (`render`,
  `kdenlive_call`, `routes`, `ping`) over the seams above.
- `native/` — cljrs native bridge: a Rust cdylib dlopening libmlt-7, rendering
  and probing media in-process with no JVM and no melt subprocess.

## Verify

```sh
clojure -M:test unit          # JVM unit suite (kaocha)
clojure -M:test integration   # real melt render (self-skips without melt)
cljw -cp src dev/portability.cljw   # ClojureWasm portability gate
cd native && ./build.sh       # build the cdylib (debug profile pairs with debug cljrs)
cd native && cljrs run src/hive_kdenlive/native_probe.cljrs -- /tmp/probe
```
