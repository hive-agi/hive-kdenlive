#!/usr/bin/env bash
# Run the shared byte oracle on existing runtimes, sequentially, without builds.
set -euo pipefail

if [[ ${1:-} == --help ]]; then
  cat <<'HELP'
Usage: bash dev/verify_portability.sh [cljw|cljrs ...]

Defaults to both hosts. Each runs the existing 200-pass byte oracle.
CLJW / CLJRS select executable paths or commands (defaults: cljw / cljrs).
PORTABILITY_TIMEOUT sets seconds per host (default: 120).
PORTABILITY_REPORT_DIR selects the output directory (default: a new temp dir).
Logs and a TSV report retain binary path, SHA-256, version, exit code, and time.
No Cargo builds, native libraries, or GUI processes are started.
HELP
  exit 0
fi

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
seconds=${PORTABILITY_TIMEOUT:-120}
[[ $seconds =~ ^[1-9][0-9]*$ ]] || { echo 'PORTABILITY_TIMEOUT must be a positive integer' >&2; exit 2; }
(( $# )) || set -- cljw cljrs
for host in "$@"; do
  case $host in cljw|cljrs) ;; *) echo "Unknown host: $host" >&2; exit 2 ;; esac
done
for tool in timeout sha256sum realpath nice; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 2; }
done

report=${PORTABILITY_REPORT_DIR:-$(mktemp -d -t kdenlive-portability.XXXXXX)}
mkdir -p "$report"
report=$(realpath "$report")
sha256sum "$repo/dev/oracle/expected.mlt" > "$report/fixture.sha256"
printf 'host\tstatus\texit_code\tseconds\tbinary\tsha256\tversion\n' > "$report/results.tsv"
echo "Portability report: $report"
failed=0
for host in "$@"; do
  case $host in
    cljw) requested=${CLJW:-cljw}; args=(-cp src:test dev/oracle.cljw) ;;
    cljrs) requested=${CLJRS:-cljrs}; args=(run dev/oracle.cljrs --src-path src --src-path test) ;;
  esac
  if ! binary=$(command -v "$requested") || [[ ! -x $binary ]]; then
    printf '%s\tmissing\t127\t0\t%s\t-\t-\n' "$host" "$requested" >> "$report/results.tsv"
    echo "$host: executable missing; set ${host^^}"
    failed=1
    continue
  fi
  binary=$(realpath "$binary")
  digest=$(sha256sum "$binary"); digest=${digest%% *}
  version=$(timeout --kill-after=2s 5s "$binary" --version 2>&1) || version='version unavailable'
  version=${version//$'\n'/ }; version=${version//$'\t'/ }
  echo "$host: $binary ($version)"
  started=$SECONDS
  code=0
  (cd "$repo" && timeout --kill-after=5s "${seconds}s" nice -n 10 "$binary" "${args[@]}") > "$report/$host.log" 2>&1 || code=$?
  elapsed=$((SECONDS - started))
  status=passed
  if (( code != 0 )); then
    status=failed
    (( code == 124 || code == 137 )) && status=timeout
    failed=1
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$host" "$status" "$code" "$elapsed" "$binary" "$digest" "$version" >> "$report/results.tsv"
  echo "$host: $status (${elapsed}s, exit $code)"
  tail -n 8 "$report/$host.log"
done
exit "$failed"
