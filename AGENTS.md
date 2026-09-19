# Agent Directives & Constraints

## Scope
- Do NOT run destructive commands: `git reset --hard`, `git push --force`, `git clean -fd`, `git checkout -- .`
- Do NOT commit directly to `main` or `master`. Always start new work with a checkout of a new branch in a worktree: `git worktree add -b <branchname> $(pwd)/../$(basename $(pwd))-worktrees/<branchname>`, where `<branchname>` is `opencode/<type>/<short-desc>` (type is one of `feature`, `fix`, `chore`), e.g. `opencode/fix/login-crash`.
  - When the work is complete, push the branch and create a PR with `gh` targeting the base branch: `gh pr create --base main`.
  - After the PR is merged, remove the worktree: `git worktree remove $(pwd)/../$(basename $(pwd))-worktrees/<branchname>`.
- Never modify files inside `.git/`, or any file ending in `.env*`, `*.jks`, or `.pem`.
- Edits can be made to files inside `.github/` but do NOT commit them.

## Verification
- Before completing a task, run tests locally via `scripts/build-debug.sh`
- Stage only the specific files modified for the requested feature.

