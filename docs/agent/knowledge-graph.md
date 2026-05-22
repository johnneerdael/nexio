# Knowledge Graph Reference

This repo has two graph tools installed and kept fresh automatically:
- **code-review-graph (CRG)** — SQLite-backed AST graph at `.code-review-graph/graph.db`, exposed via MCP server `code-review-graph`. Sub-second incremental updates after each AI turn.
- **graphify** — JSON graph at `graphify-out/graph.json` + Markdown community report at `graphify-out/GRAPH_REPORT.md`. Rebuilt on every git commit / branch switch.

## When you MUST consult the graph first

Use one of the graph tools listed below **before** running `grep`, `rg`, `find`, `Glob`, or `Read` on code files when you are:
- Answering architecture, cross-module, or "how does X work" questions
- Locating a symbol, function, class, or file by name
- Tracing who calls / imports / is called by something
- Estimating blast radius before a refactor
- Reviewing code changes for risk and test gaps

If the graph misses, fall back in this order: **CRG semantic → `graphify query` → grep with `# --graph-tried` shell comment**.

## Query → tool map (CRG MCP)

| Question | Tool | Notes |
|---|---|---|
| Where is X defined? | `semantic_search_nodes_tool(query="X")` | ~100 tok |
| Who calls X? | `query_graph_tool(pattern="callers_of", target="X")` | ~80 tok |
| What imports file F? | `query_graph_tool(pattern="importers", target="F")` | ~125 tok |
| Pre-refactor blast | `get_impact_radius_tool(changed_files=[…])` | full 2-hop |
| Code review on diff | `get_review_context_tool(changed_files=[…])` | risk + test gaps |
| Concept walk | `traverse_graph_tool(query="…", depth=3)` | keep depth ≤ 3 |
| Architecture clusters | `list_communities_tool()` → `get_community_tool(community_id=N)` | NEVER `get_architecture_overview_tool` (~33k tok) |
| Sanity check | `list_graph_stats_tool()` | node/edge counts |

The MCP server is stripped to this 8-tool allow-list via `CRG_TOOLS` in `.mcp.json` — invoking any other CRG tool will fail because it's not registered.

## Query → tool map (graphify CLI)

```bash
graphify query "auth flow" --graph graphify-out/graph.json --budget 1500
graphify path "ComponentA" "ServiceB" --graph graphify-out/graph.json
graphify explain "MyService" --graph graphify-out/graph.json
```

`graphify query` does BFS depth=2 (~1500 tok output) — heavy. Use it only when CRG misses or for unfamiliar-codebase orientation.

## Bypass for grep

The PreToolUse hook `bash .claude/scripts/smart-grep-hook.sh` denies subsequent greps that the graph can answer. Override with `# --graph-tried` as a **shell comment** at the end of the command:

```bash
grep -rn "TODO" app/src/ # --graph-tried
```

`# --graph-tried` (comment) works everywhere; `--graph-tried` as a flag does not — ugrep on macOS rejects unknown flags.

## Lifecycle (auto-update)

| Event | What runs | Where |
|---|---|---|
| AI finishes a turn | `code-review-graph update --skip-flows` + `embed` | `.claude/settings.local.json` Stop hook (PID-guarded) |
| `git commit` | both tools rebuild in background (`_resources_ok` guarded) | `.git/hooks/post-commit` |
| `git checkout <branch>` | both tools rebuild (smart: ≤5 files = incremental, >5 = full) | `.git/hooks/post-checkout` |

graphify is **never** in a Claude hook — its rebuild takes ~10s on this monorepo, which would pile up across AI turns.

## Reproducing this setup on a fresh checkout

Files under version control:
- `.graphifyignore`, `.code-review-graphignore`
- `.mcp.example.json` — copy to `.mcp.json` locally
- `.claude/settings.example.json` — copy to `.claude/settings.local.json` locally
- `.claude/scripts/smart-grep-hook.sh` (executable)
- `.claude/scripts/test-smart-grep-hook.sh` (executable)
- `docs/agent/knowledge-graph.md` (this file)

Files **not** under version control — replay on a fresh checkout:
- `.git/hooks/post-commit` — append the graph-rebuild block; see the implementation plan at `docs/superpowers/plans/2026-05-22-graphs-best-practices.md` Task 7 Step 1
- `.git/hooks/post-checkout` — append the smart-rebuild block; see Task 7 Step 2

After replaying hooks: `chmod +x .git/hooks/post-commit .git/hooks/post-checkout`.

## Verify the graphs are fresh

```bash
code-review-graph status
head -15 graphify-out/GRAPH_REPORT.md
git log -1 --format=%ci                            # last commit timestamp
stat -f %Sm .code-review-graph/graph.db            # macOS — should be ≥ commit timestamp
stat -f %Sm graphify-out/graph.json
```

If `graph.db` mtime lags behind the last commit, the post-commit hook didn't fire. Check `~/.cache/code-review-graph-update.log` and `~/.cache/graphify-rebuild.log` for skipped-due-to-resources entries.
