# Gamma — Chunk classifiers

New-chunk detection is a pluggable pipeline (`dev.gamma.chunks.classifier`): each
`ChunkClassifier` looks at one `ChunkObservation` — a plain-data snapshot with no Minecraft types
in it, built by `ChunkObservationCollector` from real packets and chunk state — and returns a vote
in `[-1, 1]` (positive = "looks freshly generated"), or `NaN` to abstain. `ClassifierPipeline`
combines whichever classifiers had an opinion into one weighted confidence score in `[0, 1]`.

**Important limitation, stated up front:** every classifier below was implemented against real,
verified 26.2 APIs (checked with `javap` against the actual mapped jar, not assumed — see the
per-classifier notes), and covered by unit tests using synthetic data.
None of them have been empirically measured for real-world accuracy against a live server.
Implementation is only half the job; validation is the other half, and it has not happened yet.
Treat every weight and threshold below as an untested starting point, run `.chunks record` on a
real server to build a fixture corpus, and revisit this document once there is real accuracy data
to report.

## The pipeline

`ClassifierPipeline.standard()` wires up all six classifiers below. `ClassifierContext` gives each
one access to `RollingBaseline`s (Welford's online mean/variance, per server+dimension+metric,
`BaselineStore`) — this is where per-server baseline calibration and outlier
rejection under lag actually live, shared by every classifier that needs one, not just
generation-latency. A baseline abstains (returns NaN from `zScore`) until it has seen 8 samples,
and rejects (doesn't fold in) any sample more than 5 standard deviations from the current mean
once warmed up, so one lag spike doesn't wreck calibration for the rest of the session.

Combination: `confidence = (Σ(weight·clampedSignal) / Σweight + 1) / 2`, over classifiers that
didn't abstain. `confidence ≥ 0.6` → `LIKELY_NEW`, `≤ 0.4` → `LIKELY_EXISTING`, otherwise
`UNKNOWN` (including "every classifier abstained," which reports `UNKNOWN` at `confidence = 0.5`
rather than picking an arbitrary default).

## The six classifiers

| Classifier | Weight | Metric(s) | Needs a baseline? |
|---|---|---|---|
| `GenerationLatencyClassifier` | 1.5 | render-distance-entry → chunk-packet-arrival latency | yes |
| `LiquidSettlingClassifier` | 1.0 | flowing-fluid block updates in the post-load window | yes |
| `PostLoadUpdateBurstClassifier` | 0.7 | all block updates in the post-load window | yes |
| `LightingCoverageClassifier` | 0.5 | populated sky+block light layer count | yes |
| `UnrolledLootClassifier` | 1.0 | fraction of containers with an unresolved loot table | no — self-normalizing ratio |
| `PaletteEntropyClassifier` | 0.8 | block-state Shannon entropy + raw packet size | yes (both sub-metrics) |

### Generation latency

The primary candidate. Vanilla never sends an explicit per-chunk "request" — the server
streams chunks as the player moves — so `ChunkObservationCollector` proxies "requested" with "this
chunk position entered render distance and isn't loaded yet" (tracked on `TickEvent`, only
re-scanned when the player's own chunk position changes, not every tick). The gap between that
moment and the chunk packet's actual arrival is scored as a z-score against a per-server-per-
dimension baseline. This is the same general technique real open-source "new chunk" finder mods
have used historically — not novel, but also not verified here against an actual server's timing
characteristics.

### Liquid settling

Flowing (non-source) fluid block updates landing on a chunk in the 3 seconds after it loads.
Hypothesis: freshly generated terrain has fluids still settling; an already-explored, previously-
ticked chunk mostly doesn't. Counted via `PacketReceiveEvent` matching
`ClientboundBlockUpdatePacket`/`ClientboundSectionBlocksUpdatePacket`, checking
`BlockState.getFluidState()` (`!isEmpty() && !isSource()`) — both real, verified accessors.

### Post-load update burst

Block-update packet flags on the section-blocks-update path were the obvious candidate.
Verified against the real 26.2 jar (`javap` on `ClientboundBlockUpdatePacket` and
`ClientboundSectionBlocksUpdatePacket`): **neither packet exposes per-block edit flags on the wire
in this version.** That field existed in much older protocol versions and is gone now — there is
nothing to read. What's still real on the same packet stream: the sheer *volume* of block-update
packets a chunk receives right after loading. World-gen decoration passes (trees, caves, fluid
carving, structure post-processing) finishing just after generation produce a measurable burst of
corrections that an already-settled chunk mostly doesn't. Scored the same way as liquid settling,
against a baseline of total (not just fluid) update count. See the design notes for the same
pivot recorded there.

### Lighting coverage

Populated sky+block light layer count (`ClientboundLightUpdatePacketData#getSkyYMask`/
`getBlockYMask`, `.cardinality()`) from the packet, captured before it's applied
(`PacketReceiveEvent`, matching `ClientboundLevelChunkWithLightPacket`). Hypothesis: world-gen
computes lighting proactively, so a freshly generated chunk tends to arrive with data for close to
every section, while a loaded-from-region-file chunk can arrive sparser if propagation is still
settling from neighbors. Weighted lowest of the baseline-driven classifiers specifically because
this hypothesis is the shakiest of the six and hasn't been checked against real traffic.

### Unrolled loot

Block-entity presence inconsistencies, narrowed to one real, verified mechanic: a
generated container (chest, barrel, hopper, ...) that nobody has opened yet stores an unresolved
loot-table reference instead of real items —
`RandomizableContainerBlockEntity#getLootTable() != null`, real vanilla behavior, verified against
the jar. A chunk where every container is still in that state is a strong sign nobody has been
there. This is the one classifier that needs no calibration — it's a self-normalizing ratio
(unrolled / total containers) — so it's also the only one with an opinion from the very first
chunk of a session, before any baseline has warmed up.

### Palette entropy + packet size

These two are paired deliberately. Both are proxies for "how much distinct terrain/structure
detail is packed into this chunk." Entropy is computed from real per-section palette occurrence
counts (`PalettedContainer#count(CountConsumer)`, verified — a much cheaper read than a manual
16×16×height block scan), normalized to `[0,1]` via `PaletteEntropy.normalizedShannonEntropy`
(pure, unit-tested independently of anything Minecraft-shaped). Packet size is
`ClientboundLevelChunkPacketData#getReadBuffer().readableBytes()`. Both are scored as z-scores
against their own per-server-per-dimension baseline and averaged (using whichever sub-metric has
enough baseline data if only one does).

## Testing

`ChunkObservation` being plain data (no Minecraft types) means every classifier, `RollingBaseline`,
`ClassifierPipeline`, and `PaletteEntropy`/`ContentHash` are unit-testable with zero game
dependency — see `src/test/java/dev/gamma/chunks`. `ChunkObservationFixtureTest` runs the real
`ClassifierPipeline.standard()` against fixture JSON under `src/test/resources/fixtures`; those two
fixtures are **hand-authored idealized profiles**, not a real server capture. `.chunks record` dumps
`{"observation": ..., "result": ...}` JSON in the exact shape `FixtureLoader` reads (just the
`observation` object matters) — drop real captures into `src/test/resources/fixtures` as they
become available and extend `ChunkObservationFixtureTest` rather than trusting the synthetic ones
long-term.
