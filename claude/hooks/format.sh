#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): formats a Clojure file the agent wrote with the zprint configuration of the
# project the file belongs to, the nearest .zprint.edn above it (#61). zprint reads a .zprint.edn only when
# ~/.zprintrc asks it to, so the options go on the command line, as clojure/zprint_check.sh does. With no
# configuration above the file, or no zprint, the file stays as written: zprint's own defaults (width 80)
# would reflow it against the project's style. It never fails the call.
{
  f="$(jq -r '.tool_input.file_path // empty')"
  case "$f" in *.clj | *.cljs | *.cljc) ;; *) exit 0 ;; esac
  [ -f "$f" ] && command -v zprint >/dev/null || exit 0
  d="$(dirname "$f")"
  while [ "$d" != / ] && [ ! -e "$d/.zprint.edn" ]; do d="$(dirname "$d")"; done
  [ -e "$d/.zprint.edn" ] || exit 0
  # zprint takes the map only when its first character opens it, so the comments above it are cut
  zprint "$(sed -n '/^{/,$p' "$d/.zprint.edn")" -w "$f"
} >/dev/null 2>&1
exit 0
