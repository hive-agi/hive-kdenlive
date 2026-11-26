#!/usr/bin/env bash
# No-build native/component performance baseline for existing cljw / cljrs
# binaries. Sequential, small, reproducible. See dev/perf/README.md.
set -uo pipefail

if [[ ${1:-} == --help ]]; then
  cat <<'HELP'
Usage: bash dev/perf/run_baseline.sh

Env:
  CLJW       cljw executable (default: cljw on PATH)
  CLJRS      cljrs executable (default: cljrs on PATH; absent => "unavailable")
  REPEATS    process launches per (host, workload) (default 5)
  COMPUTE_N  loop length for compute.cljc (default 100000)
  CROSS_N    calls per trial for crossing.cljw (default 20000)
  TRIALS     in-process timed trials per launch (default 5)
  PERF_TIMEOUT  seconds per launch (default 60)
  PERF_REPORT_DIR  output dir (default: new temp dir)

Never builds anything (no cargo, no zig). Runs one process at a time under
nice -n 10 and timeout. Records per binary: resolved path, SHA-256, version
string, build profile; per launch: wall/user/sys seconds and peak RSS from
/usr/bin/time -v; per workload: the in-process ns figures the program prints.
Writes runs.tsv, binaries.tsv, logs/ and summary.md into the report dir.
HELP
  exit 0
fi

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repeats=${REPEATS:-5}
compute_n=${COMPUTE_N:-100000}
cross_n=${CROSS_N:-20000}
trials=${TRIALS:-5}
secs=${PERF_TIMEOUT:-60}
for v in "$repeats" "$compute_n" "$cross_n" "$trials" "$secs"; do
  [[ $v =~ ^[1-9][0-9]*$ ]] || { echo "numeric env must be a positive integer: $v" >&2; exit 2; }
done
for tool in /usr/bin/time timeout sha256sum realpath nice awk; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 2; }
done

