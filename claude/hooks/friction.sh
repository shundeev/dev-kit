#!/usr/bin/env bash
# The friction recorder (#28, #55): one line per Claude Code hook event, appended to
# ${XDG_STATE_HOME:-~/.local/state}/dev-kit/friction/<YYYY-MM>.jsonl on this machine, readable by its owner
# alone. `kit retro` reads it. The log never leaves the machine.
#
#   friction.sh <event>    the hook's JSON on stdin
#
# It never blocks or slows a call: it prints nothing and exits 0, whatever fails. A line is written in one
# piece under a lock, so parallel calls do not interleave; strings longer than 2 KB are cut.
# Every line: time, event, session, turn (prompt_id), agent, tool, tool_use_id, the tool's input. Besides:
#   PreToolUse         the task: branch and origin of the directory the call touches, read now: a file's
#                      directory, an existing directory after `cd` or `git -C`, else the cwd
#   PermissionRequest  the ask rules in force: managed, the project's, its local ones, the user's
#   UserPromptSubmit   the owner's message
#   Stop, SubagentStop whether the agent's last message asked the owner ("needs input:"); the message itself
#                      is not kept. A stop also drops log files older than 100 days.
#   StopFailure        a turn that ended in an error
#   PermissionDenied   the classifier's reason
#   HookDenied         a refusal by dev-kit's PreToolUse hook (pretooluse.sh records it), its reason
{
  umask 077
  event="$1"
  input="$(cat)"
  log="${XDG_STATE_HOME:-$HOME/.local/state}/dev-kit/friction"
  extra='{}'
  case "$event" in
    PreToolUse)
      dir=""
      file="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$input")"
      if [ -n "$file" ]; then
        dir="$(dirname "$file")"
        while [ "$dir" != / ] && [ ! -d "$dir" ]; do dir="$(dirname "$dir")"; done
      else
        while IFS= read -r d; do
          [ -d "$d" ] && { dir="$d"; break; }
        done < <(jq -r --arg q "'" '.tool_input.command // "" |
          ("(^|[;&|(]\\s*)(cd|git\\s+-C)\\s+") as $at |
          (capture($at + "\"(?<d>/[^\"]+)\""; "g") | .d),
          (capture($at + $q + "(?<d>/[^" + $q + "]+)" + $q; "g") | .d),
          (capture($at + "(?<d>/[^\\s;&|)\"" + $q + "]+)"; "g") | .d)' <<<"$input")
      fi
      [ -n "$dir" ] || dir="$(jq -r '.cwd // "."' <<<"$input")"
      branch="$(git -C "$dir" symbolic-ref --short -q HEAD 2>/dev/null)"
      origin="$(git -C "$dir" remote get-url origin 2>/dev/null | sed -E 's#\.git$##; s#^.*[:/]([^/]+/[^/]+)$#\1#')"
      extra="$(jq -nc --arg b "$branch" --arg o "$origin" '{branch: $b, origin: $o} | with_entries(select(.value != ""))')"
      ;;
    PermissionRequest)
      root="${CLAUDE_PROJECT_DIR:-.}"
      extra="$(for f in /etc/claude-code/managed-settings.json "$root/.claude/settings.json" \
                        "$root/.claude/settings.local.json" "$HOME/.claude/settings.json"; do
                 [ -r "$f" ] && jq -c '.permissions.ask // [] | map(select(type == "string"))' "$f" 2>/dev/null
               done | jq -sc '{asks: (add // [] | unique)}')"
      ;;
    Stop | SubagentStop)
      extra="$(jq -c '{needs_input: ((.last_assistant_message // "") | test("(?m)^needs input:"))}' <<<"$input")"
      find "$log" -name '*.jsonl' -mtime +100 -delete # a retrospective looks back 14 days, a long task some weeks
      ;;
  esac
  [ -n "$extra" ] || extra='{}'
  line="$(jq -c --arg e "$event" --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson x "$extra" '
    def cut: walk(if type == "string" and length > 2000 then .[0:2000] + "…" else . end);
    {at: $t, event: $e, session: .session_id, turn: .prompt_id, agent: .agent_id, tool: .tool_name,
     id: .tool_use_id, input: (.tool_input | cut), prompt: (.prompt | cut), reason: (.reason | cut)}
    + $x | with_entries(select(.value != null))' <<<"$input")"
  [ -n "$line" ] || exit 0
  mkdir -p "$log"
  target="$log/$(date -u +%Y-%m).jsonl"
  if command -v flock >/dev/null; then
    { flock -x 9 && printf '%s\n' "$line" >&9; } 9>>"$target"
  else
    printf '%s\n' "$line" >>"$target"
  fi
} >/dev/null 2>&1
exit 0
