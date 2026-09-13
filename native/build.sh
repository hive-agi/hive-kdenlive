#!/usr/bin/env bash
# Build the native MLT bridge: the Rust cdylib that cljrs loads.
#
# PROFILE MATTERS. The cdylib statically links its own copy of the cljrs
# runtime crates, so the host `cljrs` binary and this library must be the SAME
# cargo profile or the first call into a registered fn segfaults. cljrs loads
# the project library from target/debug (native_lib_path(..., release = false)
# in clojurust's cljrs/src/native/mod.rs), so a debug build here pairs with the
# DEBUG cljrs binary. Same trap, same fix as hive-k8s/native.
#
# libmlt-7 is resolved at run time (HIVE_MLT_LIB, else the soname), so this
# builds without MLT headers or a libmlt-dev package.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

avail=$(free -g | awk '/^Mem:/ {print $7}')
if (( avail < 3 )); then
  echo "REFUSED: ${avail} GB available, a cargo build here wants at least 3" >&2
  exit 2
fi

echo "==> rust: libhive_kdenlive_native.so (cljrs cdylib)"
cd "$here/rust"
cargo build -j 4

echo
ls -la "$here/rust/target/debug/libhive_kdenlive_native.so"
echo
echo "verify with:  $here/verify.sh"