report=${PERF_REPORT_DIR:-$(mktemp -d -t native-perf.XXXXXX)}
mkdir -p "$report/logs"; report=$(realpath "$report")
printf 'host\tstatus\tbinary\tsha256\tversion\tprofile\n' > "$report/binaries.tsv"
printf 'host\tworkload\trun\texit\twall_s\tuser_s\tsys_s\tmaxrss_kb\tinproc\n' > "$report/runs.tsv"
{
  echo "date: $(date -Is)"
  echo "kernel: $(uname -srm)"
  echo "cpu: $(awk -F: '/model name/{print $2; exit}' /proc/cpuinfo | sed 's/^ *//')"
  echo "nproc: $(nproc)"
  echo "loadavg_start: $(cut -d' ' -f1-3 /proc/loadavg)"
  echo "mem_available_kb_start: $(awk '/MemAvailable/{print $2}' /proc/meminfo)"
  echo "repeats=$repeats compute_n=$compute_n cross_n=$cross_n trials=$trials timeout=$secs"
  echo "harness_sha256: $(sha256sum "$0" | cut -d' ' -f1)"
  sha256sum "$here"/*.cljc "$here"/*.cljw "$here"/fixtures/*.wasm | sed 's/^/workload_sha256: /'
} > "$report/env.txt"

# Build profile is read from the binary, never assumed. cljw prints it in
# --version ("(ReleaseSafe, wasm)"); cljrs prints none, so it is inferred from
# the cargo target dir it lives in, else recorded as unknown.
profile_of() {
  local host=$1 bin=$2 ver=$3
  case $host in
    cljw) [[ $ver =~ \((Debug|ReleaseSafe|ReleaseFast|ReleaseSmall) ]] && { echo "${BASH_REMATCH[1]}"; return; } ;;
    cljrs) case $bin in */target/debug/*) echo debug; return ;; */target/release/*) echo release; return ;; esac ;;
  esac
  echo unknown
}

run_one() { # host bin workload run cwd args...
  local host=$1 bin=$2 wl=$3 run=$4 cwd=$5; shift 5
  local log="$report/logs/$host-$wl-$run.log" tv="$report/logs/$host-$wl-$run.time"
  local code=0
  (cd "$cwd" && timeout --kill-after=5s "${secs}s" nice -n 10 /usr/bin/time -v -o "$tv" "$bin" "$@") > "$log" 2>&1 || code=$?
  local wall user sys rss
  wall=$(awk -F': ' '/Elapsed \(wall clock\)/{n=split($2,a,":"); s=0; for(i=1;i<=n;i++) s=s*60+a[i]; print s}' "$tv" 2>/dev/null)
  user=$(awk -F': ' '/User time/{print $2}' "$tv" 2>/dev/null)
  sys=$(awk -F': ' '/System time/{print $2}' "$tv" 2>/dev/null)
  rss=$(awk -F': ' '/Maximum resident/{print $2}' "$tv" 2>/dev/null)
  local inproc
  inproc=$(grep -E '^(result|cell)=' "$log" | tr '\n' ';' | tr '\t' ' ')
  [[ -z $inproc ]] && inproc=$(grep -c '^startup-ok$' "$log" | sed 's/^1$/startup-ok/;s/^0$/-/')
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$host" "$wl" "$run" "$code" \
    "${wall:--}" "${user:--}" "${sys:--}" "${rss:--}" "$inproc" >> "$report/runs.tsv"
}

for host in cljw cljrs; do
  case $host in cljw) requested=${CLJW:-cljw} ;; cljrs) requested=${CLJRS:-cljrs} ;; esac
  if ! bin=$(command -v "$requested" 2>/dev/null) || [[ ! -x $bin ]]; then
    printf '%s\tunavailable\t%s\t-\t-\t-\n' "$host" "$requested" >> "$report/binaries.tsv"
    echo "$host: UNAVAILABLE ($requested not found; no build attempted)"
    continue
  fi
  bin=$(realpath "$bin")
  sha=$(sha256sum "$bin" | cut -d' ' -f1)
  ver=$(timeout 5s "$bin" --version 2>&1 | head -1 | tr '\t' ' ') || ver='version unavailable'
  prof=$(profile_of "$host" "$bin" "$ver")
  printf '%s\tok\t%s\t%s\t%s\t%s\n' "$host" "$bin" "$sha" "$ver" "$prof" >> "$report/binaries.tsv"
  echo "$host: $bin [$prof] $ver"
  for r in $(seq 1 "$repeats"); do
    case $host in
      cljw)
        run_one cljw "$bin" startup  "$r" "$here" startup.cljc
        run_one cljw "$bin" compute  "$r" "$here" compute.cljc "$compute_n" "$trials"
        run_one cljw "$bin" crossing "$r" "$here" crossing.cljw "$cross_n" "$trials" ;;
      cljrs)
        # cljrs has no wasm/component surface; its native crossing is the
        # cdylib bridge (native/), which needs a profile-matched build: not run.
        run_one cljrs "$bin" startup "$r" "$here" run startup.cljc
        run_one cljrs "$bin" compute "$r" "$here" run compute.cljc "$compute_n" "$trials" ;;
    esac
  done
done
echo "loadavg_end: $(cut -d' ' -f1-3 /proc/loadavg)" >> "$report/env.txt"

# Summary: medians across launches. Startup = startup workload wall time;
# steady-state and crossing are the in-process ns figures (startup excluded).
awk -F'\t' '
  function med(arr, n,   i,j,t) { for(i=1;i<=n;i++) for(j=i+1;j<=n;j++) if(arr[j]<arr[i]){t=arr[i];arr[i]=arr[j];arr[j]=t}
                                  return (n%2)? arr[(n+1)/2] : (arr[n/2]+arr[n/2+1])/2 }
  NR==1 { next }
  $4 != 0 { fail[$1"|"$2]++; next }
  { k=$1"|"$2; n[k]++; W[k,n[k]]=$5; R[k,n[k]]=$8; keys[k]=1
    m=split($9, parts, ";")
    for (p=1; p<=m; p++) if (parts[p] != "") {
      cell=$2; if (match(parts[p], /cell=[^ ]+/)) cell=substr(parts[p], RSTART+5, RLENGTH-5)
      if (match(parts[p], /ns_per_(iter|call)=[0-9]+/)) { v=substr(parts[p], RSTART, RLENGTH); sub(/.*=/, "", v)
        c=$1"|"cell; cn[c]++; C[c,cn[c]]=v; ckeys[c]=1 } } }
  END {
    print "| host | workload | ok launches | median wall s | median peak RSS KB |"
    print "|---|---|---|---|---|"
    for (k in keys) { split(k, a, "|"); for(i=1;i<=n[k];i++){w[i]=W[k,i]; r[i]=R[k,i]}
      printf "| %s | %s | %d (failed %d) | %.3f | %d |\n", a[1], a[2], n[k], fail[k]+0, med(w,n[k]), med(r,n[k]) }
    for (k in fail) if (!(k in keys)) { split(k, a, "|"); printf "| %s | %s | 0 (failed %d) | - | - |\n", a[1], a[2], fail[k] }
    print ""
    print "| host | in-process cell | median ns/op (median of per-launch medians) | launches |"
    print "|---|---|---|---|"
    for (c in ckeys) { split(c, a, "|"); for(i=1;i<=cn[c];i++) v2[i]=C[c,i]
      printf "| %s | %s | %d | %d |\n", a[1], a[2], med(v2,cn[c]), cn[c] }
  }' "$report/runs.tsv" > "$report/summary.md"
{ echo; echo "Binaries:"; echo; column -t -s $'\t' "$report/binaries.tsv"; } >> "$report/summary.md"
cat "$report/summary.md"
echo "Report: $report"
