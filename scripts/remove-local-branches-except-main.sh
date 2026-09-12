#!/usr/bin/env bash

set -euo pipefail

if [[ "$(git rev-parse --show-toplevel)" != "$PWD" ]]; then
  echo "Run this script from the repository root." >&2
  exit 1
fi

if [[ "$(git branch --show-current)" != "main" ]]; then
  echo "Switch to main before running this script." >&2
  exit 1
fi

failed=()

while IFS= read -r branch; do
  [[ -z "$branch" ]] && continue
  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    echo "Would delete: $branch"
  else
    if ! git branch -D -- "$branch"; then
      echo "Skipped: $branch" >&2
      failed+=("$branch")
    fi
  fi
done < <(git for-each-ref --format='%(refname:short)' refs/heads/ | while IFS= read -r branch; do
  [[ "$branch" != "main" ]] && printf '%s\n' "$branch"
done)

if ((${#failed[@]} > 0)); then
  echo "Could not delete ${#failed[@]} branch(es):" >&2
  printf '  %s\n' "${failed[@]}" >&2
  exit 1
fi
