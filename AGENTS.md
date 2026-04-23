<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

NEXIO is an Android TV / Fire TV streaming app built with Kotlin and Jetpack Compose.

- Package: `com.nexio.tv`
- Core areas: debrid integration, Trakt sync, benchmark-driven playback
- Playback stack: forked Media3 / ExoPlayer with custom extensions

When using subagent-driven-development always continue your tasks sequentially untill every task is completed without stopping.
When a subagent is stalled, and you have not received or seen any progress terminate the stalled agent immediately and split the task in smaller slices for new subagents.
