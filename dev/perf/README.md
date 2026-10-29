# Native/component performance baseline (no-build)

Kanban `[CLJW-CLJRS-PERF]` 20260914010258-7734b8ca. This harness measures
**existing** `cljw` / `cljrs` binaries. It never runs `cargo` or `zig`. Runs go one
at a time under `nice -n 10` and `timeout`, and each run is small, because the
host is short on RAM.

    bash dev/perf/run_baseline.sh            # CLJW / CLJRS env select binaries
    bash dev/perf/run_baseline.sh --help     # all knobs

| file | measures |
|---|---|
| `startup.cljc` | a process that only prints: launch + runtime init + exit (the startup floor) |
| `compute.cljc` | `loop/recur` sum 0..n-1, timed **in-process** after a warm pass (steady state, startup excluded) |
| `crossing.cljw` | ns per call, in-process: plain fn call, core `wasm/call` (auto engine and `:interp`), `wasm/component-call` |
| `fixtures/` | `add.wasm`/`.wat` (ClojureWasm `bench/wasm/ffi`) and `echo.component.wasm` (ClojureWasm `test/e2e/fixtures/wasm`), copied byte-for-byte |
| `run_baseline.sh` | records each binary's path, SHA-256, `--version` and build profile; records each launch's wall/user/sys time and peak RSS (`/usr/bin/time -v`) and the in-process figures; writes `env.txt`, `binaries.tsv`, `runs.tsv`, `summary.md`, `logs/` |

How the three costs are kept apart:

- **Startup** is the wall time of `startup.cljc`.
- **Steady-state compute** is the ns/iteration the program measures itself, so launch cost is not in it.
- **Crossings** are ns/call minus the `fn-call` cell, so the interpreter's own call cost is not counted as boundary cost.

Wall time from the `compute` and `crossing` workloads includes startup. Do not read it as compute time.

How the build profile is recorded:

- `cljw`: read from `--version`.
- `cljrs`: `--version` does not print a profile, so it is inferred from a `target/debug|release` path, otherwise recorded as `unknown`.
- A host whose binary is missing is recorded as **unavailable**. The harness never builds a replacement.

## Measured 2026-10-29 (Ultra 9 185H, 22 threads, Linux 7.0)

The host was loaded: load average 12.9 / 15.5 / 10.8, about 15 GB available, other JVMs running.
Five launches per workload. Each in-process figure is the median of 5 trials. Read the ratios, not the exact digits.

### Profile availability

| host | binary | profile | status |
|---|---|---|---|
| cljw A | `/home/linuxbrew/.linuxbrew/Cellar/cljw/1.15.3/bin/cljw`, sha256 `88ccd5b7…dae3` | ReleaseSafe | measured |
| cljw B | `ClojureWasm-e2e-p16/zig-out/bin/cljw` (built 2026-10-22, branch `swarm/e2e-phase16`), sha256 `65e024a0…fdcc` | ReleaseSafe | measured |
| cljrs | none on PATH. The debug binary named in memory 20260914010216-7e6b1184 (`/home/leibniz/...`) no longer exists, and no `target/` exists under any clojurust worktree | — | **UNAVAILABLE** (no build attempted) |

This report contains **no cljw-vs-cljrs comparison**. A ReleaseSafe cljrs build does
not exist, and the old DEBUG cljrs number (0.76 s / 21.9 MB peak RSS for a 10k-iteration
loop, startup included) is a different build profile, so it is not a release comparison.

### Results (medians across 5 launches)

| measure | cljw A (Homebrew 1.15.3) | cljw B (e2e-p16) |
|---|---|---|
| startup wall | 0.02 s | 0.01 s |
| startup peak RSS | 13.4 MB | 14.2 MB |
| compute, ns/iter (n=100k) | 97 | 100 |
| crossing workload peak RSS | 17.4 MB | — |
| `fn-call` ns | 173 (153–183) | 154 (135–183) |
| core `wasm/call`, auto engine, ns | **1255** (1171–1296) | **611** (527–792) |
| core `wasm/call`, `:interp`, ns | 500 (437–573) | 473 (394–496) |
| `wasm/component-call` echo-f64, ns | 1205 (807–1287) | **1084** (861–1207) |

Runs: `/tmp/native-perf-20261029` (A) and `/tmp/native-perf-20261029-e2ep16` (B).
These directories are ephemeral, so the numbers above are the durable copy.

### Reading

1. **Startup is not the bottleneck for cljw.** It takes 10–20 ms at about 13–14 MB peak RSS, and the RSS barely grows (+4 MB) with wasm and a component loaded.
2. **The auto-engine core crossing on the Homebrew 1.15.3 binary costs about 2.5x `:interp` (1255 vs 500 ns).** This matches the zwasm/D-585 by-name export walk described in ClojureWasm `.dev/wasm_percall_findings.md`. Binary B was built 2026-10-22 and drops the auto cell to 611 ns. That fits the merged fork fix, but the binary's zwasm pin was not checked. The same fix landing in a release (it is not yet in Homebrew 1.15.3) is the biggest gain within reach for crossing-heavy code.
3. **On the newer binary, `wasm/component-call` is now the largest per-call cost.**
   - About 1.08 µs, versus 0.61 µs for a core call and 0.15 µs for a fn call.
   - Subtracting the fn-call cost leaves about 930 ns of boundary cost, roughly 2x the core crossing.
   - The component path did not improve with the core export fix (1205 → 1084, which is within noise).
   - The canonical-ABI lift/lower, or a per-call name resolution in the component layer, is therefore the next measured bottleneck.

## Bounded follow-up (justified by the evidence above)

**Find where `wasm/component-call`'s per-call overhead goes, and cache the component
export resolution per handle if that is where it goes.** This is the same shape as the core
`exportSig` cache, applied to the component path.

- Scope: one call path in ClojureWasm.
- Acceptance: the `component` cell of `crossing.cljw` lands within 1.5x of the `core-call` cell on the same binary, A/B'd back-to-back with this harness.
- Out of scope: the string-seq work on `perf/string-seq`.

The cljrs side needs a separate decision: make one ReleaseSafe-equivalent (`--release`) build, budgeted and done
manually, before any cross-runtime claim. The harness picks it up through `CLJRS=`.
