#!/bin/sh
# Sensitive-data gate. Blocks secrets and personal data before they enter history.
#   check-sensitive.sh staged   -> pre-commit: scan file names, sizes and content of the index
#   check-sensitive.sh history  -> pre-push: rescan every tracked file and the FULL commit
#                                  history (catches anything smuggled in with --no-verify)
# Deliberate exception? ALLOW_SENSITIVE=1 git commit ... (leaves a conscious trace, unlike --no-verify).
set -e
mode="${1:-staged}"
hookdir="$(dirname "$0")"
fail=0

if [ "${ALLOW_SENSITIVE:-}" = "1" ]; then
  echo "[sensitive] ALLOW_SENSITIVE=1 set — skipping sensitive-data gate for this run"
  exit 0
fi

# File names that must never be committed in this repo: personal Takeout exports,
# databases, mail archives, env files, key material, certificates.
DENY_NAMES='(^|/)[Tt]akeout([^a-zA-Z][^/]*)?(/|$)|\.mbox$|\.db(-shm|-wal)?$|\.sqlite3?$|(^|/)\.env(\..+)?$|\.pem$|\.p12$|\.pfx$|\.key$|\.keystore$|\.jks$|(^|/)id_(rsa|dsa|ecdsa|ed25519)[^/]*$|(^|/)credentials(\..+)?$'

if [ "$mode" = "staged" ]; then
  files=$(git diff --cached --name-only --diff-filter=ACMR)
else
  files=$(git ls-files)
fi

# 1) forbidden file names
bad=$(printf '%s\n' "$files" | grep -E "$DENY_NAMES" || true)
if [ -n "$bad" ]; then
  echo "[sensitive] BLOCKED — file names that must never be committed:"
  printf '%s\n' "$bad" | sed 's/^/  - /'
  fail=1
fi

# 2) oversized files (data exports, dumps; screenshots are gitignored anyway)
if [ "$mode" = "staged" ]; then
  big=$(printf '%s\n' "$files" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    size=$(git cat-file -s ":$f" 2>/dev/null || echo 0)
    [ "$size" -gt 5242880 ] && printf '%s (%s bytes)\n' "$f" "$size"
  done || true)
  if [ -n "$big" ]; then
    echo "[sensitive] BLOCKED — files larger than 5 MB; data exports don't belong in git:"
    printf '%s\n' "$big" | sed 's/^/  - /'
    fail=1
  fi
fi

# 3) personal-data markers (owner's e-mail, CPF, ...) in newly added lines
if [ -f "$hookdir/sensitive-patterns.txt" ] && [ "$mode" = "staged" ]; then
  patfile=$(mktemp)
  grep -Ev '^[[:space:]]*(#|$)' "$hookdir/sensitive-patterns.txt" > "$patfile" || true
  if [ -s "$patfile" ]; then
    # the patterns file itself is excluded — its lines always match themselves
    hits=$(git diff --cached -U0 -- . ':(exclude).githooks/sensitive-patterns.txt' \
      | grep '^+' | grep -v '^+++' | grep -E -i -f "$patfile" || true)
    if [ -n "$hits" ]; then
      echo "[sensitive] BLOCKED — staged lines match personal-data markers (.githooks/sensitive-patterns.txt):"
      printf '%s\n' "$hits" | head -5 | sed 's/^/  /'
      fail=1
    fi
  fi
  rm -f "$patfile"
fi

# 4) secret scan (API keys, tokens, private keys, high-entropy strings)
if command -v gitleaks >/dev/null 2>&1; then
  if [ "$mode" = "staged" ]; then
    set -- git --pre-commit --staged
  else
    set -- git
  fi
  status=0
  gitleaks "$@" --redact --exit-code 9 . || status=$?
  if [ "$status" -eq 9 ]; then
    echo "[sensitive] BLOCKED — gitleaks found secrets ($mode scan)"
    fail=1
  elif [ "$status" -ne 0 ]; then
    echo "[sensitive] BLOCKED — gitleaks failed to run (exit $status); gate fails closed"
    fail=1
  fi
else
  echo "[sensitive] BLOCKED — gitleaks is not installed and the gate fails closed."
  echo "  Install: https://github.com/gitleaks/gitleaks/releases (put the binary on your PATH)"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "[sensitive] Commit/push rejected. If this is a deliberate exception, rerun with ALLOW_SENSITIVE=1."
  exit 1
fi
echo "[sensitive] OK"
