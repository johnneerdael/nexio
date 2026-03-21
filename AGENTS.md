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

# Agent Instructions

## Kodi Audio Port

- For any work in the custom Kodi audio sink / IEC packer / passthrough path, ground analysis against the actual Kodi source in [`xbmc/xbmc/cores/AudioEngine`](xbmc/xbmc/cores/AudioEngine).
- Any changes to the media/libraries/cpp_audiosink files are only accepted if absolutely required for the jni_bridge, other code changes are not allowed
- When changes are made to files in media/libraries/cpp_audiosink the GPL license header needs to be updated with our changes.
