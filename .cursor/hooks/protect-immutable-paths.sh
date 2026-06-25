#!/usr/bin/env bash
# Project hook (B): protect immutable paths from agent edits.
#
# Denies file-mutating tool calls that target:
#   1. anything under context/archive/   -> archived changes are immutable
#      (see .cursor/skills/10x-archive; /10x-implement refuses archived plans)
#   2. an already-committed Flyway migration src/main/resources/db/migration/V*.sql
#      -> migrations are forward-only; never edit an applied one (AGENTS.md
#         "Persistence & Migrations" -- Neon free has no rollback). A new,
#         not-yet-committed migration stays editable so authoring V{n+1}__ works.
#
# Event: preToolUse (fires for ALL tools -- no matcher, so StrReplace/Write/Delete
# are all covered). Non-mutating tools (Read/Grep/Glob/Shell/...) pass straight
# through, so reading archived files is never blocked.
#
# Output contract (Cursor preToolUse): print {"permission":"allow"|"deny",...}
# on stdout and exit 0. failClosed:true in hooks.json blocks if this crashes.

input="$(cat)"

allow() { printf '{"permission":"allow"}\n'; exit 0; }

deny() {
  # $1 = user_message (shown to user), $2 = agent_message (fed to the agent)
  jq -n --arg u "$1" --arg a "$2" \
    '{permission:"deny", user_message:$u, agent_message:$a}'
  exit 0
}

tool_name="$(printf '%s' "$input" | jq -r '.tool_name // empty' 2>/dev/null)"

# Only inspect file-mutating tools; anything else is allowed immediately.
case "$tool_name" in
  Write|Edit|StrReplace|MultiEdit|MultiStrReplace|SearchReplace|Delete|EditNotebook) ;;
  *) allow ;;
esac

# The path field inside tool_input is not formally documented per tool, so try
# the known/likely keys defensively (absolute or workspace-relative both work
# for the substring checks below).
path="$(printf '%s' "$input" | jq -r '
  .tool_input.file_path
  // .tool_input.path
  // .tool_input.target_file
  // .tool_input.target_notebook
  // empty' 2>/dev/null)"

[ -z "$path" ] && allow

# 1) context/archive/** is immutable.
case "$path" in
  *context/archive/*)
    deny \
"Blocked: context/archive/** is immutable. Archived changes are a historical record -- open a new change with /10x-new instead of editing an archive." \
"Edit denied by project hook (protect-immutable-paths): '$path' is under context/archive/, which is immutable. Do not modify archived changes; create a new change via /10x-new."
    ;;
esac

# 2) Already-committed Flyway migrations are forward-only.
case "$path" in
  */db/migration/*)
    base="$(basename -- "$path")"
    case "$base" in
      V*.sql)
        if git ls-files --error-unmatch -- "$path" >/dev/null 2>&1; then
          deny \
"Blocked: '$base' is an applied (committed) Flyway migration. Migrations are forward-only -- never edit an applied one. Add a new V{n+1}__ migration instead." \
"Edit denied by project hook (protect-immutable-paths): '$path' is a committed Flyway migration. Per AGENTS.md (Persistence & Migrations), migrations are forward-only and immutable once applied -- create a new V{n+1}__*.sql migration rather than editing this file."
        fi
        ;;
    esac
    ;;
esac

allow
