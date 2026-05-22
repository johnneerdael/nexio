#!/usr/bin/env bash
# Smoke tests for smart-grep-hook.sh.
# Each test pipes a Claude Code PreToolUse JSON payload and asserts on output.
set -u

HOOK=".claude/scripts/smart-grep-hook.sh"
PASS=0; FAIL=0

assert_silent() {
  local desc="$1" payload="$2"
  local out
  out=$(printf '%s' "$payload" | bash "$HOOK" 2>&1)
  if [ -z "$out" ]; then
    echo "  PASS  $desc"; PASS=$((PASS+1))
  else
    echo "  FAIL  $desc — expected empty, got: $out"; FAIL=$((FAIL+1))
  fi
}

assert_contains() {
  local desc="$1" payload="$2" needle="$3"
  local out
  out=$(printf '%s' "$payload" | bash "$HOOK" 2>&1)
  if printf '%s' "$out" | grep -q -- "$needle"; then
    echo "  PASS  $desc"; PASS=$((PASS+1))
  else
    echo "  FAIL  $desc — expected to contain '$needle', got: $out"; FAIL=$((FAIL+1))
  fi
}

# Clean session state before tests
rm -rf "${HOME}/.cache/claude-graph-hook"

echo "Tier 1: non-search commands pass silently"
assert_silent "ls is not a search" '{"tool_input":{"command":"ls -la"}}'
assert_silent "echo is not a search" '{"tool_input":{"command":"echo hello"}}'

echo "Tier 2: --graph-tried override passes silently"
assert_silent "grep with --graph-tried comment" '{"tool_input":{"command":"grep foo src/ # --graph-tried"}}'

echo "Tier 3: non-code targets pass silently"
assert_silent "grep .md" '{"tool_input":{"command":"grep foo README.md"}}'
assert_silent "grep .json" '{"tool_input":{"command":"grep foo package.json"}}'
assert_silent "grep .log" '{"tool_input":{"command":"grep ERROR app.log"}}'

echo "Tier 6a/6b: first grep this hour — allows with hint"
# Clean session state to force first-grep-of-hour branch
rm -rf "${HOME}/.cache/claude-graph-hook"
assert_contains "first grep emits PreToolUse hookSpecificOutput" \
  '{"tool_input":{"command":"grep HomeViewModel app/src/main/java/"}}' \
  "PreToolUse"

echo "Tier 6c: subsequent grep with graph hit — answering deny"
assert_contains "second grep with graph hit denies with permissionDecision" \
  '{"tool_input":{"command":"grep HomeViewModel app/src/main/java/"}}' \
  "permissionDecision"

echo "Tier 6d: subsequent grep with graph MISS — pass silently"
assert_silent "subsequent grep with graph miss" \
  '{"tool_input":{"command":"grep zzzzzznosuchsymbolzzzzzz app/"}}'

echo
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
