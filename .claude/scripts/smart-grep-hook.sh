#!/usr/bin/env bash
# .claude/scripts/smart-grep-hook.sh
# Graph-first grep interceptor for Claude Code PreToolUse(Bash).
# Decision ladder — see docs/agent/knowledge-graph.md.
set -uo pipefail

INPUT=$(cat)
CMD=$(printf '%s' "$INPUT" | python3 -c \
  "import json,sys; d=json.load(sys.stdin); print(d.get('tool_input',d).get('command',''))" \
  2>/dev/null || echo "")

# ── Tier 1: not a search command → silent pass ───────────────────────────────
case "$CMD" in
  *grep*|*" rg "*|*"   rg "*|*ripgrep*|*" fd "*|*" ack "*|*" ag "*) ;;
  *find\ *) ;;
  *) exit 0 ;;
esac

# ── Tier 2: explicit override → silent pass ──────────────────────────────────
case "$CMD" in
  *--graph-tried*|*"# graph-checked"*|*"GRAPH_TRIED=1"*) exit 0 ;;
esac

# ── Tier 3: non-code target → silent pass ────────────────────────────────────
case "$CMD" in
  *.md*|*.json*|*.yml*|*.yaml*|*.log*|*.jsonl*|*.txt*|*.csv*|\
  *node_modules*|*"/.git/"*|*/dist/*|*/build/*|*/.gradle/*|*/.cxx-build/*|*/__pycache__/*) exit 0 ;;
esac

json_esc() { python3 -c "import json,sys; print(json.dumps(sys.stdin.read())[1:-1])"; }

# ── CRG SQLite FTS5 query (sub-ms) ───────────────────────────────────────────
query_crg() {
  local db="$1" pat="$2"
  python3 - "$pat" "$db" <<'PYEOF' 2>/dev/null
import sqlite3, sys, os
pat, db = sys.argv[1], sys.argv[2]
if not os.path.exists(db): sys.exit(0)
try:
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=3)
    rows = c.execute(
        "SELECT n.kind, n.name, n.file_path, n.line_start "
        "FROM nodes_fts f JOIN nodes n ON n.id=f.rowid WHERE nodes_fts MATCH ? LIMIT 5",
        (pat,)).fetchall()
    if not rows:
        rows = c.execute(
            "SELECT kind, name, file_path, line_start FROM nodes WHERE name LIKE ? LIMIT 5",
            (f'%{pat}%',)).fetchall()
    c.close()
    for kind, name, path, line in rows:
        print(f'[crg] {kind}  {name}  →  {path}:{line}')
except Exception:
    pass
PYEOF
}

# ── graphify JSON search (~5ms for 4k nodes) ─────────────────────────────────
query_graphify() {
  local json="$1" pat="$2"
  python3 - "$pat" "$json" <<'PYEOF' 2>/dev/null
import json, sys, os
pat_low, gfile = sys.argv[1].lower(), sys.argv[2]
if not os.path.exists(gfile): sys.exit(0)
try:
    g = json.load(open(gfile))
    out = []
    for n in g.get('nodes', []):
        label = n.get('label', '')
        if pat_low in label.lower() or pat_low in n.get('id', '').lower():
            src = n.get('source_file', '')
            loc = n.get('source_location', '') or n.get('line', '')
            kind = n.get('file_type', 'node')
            comm = n.get('community', '')
            out.append(f'[graphify] {kind}  {label}  →  {src}:{loc}  (community {comm})')
    for r in out[:5]:
        print(r)
except Exception:
    pass
PYEOF
}

query_at_root() {
  local root="$1" pat="$2" out=""
  local crg_db="${root}/.code-review-graph/graph.db"
  local gfy_json="${root}/graphify-out/graph.json"
  [ -f "$crg_db" ]   && out+=$(query_crg "$crg_db" "$pat")$'\n'
  [ -f "$gfy_json" ] && out+=$(query_graphify "$gfy_json" "$pat")$'\n'
  printf '%s' "$out" | sed '/^[[:space:]]*$/d'
}

find_graph_root() {
  local d="$1"
  [ ! -d "$d" ] && d=$(dirname "$d")
  while [ "$d" != "/" ] && [ "$d" != "$HOME" ]; do
    { [ -f "$d/.code-review-graph/graph.db" ] || [ -f "$d/graphify-out/graph.json" ]; } \
      && echo "$d" && return
    d=$(dirname "$d")
  done
}

# ── Extract search pattern (first non-flag positional that isn't a path) ─────
PATTERN=$(printf '%s' "$CMD" | python3 -c "
import sys, shlex
cmd = sys.stdin.read().strip()
try: parts = shlex.split(cmd)
except Exception: parts = cmd.split()
bases = {'grep','rg','ripgrep','egrep','fgrep','ag','ack','fd'}
idx = next((i for i,p in enumerate(parts) if p.rsplit('/',1)[-1] in bases), -1)
if idx < 0: sys.exit(0)
for p in parts[idx+1:]:
    if not p.startswith('-') and '/' not in p and len(p) > 2:
        print(p[:60]); break
" 2>/dev/null || echo "")

# ── Tier 4: cross-repo absolute path → query that repo's graph if any ────────
TARGET=$(printf '%s' "$CMD" | python3 -c "
import sys, shlex, os
try: parts = shlex.split(sys.stdin.read())
except Exception: parts = sys.stdin.read().split()
cwd = os.getcwd()
for p in parts:
    if p.startswith('/') and not p.startswith('-') and not p.startswith(cwd):
        print(p); break
" 2>/dev/null || echo "")

if [ -n "$TARGET" ]; then
  ROOT=$(find_graph_root "$TARGET")
  if [ -n "$ROOT" ] && [ -n "$PATTERN" ]; then
    RESULT=$(query_at_root "$ROOT" "$PATTERN")
    if [ -n "$RESULT" ]; then
      MSG="Cross-repo graph hit (${ROOT##*/}) for '${PATTERN}':\n${RESULT}\n\nGrep proceeding — result shown as context."
      printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"%s"}}' \
        "$(printf '%s' "$MSG" | json_esc)"
    fi
  fi
  exit 0
