#!/usr/bin/env bash
set -euo pipefail
[[ $# == 0 || ( $# == 1 && "$1" == --rebuild ) ]] || { echo "Usage: prepare.sh [--rebuild]" >&2; exit 2; }
REBUILD=0
[[ $# == 0 ]] || REBUILD=1
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
prepare_runtime
