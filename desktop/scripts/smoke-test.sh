#!/usr/bin/env bash
# Copyright 2026 R. Heller
# SPDX-License-Identifier: Apache-2.0
#
# Headless launch check for the packaged Linux app image:
#   ./gradlew createDistributable && scripts/smoke-test.sh
# Starts the app under xvfb with a throw-away data folder, requires it to keep
# running for SMOKE_SECONDS (default 10), then checks that stderr shows no
# exception and that the history database was created and seeded.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
app="${1:-$here/build/compose/binaries/main/app/EtabliFocus/bin/EtabliFocus}"
seconds="${SMOKE_SECONDS:-10}"

[[ -x "$app" ]] || { echo "smoke: launcher not found: $app (run ./gradlew createDistributable)" >&2; exit 2; }
command -v xvfb-run >/dev/null || { echo "smoke: xvfb-run is required" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
data="$work/data"

set +e
# No session bus: a local run must not reach the desktop's notification service.
env -u DBUS_SESSION_BUS_ADDRESS ETABLI_FOCUS_DATA_DIR="$data" xvfb-run -a -s "-screen 0 1280x900x24" \
    timeout -s TERM "$seconds" "$app" >"$work/stdout.log" 2>"$work/stderr.log"
status=$?
set -e

echo "--- stderr ---"
cat "$work/stderr.log"
echo "--------------"

if [[ $status -ne 124 ]]; then
    echo "smoke: FAIL — app exited with status $status before ${seconds}s" >&2
    exit 1
fi
if grep -Eiq 'exception|^error|fatal' "$work/stderr.log"; then
    echo "smoke: FAIL — stderr reports an exception" >&2
    exit 1
fi

python3 - "$data/etabli_focus.db" <<'EOF'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
version = db.execute("PRAGMA user_version").fetchone()[0]
categories = db.execute("SELECT COUNT(*) FROM category").fetchone()[0]
assert version == 2, f"schema version {version}"
assert categories == 6, f"{categories} seeded categories"
print(f"smoke: history database schema v{version}, {categories} default categories seeded")
EOF

echo "smoke: PASS — app stayed up for ${seconds}s under xvfb without exceptions"
