# DV P5 To P8.1 Realtime Playback Feasibility Spike

## Decision Bar

Nexio only accepts a path that produces correct HDR10 colors from Dolby Vision Profile 5 on non-Dolby Vision HDR10 TVs. Metadata-only output that shows green/purple on an HDR10 sink is not acceptable.

Dolby Vision over HDMI is a bonus follow-up gate, not a blocker for the primary correctness decision.

## Candidate Architectures

1. Current metadata-only DV5 to P8.1 path: verify the presumed green/purple baseline on AM9 plus Samsung.
2. SoC DV composer downconvert: route P5 to the Amlogic Dolby Vision decoder/composer and let stock firmware emit HDR10 for the attached non-DV sink.
3. libplacebo pixel conversion fallback: decode Profile 5 as Main10, reshape and convert IPT to BT.2020 PQ on GPU, and output HDR10 RGB.

## Evidence

- Device baseline: `device-baseline.md`
- HDR10 correctness verdict: `tunnel-verdict.md`
- libplacebo benchmark: `libplacebo-benchmark.md`
- Production plan: `production-plan.md`
- Device identifiers: `evidence/device-ids.env`
- Test media: `evidence/test-media.md`

## Known Inputs

- AM9 Pro rooted ADB target: `192.168.50.71:5555`
- CoreELEC Dolby Vision module reference: `/Users/jneerdael/Downloads/dovi.ko`
