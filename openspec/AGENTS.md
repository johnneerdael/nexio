# OpenSpec Instructions

Use OpenSpec for spec-driven changes in this worktree.

## Workflow
- Check the current project context before creating or editing a change.
- Use a unique verb-led change id.
- Scaffold `proposal.md`, `tasks.md`, and one or more spec deltas under `openspec/changes/<change-id>/`.
- Use `#### Scenario:` blocks for every requirement.
- Validate with `openspec validate <change-id> --strict` before implementation handoff.

## Conventions
- Prefer focused changes that stay aligned with the existing capability boundaries.
- Modify existing specs when extending behavior; add new capability specs only when needed.
- Keep proposal and task lists explicit about rollout, compatibility, and verification.
