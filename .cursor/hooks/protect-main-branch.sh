#!/usr/bin/env bash
# Project hook (A): keep the agent off the protected `main` branch.
#
# `main` is protected, auto-deploys to Render on merge, and per
# .cursor/rules/git-workflow.mdc must never receive direct commits or pushes —
# all work lands via a change branch + PR. This hook denies the agent's shell
# calls that would:
#   1. `git commit` while the current branch is `main`
#   2. `git push` that targets `main` via an explicit refspec (`main`, `+main`,
#      `HEAD:main`, `refs/heads/main`, ...), from any branch
#   3. ANY `git push` while the current branch is `main` (intentionally broad:
#      per the workflow you never push from a `main` checkout, so a blanket
#      block here is the simplest safe stance, even for a non-main refspec)
#
# It deliberately does NOT touch: feature-branch commits/pushes,
# `git merge --ff-only origin/main` (local main sync), or `gh pr merge`
# (the sanctioned merge path) — none of those are a `git commit`/`git push`
# targeting main, so they fall through to allow.
#
# Event: beforeShellExecution (matcher "git" in hooks.json, so it only runs on
# git commands). Input on stdin: { "command", "cwd", "sandbox" }.
# Output: {"permission":"allow"|"deny", ...} on stdout, exit 0.
#
# NOTE: Cursor hooks gate AGENT shell calls only, not your own terminal. This is
# not a substitute for server-side branch protection.

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

# Cheap pre-filter: nothing to do if there's no git invocation at all.
case "$cmd" in
  *git*) ;;
  *) allow ;;
esac

# Best-effort current branch in the command's working directory. If this can't
# be resolved (not a repo, detached HEAD, git missing) we leave it empty and
# fall back to the explicit-refspec checks only — never falsely block.
branch="$(git -C "${cwd:-.}" rev-parse --abbrev-ref HEAD 2>/dev/null)"

# Return the git subcommand of a single command segment (or empty). Skips git's
# global options, including the value-taking ones (-C <path>, -c <kv>, etc.).
git_subcommand() {
  local seg="$1"
  local -a toks
  read -ra toks <<<"$seg"
  local i=0 n=${#toks[@]}
  while [ $i -lt $n ]; do
    if [ "${toks[$i]}" = "git" ]; then
      i=$((i + 1))
      while [ $i -lt $n ]; do
        case "${toks[$i]}" in
          -C|-c|--git-dir|--work-tree|--namespace|--exec-path|--super-prefix)
            i=$((i + 2)); continue ;;
          -*) i=$((i + 1)); continue ;;
          *) printf '%s\n' "${toks[$i]}"; return ;;
        esac
      done
      return
    fi
    i=$((i + 1))
  done
}

# Does a push segment name `main` as its DESTINATION ref? For each token we take
# the destination = the part after the last `:` (the dst in `src:dst`), or the
# whole token when there's no colon, after dropping a leading `+` (force-push
# shorthand). A token counts only if that destination is exactly `main` or
# `refs/heads/main`. So we catch `main`, `+main`, `HEAD:main`, `:main` (delete),
# `refs/heads/main`, `feature/main:main`; and we DON'T flag a source-only ref
# whose last segment merely happens to be `main` (e.g. `feature/main`) or
# look-alikes like `feature-main`.
push_targets_main() {
  local seg="$1"
  local -a toks
  read -ra toks <<<"$seg"
  local t dst
  for t in "${toks[@]}"; do
    t="${t#+}"        # drop force-push shorthand prefix
    dst="${t##*:}"    # dst of src:dst, or the whole token when no colon
    case "$dst" in
      main|refs/heads/main) return 0 ;;
    esac
  done
  return 1
}

COMMIT_USER="Blocked: you're on 'main'. Never commit directly to main — it's protected and auto-deploys. Create a change branch (<type>/<issue-id>-<slug>) and commit there."
COMMIT_AGENT="Denied by project hook (protect-main-branch): a 'git commit' on the protected 'main' branch is forbidden by .cursor/rules/git-workflow.mdc. Cut a change branch first (git switch -c <type>/<issue-id>-<slug>) and commit there."
PUSH_USER="Blocked: pushing to 'main' is forbidden — main is protected and auto-deploys to Render. Push your change branch and open a PR instead."
PUSH_AGENT="Denied by project hook (protect-main-branch): pushing to 'main' is forbidden by .cursor/rules/git-workflow.mdc (never push/force-push to main). Push the change branch and open a PR with 'gh pr create'; merges to main happen via 'gh pr merge' after green CI."

# Split the command into segments on &&, ||, ;, | and inspect each git segment.
segments="$(printf '%s' "$cmd" | sed -E 's/(\&\&|\|\||[;|])/\n/g')"
while IFS= read -r seg; do
  case "$seg" in *git*) ;; *) continue ;; esac
  sub="$(git_subcommand "$seg")"
  case "$sub" in
    commit)
      [ "$branch" = "main" ] && deny "$COMMIT_USER" "$COMMIT_AGENT"
      ;;
    push)
      if push_targets_main "$seg"; then
        deny "$PUSH_USER" "$PUSH_AGENT"
      fi
      if [ "$branch" = "main" ]; then
        deny "$PUSH_USER" "$PUSH_AGENT"
      fi
      ;;
  esac
done <<<"$segments"

allow
