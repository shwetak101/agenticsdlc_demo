#!/usr/bin/env bash
set -euo pipefail
[[ $# == 0 ]] || { echo "Usage: status.sh" >&2; exit 2; }
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
python3 "$SCRIPT_DIR/runtime.py" status
