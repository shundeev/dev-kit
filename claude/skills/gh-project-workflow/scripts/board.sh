# shellcheck shell=bash
# Sourced by the flow scripts: sets a single-select field of an issue's item on a project board.
#
# gh's by-URL mode (`gh project item-edit N --url <issue> --field F --value V`) finds the item
# through the issue, and an issue in an organization does not list a project owned by a user.
# After the move to the organization `shundeev` (#22) every such edit failed with "is not an
# item in project N". This resolves the item through the project instead: `item-add` returns
# the existing item when the issue is already on the board, and adds it otherwise.

board_project_id=""
board_fields_json=""

board_set_field() { # board_set_field <project-number> <owner> <issue-url> <field> <option>
  local number="$1" owner="$2" url="$3" field="$4" option="$5" item ids field_id option_id
  item="$(gh project item-add "$number" --owner "$owner" --url "$url" --format json --jq '.id')" || return 1
  [[ -n "$board_project_id" ]] \
    || board_project_id="$(gh project view "$number" --owner "$owner" --format json --jq '.id')" || return 1
  [[ -n "$board_fields_json" ]] \
    || board_fields_json="$(gh project field-list "$number" --owner "$owner" --format json --limit 100)" || return 1
  ids="$(jq -r --arg f "$field" --arg o "$option" \
    '.fields[] | select(.name == $f) | [.id, ((.options // [])[] | select(.name == $o) | .id)] | @tsv' \
    <<<"$board_fields_json")"
  IFS=$'\t' read -r field_id option_id <<<"$ids"
  if [[ -z "$field_id" || -z "$option_id" ]]; then
    echo "project $number has no field '$field' with an option '$option'" >&2
    return 1
  fi
  gh project item-edit --id "$item" --project-id "$board_project_id" \
    --field-id "$field_id" --single-select-option-id "$option_id" >/dev/null
}
