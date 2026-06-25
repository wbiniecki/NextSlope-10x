#!/usr/bin/env bash
# Project hook (C): best-effort shell-side guard for immutable paths.
#
# Companion to protect-immutable-paths.sh, which only gates file-editing TOOLS
# (Write/StrReplace/Delete/EditNotebook/...). Without this, an agent blocked
# from StrReplace-ing an immutable file could just bypass it through the Shell
# tool (sed -i, rm, mv, > redirect). This hook denies a shell command when a
# single segment BOTH:
#   (a) invokes a filesystem-mutating command (rm/mv/cp/ln/tee/sed -i/...) OR
#       redirects output (> / >>) into a target, AND
#   (b) that target is an immutable path:
#         - a committed (git-tracked) Flyway migration db/migration/V*.sql, or
#         - an already-EXISTING path under context/archive/.
#
# The "already-exists" test for context/archive is deliberate: it blocks edits
# to/deletion of archived artifacts while leaving the sanctioned /10x-archive
# `git mv <src> context/archive/<new-dest>` working (the dest doesn't exist
# yet). A not-yet-committed migration stays editable so authoring V{n+1} works.
#
# This is a HEURISTIC, not a sandbox. Indirect mutation can still slip through
# (cd into the dir then relative paths, globs, find -exec/-delete, xargs,
# heredocs, variable expansion, numbered-fd redirects like 2>). The real
# backstops remain forward-only migrations + review and the archive read-only
# convention; this just closes the lazy `sed -i`/`rm`/`>` bypass.
#
# Event: beforeShellExecution (no matcher -> runs on every shell command).
# Input on stdin: { "command", "cwd", "sandbox" }.
# Output: {"permission":"allow"|"deny", ...} on stdout, exit 0.
# failClosed:true in hooks.json blocks if this crashes.

input="$(cat)"

allow() { printf '{"permission":"allow"}\n'; exit 0; }

deny() {
  # $1 = user_message (shown to user), $2 = agent_message (fed to the agent)
  jq -n --arg u "$1" --arg a "$2" \
    '{permission:"deny", user_message:$u, agent_message:$a}'
  exit 0
}

cmd="$(printf '%s' "$input" | jq -r '.command // empty' 2>/dev/null)"
cwd="$(printf '%s' "$input" | jq -r '.cwd // empty' 2>/dev/null)"
[ -z "$cmd" ] && allow
cwd="${cwd:-.}"

# Set by is_immutable_target on a hit, for use in the deny message.
TARGET_DESC=""

# Is $1 an immutable target? Strips surrounding quotes first. Sets TARGET_DESC.
is_immutable_target() {
  local tok="$1"
  tok="${tok%\"}"; tok="${tok#\"}"
  tok="${tok%\'}"; tok="${tok#\'}"
  [ -z "$tok" ] && return 1

  # Committed Flyway migration (forward-only; new/uncommitted ones stay editable).
  case "$tok" in
    */db/migration/V*.sql|db/migration/V*.sql)
      if git -C "$cwd" ls-files --error-unmatch -- "$tok" >/dev/null 2>&1; then
        TARGET_DESC="a committed Flyway migration"
        return 0
      fi
      ;;
  esac

  # Existing path under context/archive/ (archived artifacts are read-only).
  case "$tok" in
    *context/archive/*)
      local p="$tok"
      case "$p" in /*) ;; *) p="$cwd/$p" ;; esac
      if [ -e "$p" ]; then
        TARGET_DESC="an archived path under context/archive/"
        return 0
      fi
      ;;
  esac

  return 1
}

# Print each output-redirection target (> / >>, attached or spaced) in a segment.
redirect_targets() {
  local seg="$1"
  local -a toks
  read -ra toks <<<"$seg"
  local i=0 n=${#toks[@]} t r
  while [ $i -lt $n ]; do
    t="${toks[$i]}"
    case "$t" in
      '>'|'>>')
        r="${toks[$((i + 1))]}"
        [ -n "$r" ] && printf '%s\n' "$r"
        ;;
      '>'*)
        r="${t#>}"; r="${r#>}"
        [ -n "$r" ] && printf '%s\n' "$r"
        ;;
    esac
    i=$((i + 1))
  done
}

block_for() {
  # $1 = offending token
  local tok="$1"
  deny \
"Blocked: this shell command would modify $TARGET_DESC ('$tok'), which is immutable. Add a new V{n+1}__ migration or open a new change with /10x-new instead of editing it via the shell." \
"Denied by project hook (protect-immutable-paths-shell): the command mutates $TARGET_DESC ('$tok'). This is the same immutability the file-edit hook enforces (AGENTS.md Persistence & Migrations; context/archive is read-only) -- do not bypass it through the shell. Create a new V{n+1}__*.sql migration or a new change."
}

# Inspect each &&/||/;/| segment independently to avoid cross-segment false
# positives (e.g. `rm /tmp/x && cat context/archive/y` must not be denied).
segments="$(printf '%s' "$cmd" | sed -E 's/(\&\&|\|\||[;|])/\n/g')"
while IFS= read -r seg; do
  [ -z "$seg" ] && continue

  mut=0
  if printf '%s' "$seg" | grep -Eq '(^|[[:space:]])(rm|rmdir|mv|cp|ln|install|dd|truncate|shred|chmod|chown|chgrp|tee)([[:space:]]|$)'; then
    mut=1
  fi
  if printf '%s' "$seg" | grep -Eq '(^|[[:space:]])(sed|perl)[[:space:]].*(-i|--in-place)'; then
    mut=1
  fi

  if [ "$mut" -eq 1 ]; then
    read -ra toks <<<"$seg"
    for t in "${toks[@]}"; do
      if is_immutable_target "$t"; then
        block_for "$t"
      fi
    done
  fi

  while IFS= read -r rt; do
    [ -z "$rt" ] && continue
    if is_immutable_target "$rt"; then
      block_for "$rt"
    fi
  done <<<"$(redirect_targets "$seg")"
done <<<"$segments"

allow