fi

# ── Local graph detection ────────────────────────────────────────────────────
HAVE_CRG=0; HAVE_GFY=0
[ -f .code-review-graph/graph.db ]   && HAVE_CRG=1
[ -f graphify-out/graph.json ]       && HAVE_GFY=1

# ── Tier 5: no local graph → silent pass ─────────────────────────────────────
if [ "$HAVE_CRG" = "0" ] && [ "$HAVE_GFY" = "0" ]; then
  exit 0
fi

# ── Tier 6: local graph → session-aware gating ───────────────────────────────
KEY=$(printf '%s' "${PWD}" | md5 2>/dev/null || printf '%s' "${PWD}" | md5sum 2>/dev/null | cut -c1-8)
DIR="${HOME}/.cache/claude-graph-hook"
mkdir -p "$DIR"
SLOT="${DIR}/first-grep-${KEY}-$(date +%Y%m%d_%H)"

RESULT=""
if [ -n "$PATTERN" ] && [ "${#PATTERN}" -gt 2 ]; then
  RESULT=$(query_at_root "." "$PATTERN")
fi

TOOL_HINT=""
[ "$HAVE_CRG" = "1" ] && TOOL_HINT="semantic_search_nodes_tool(query='${PATTERN}')"
[ "$HAVE_GFY" = "1" ] && {
  GFY_HINT="graphify query '${PATTERN}' --graph graphify-out/graph.json"
  TOOL_HINT="${TOOL_HINT:+${TOOL_HINT} or }${GFY_HINT}"
}

if [ ! -f "$SLOT" ]; then
  touch "$SLOT"
  if [ -n "$RESULT" ]; then
    MSG="Graph pre-answer for '${PATTERN}':\n${RESULT}\n\nIf enough — skip the grep. Running this time (one-shot)."
  else
    MSG="No graph hit for '${PATTERN}' — grep proceeding (one-shot). Next time try: ${TOOL_HINT}. Append # --graph-tried to bypass permanently."
  fi
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"%s"}}' \
    "$(printf '%s' "$MSG" | json_esc)"
  exit 0
fi

# Subsequent grep this hour: answering deny when graph hits, else silent
if [ -n "$RESULT" ]; then
  MSG="Graph has this — no retry needed:\n\n${RESULT}\n\nUse: ${TOOL_HINT}. Append # --graph-tried to override."
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"block","permissionDecisionReason":"%s"}}' \
    "$(printf '%s' "$MSG" | json_esc)"
  exit 0
fi

printf '[graph-hook] no result for "%s"\n' "$PATTERN" >> "${DIR}/bypass.log"
exit 0
