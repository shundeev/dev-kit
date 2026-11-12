# shellcheck shell=bash
# Sourced by the flow scripts: sets a single-select field of an issue's item on a project board.
# Run on its own, it sets one field, as the flow scripts' errors suggest (#41):
#   bash board.sh set <project-number> <owner> <issue-url> <field> <option>
#
# gh's by-URL mode (`gh project item-edit N --url <issue> --field F --value V`) finds the item
# through the issue, and an issue in an organization does not list a project owned by a user.
# After the move to the organization `shundeev` (#22) every such edit failed with "is not an
# item in project N". This resolves the item through the project instead: `item-add` returns
# the existing item when the issue is already on the board, and adds it otherwise.

board_project_id=""
board_fields_json=""

# board_hint <GitHub's message>: the likely cause and its fix, or nothing (#41).
board_hint() {
  case "$1" in
    *INSUFFICIENT_SCOPES* | *"required scopes"* | *"missing required scope"*)
      echo "the gh login in use lacks the project scope: in your own terminal, GH_CONFIG_DIR=${GH_CONFIG_DIR:-~/.config/gh} gh auth refresh -h github.com -s project" ;;
    *"Could not resolve to a ProjectV2"* | *"Could not resolve to a User"* | *NOT_FOUND*)
      echo "no access to the board: check that it exists and that this gh account can write to it (the board's Settings > Manage access)" ;;
    *"HTTP 5"* | *"Bad Gateway"* | *"Service Unavailable"* | *"timed out"* | *timeout* | *"connection reset"* | *"Something went wrong"*)
      echo "GitHub had an error: run the command again" ;;
  esac
}

# board_reason <GitHub's message>: the message and, when known, its cause and fix.
board_reason() {
  local hint
  hint="$(board_hint "$1")"
  printf '%s%s' "$1" "${hint:+; $hint}"
}

# board_gh <gh args>: gh, run once more after a passing GitHub error (#41). Its message goes to stderr;
# stdout stays only gh's answer, so a warning cannot end up in an id.
board_gh() {
  local out err rc
  err="$(mktemp)"
  out="$(gh "$@" 2>"$err")"; rc=$?
  if [[ "$rc" -ne 0 && "$(board_hint "$(cat "$err")")" == "GitHub had an error"* ]]; then
    out="$(gh "$@" 2>"$err")"; rc=$?
  fi
  if [[ "$rc" -eq 0 ]]; then printf '%s\n' "$out"; else cat "$err" >&2; fi
  rm -f "$err"
  return "$rc"
}

board_set_field() { # board_set_field <project-number> <owner> <issue-url> <field> <option>
  local number="$1" owner="$2" url="$3" field="$4" option="$5" item ids field_id option_id
  # item-add returns the item an issue already has on the board, and adds a missing one
  item="$(board_gh project item-add "$number" --owner "$owner" --url "$url" --format json --jq '.id')" || return 1
  [[ -n "$board_project_id" ]] \
    || board_project_id="$(board_gh project view "$number" --owner "$owner" --format json --jq '.id')" || return 1
  [[ -n "$board_fields_json" ]] \
    || board_fields_json="$(board_gh project field-list "$number" --owner "$owner" --format json --limit 100)" || return 1
  ids="$(jq -r --arg f "$field" --arg o "$option" \
    '.fields[] | select(.name == $f) | [.id, ((.options // [])[] | select(.name == $o) | .id)] | @tsv' \
    <<<"$board_fields_json")"
  IFS=$'\t' read -r field_id option_id <<<"$ids"
  if [[ -z "$field_id" ]]; then
    echo "board $number has no field '$field'; its fields: $(jq -r '[.fields[].name] | join(", ")' <<<"$board_fields_json"). Use one of them, or add the field on the board" >&2
    return 1
  elif [[ -z "$option_id" ]]; then
    echo "the field '$field' of board $number has no option '$option'; its options: $(jq -r --arg f "$field" '[.fields[] | select(.name == $f) | (.options // [])[].name] | join(", ")' <<<"$board_fields_json"). Use one of them, or add the option on the board" >&2
    return 1
  fi
  board_gh project item-edit --id "$item" --project-id "$board_project_id" \
    --field-id "$field_id" --single-select-option-id "$option_id" >/dev/null
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  [[ "${1:-}" == set && $# -eq 6 ]] \
    || { echo "Usage: bash board.sh set <project-number> <owner> <issue-url> <field> <option>" >&2; exit 2; }
  shift
  board_set_field "$@" || exit 1
  echo "$4: $5"
fi
