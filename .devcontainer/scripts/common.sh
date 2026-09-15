#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
export REFUNDOPS_REPO="${REFUNDOPS_REPO:-/workspaces/agenticsdlc_demo}"
export REFUNDOPS_RUNTIME_ROOT="${REFUNDOPS_RUNTIME_ROOT:-/workspaces/.refundops-runtime}"
export PYTHONDONTWRITEBYTECODE=1
readonly BEFORE_SHA=fb497a78441bfe9d112415ceaf20e3328b6fe09f
readonly AFTER_SHA=2dbef84d5c937d447a97492b9565ec0df36c9b41

fail() { printf '%s\n' "$*" >&2; exit 1; }
for tool in bash git python3 flock nohup java mvn; do
    command -v "$tool" >/dev/null || fail "Required tool missing: $tool"
done
[[ "$(uname -s)" == Linux ]] || fail "Run these lifecycle scripts on Linux."
python3 "$SCRIPT_DIR/runtime.py" layout
exec 9>"$REFUNDOPS_RUNTIME_ROOT/state/lifecycle.lock"
flock -w 300 9 || fail "Another runtime lifecycle operation is still running."

validate_tree() {
    local role="$1" sha="$2" tree="$REFUNDOPS_RUNTIME_ROOT/$1"
    python3 "$SCRIPT_DIR/runtime.py" tree-path "$role"
    [[ -d "$tree" && -f "$tree/.git" ]] || fail "Not a detached worktree: $tree"
    [[ "$(git -C "$tree" rev-parse --show-toplevel)" == "$tree" ]] ||
        fail "Wrong worktree directory: $tree"
    [[ "$(git -C "$tree" rev-parse --path-format=absolute --git-common-dir)" == \
        "$(git -C "$REFUNDOPS_REPO" rev-parse --path-format=absolute --git-common-dir)" ]] ||
        fail "Worktree belongs to another repository: $tree"
    [[ "$(git -C "$tree" rev-parse HEAD)" == "$sha" ]] || fail "Wrong immutable revision: $tree"
    ! git -C "$tree" symbolic-ref -q HEAD >/dev/null || fail "Worktree must remain detached: $tree"
    [[ -z "$(git -C "$tree" status --porcelain --untracked-files=normal)" ]] ||
        fail "User changes found; leaving worktree untouched: $tree"
}

prepare_runtime() {
    local role sha tree
    java -version >/dev/null 2>&1 || fail "Java is not usable."
    mvn -version >/dev/null 2>&1 || fail "Maven is not usable."
    for role in before after; do
        sha="$BEFORE_SHA"; [[ "$role" != after ]] || sha="$AFTER_SHA"
        tree="$REFUNDOPS_RUNTIME_ROOT/$role"
        python3 "$SCRIPT_DIR/runtime.py" tree-path "$role"
        if [[ ! -e "$tree" ]]; then
            git -C "$REFUNDOPS_REPO" cat-file -e "$sha^{commit}" 2>/dev/null ||
                git -C "$REFUNDOPS_REPO" fetch --no-tags origin "$sha"
            git -C "$REFUNDOPS_REPO" -c core.autocrlf=false worktree add --detach "$tree" "$sha"
        fi
        validate_tree "$role" "$sha"
    done
    # Maven is deliberately serial on the single four-core Codespace.
    for role in before after; do
        if [[ "${REBUILD:-0}" != 1 ]] && python3 "$SCRIPT_DIR/runtime.py" build-valid "$role"; then
            continue
        fi
        python3 "$SCRIPT_DIR/runtime.py" require-stopped "$role"
        printf 'Building and testing %s (log: %s/state/%s-build.log)\n' \
            "$role" "$REFUNDOPS_RUNTIME_ROOT" "$role"
        mvn --batch-mode --no-transfer-progress -q \
            -f "$REFUNDOPS_RUNTIME_ROOT/$role/pom.xml" clean verify \
            >"$REFUNDOPS_RUNTIME_ROOT/state/$role-build.log" 2>&1 ||
            fail "Build failed for $role; inspect its build log. No application was launched."
        python3 "$SCRIPT_DIR/runtime.py" mark-build "$role"
    done
}
