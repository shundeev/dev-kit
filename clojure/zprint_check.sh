#!/usr/bin/env bash
# Clojure sources match zprint's output with the project's .zprint.edn
# (a link to the shared dev-kit config). Ported from Sprecha.
#
#   .shared/clojure/zprint_check.sh            check every tracked file (CI)
#   .shared/clojure/zprint_check.sh --staged   check the index, as it will be committed (pre-commit)
#   .shared/clojure/zprint_check.sh --write    reformat every tracked file in place
#
# Exit status: 0 when every file is formatted, 1 when one is not, 2 on a usage
# error.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if ! command -v zprint > /dev/null 2>&1; then
    echo "zprint is not on PATH: https://github.com/kkinnear/zprint/releases/tag/1.3.0" >&2
    exit 1
fi

# The options map goes on the command line: zprint reads .zprint.edn only when
# ~/.zprintrc asks it to, which CI has no copy of, and --url caches the file.
# zprint takes the map only when its first character opens it, so the
# comments above the map are cut.
config=$(sed -n '/^{/,$p' .zprint.edn)
mode=${1:-}

case "${mode}" in
    --staged) mapfile -t files < <(git diff --cached --name-only --diff-filter=ACMR -- '*.clj' '*.cljs' '*.cljc') ;;
    '' | --write) mapfile -t files < <(git ls-files -- '*.clj' '*.cljs' '*.cljc') ;;
    *)
        echo "usage: $0 [--staged | --write]" >&2
        exit 2
        ;;
esac

[ "${#files[@]}" -eq 0 ] && exit 0

if [ "${mode}" = --write ]; then
    zprint "${config}" -w "${files[@]}"
    exit
fi

# The staged copy, not the working tree: a file staged in part is committed as
# the index has it. Compared byte for byte: a trailing blank line is a
# difference too.
staged=$(mktemp)
trap 'rm -f "${staged}"' EXIT
unformatted=()
for file in "${files[@]}"; do
    input=${file}
    if [ "${mode}" = --staged ]; then
        git show ":${file}" > "${staged}"
        input=${staged}
    fi
    # shellcheck disable=SC2094 # both ends read the file, neither writes it
    zprint "${config}" < "${input}" | cmp -s - "${input}" || unformatted+=("${file}")
done

if [ "${#unformatted[@]}" -gt 0 ]; then
    echo "Not formatted with zprint (.zprint.edn):" >&2
    printf '  %s\n' "${unformatted[@]}" >&2
    echo "Fix: .shared/clojure/zprint_check.sh --write" >&2
    exit 1
fi
