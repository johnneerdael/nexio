# Deployment

This page covers how the documentation site is built, previewed, and published. It is intended for maintainers and contributors who need a reliable release path for docs changes.

## What is being deployed

The documentation portal is a VitePress site in `docs-site/`.

Current local scripts:

- `npm run docs:dev` for local authoring
- `npm run docs:build` for a production build
- `npm run docs:preview` for previewing the built output

The published site uses the base path `/nexio/`, which matters when checking links and assets.

## Local validation before publish

Before merging documentation changes, verify the site locally from `docs-site/`:

1. install dependencies with `npm install`
2. run `npm run docs:dev` while writing
3. run `npm run docs:build` before merge
4. use `npm run docs:preview` if you need to inspect the production build locally

For documentation like the Android technical pages, local validation should include:

- navigation still reaches the changed page
- related-page links still resolve
- headings and sections render cleanly in VitePress
- terminology matches the implementation that shipped in the repo

## GitHub Pages workflow

The docs site is published through `.github/workflows/deploy-docs.yml`.

The workflow runs on:

- manual dispatch
- pushes to `main` that touch `docs-site/**`
- pushes to `main` that modify the docs deployment workflow itself

The current pipeline is straightforward:

1. check out the repository
2. install Node 20
3. run `npm install` in `docs-site/`
4. run `npm run docs:build`
5. upload `docs-site/.vitepress/dist`
6. deploy the artifact to GitHub Pages

## Operational expectations

This workflow is for the documentation portal, not for Android app delivery.

That distinction matters:

- docs publishing is handled by GitHub Pages
- app builds and app release distribution are separate concerns
- changing playback behavior does not publish docs by itself unless the docs files also changed

If you are touching both code and docs, treat the docs deployment as its own verification step.

## What can break a docs deployment

The most common failure modes are simple:

- broken markdown links
- bad relative asset references under the `/nexio/` base path
- VitePress build failures
- navigation updates that point to files that do not exist

For technical docs, there is a second class of failure:

- the page builds correctly, but the content no longer matches the code

That is why these pages should be grounded in the repository implementation, especially for experimental playback features.

## Publishing guidance for playback documentation

Playback docs age quickly because they describe code paths that can change across app, media, and native modules at once.

Before publishing changes to pages like [Media3](../android/technical/media3.md), [FFmpeg](../android/technical/ffmpeg.md), [libdovi](../android/technical/libdovi.md), or [IEC Passthrough](../android/technical/iec.md), confirm:

- the user-visible setting still exists
- the fallback or probe behavior still matches runtime code
- experimental features are labeled honestly
- debug-only workflows are not described as production features

## Recommended contributor checklist

- Build the docs locally.
- Click through the changed page and its related links.
- Check the corresponding runtime code if the page describes playback behavior.
- Merge to `main`.
- Confirm the GitHub Pages deployment completes successfully.
- Verify the published page under the `/nexio/` site path.

## Related pages

- [Architecture](./architecture.md)
- [Media3](../android/technical/media3.md)
- [IEC Passthrough](../android/technical/iec.md)
- [Android Getting Started](../android/getting-started.md)
