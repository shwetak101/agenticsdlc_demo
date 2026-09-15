#!/usr/bin/env bash
set -euo pipefail
[[ $# == 0 ]] || { echo "Usage: start.sh (ports and credentials use environment variables)" >&2; exit 2; }
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
# Explicit defaults are public synthetic-demo credentials, never real secrets.
export REFUNDS_DAHNESH_PASSWORD="${REFUNDS_DAHNESH_PASSWORD-dahnesh}"
export REFUNDS_SHWETA_PASSWORD="${REFUNDS_SHWETA_PASSWORD-shweta}"
python3 "$SCRIPT_DIR/runtime.py" preflight
prepare_runtime
python3 "$SCRIPT_DIR/runtime.py" start
