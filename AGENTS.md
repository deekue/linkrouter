# Agent Directives & Constraints

## Scope
- Do NOT run destructive commands: `git reset --hard`, `git push --force`
- Do NOT commit directly to `main` or `master`. Always checkout a new branch: `git checkout -b opencode/<feature, fix, chore>`.
- Never modify files inside `.git/`, or any file ending in `.env*`, or '*.jks', or `.pem`.
- Edits can be made to files inside `.github/` but do NOT commit them.

## Verification
- Before completing a task, run tests locally via `scripts/build-debug.sh`
- Stage only the specific files modified for the requested feature.

