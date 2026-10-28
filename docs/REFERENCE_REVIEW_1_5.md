# External reference review — Preview4

Read-only metadata/license inspection at the pinned revisions below. No upstream source was copied into Yono. These are not feature benchmarks or dependency additions.

| Repository | Reviewed revision | License observation |
|---|---|---|
| [ItzSkyeYT/InterTune](https://github.com/ItzSkyeYT/InterTune/tree/524f1cd7582f5795d42cb5a9c79f7c32a517af62) | `524f1cd7582f` | GPL-3.0 |
| [cromaguy/Rhythm](https://github.com/cromaguy/Rhythm/tree/85fea209c4d44046192d830faa9181db3ece82ad) | `85fea209c4d4` | GPL-3.0 |
| [deniscerri/ytdlnis](https://github.com/deniscerri/ytdlnis/tree/7c8e0483c13b678a159e84e73792c2d6a9398beb) | `7c8e0483c13b` | GPL-3.0 |
| [JunkFood02/Seal](https://github.com/JunkFood02/Seal/tree/7677f61a20fda4210e84215fef9e9b51d25daa47) | `7677f61a20fd` | GPL-3.0 |
| [team-spotube/spotube](https://github.com/team-spotube/spotube/tree/0d5b5b094e9dc8a08a80c88729472a71db4f3d78) | `0d5b5b094e9d` | AGPL-3.0 |
| [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist/tree/876e355181fd3b04e7063b10442fef1b721d53b3) | `876e355181fd` | GPL-3.0 |
| [namidaco/namida](https://github.com/namidaco/namida/tree/dc2c96c598d1e9cd6616cf228c17011e23555d9a) | `dc2c96c598d1` | Custom source-available EULA; not treated as OSI open source |

YTDLnis DownloadWorker.kt and CancelScheduledDownloadWorker.kt were additionally read. Per-item failure containment and queue continuity are relevant patterns. Its process-destruction/global cancellation strategy was not copied into the embedded Python design: Yono cancellation remains request-scoped and cooperative. Other projects received metadata/license inspection only; a source-path query for Seal returned no selected source files. No claims about their streaming behavior were established.

The Namida LICENSE describes source-available/noncommercial restrictions and proprietary/private components. Its source was not reused or represented as freely redistributable. Any later adoption would require a separate license compatibility review.

Crossfade, ReplayGain, synchronized lyrics and custom DSP remain deferred while streaming/source recovery is experimental.
