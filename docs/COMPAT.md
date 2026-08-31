# Gamma — Compatibility

Third-party mod compatibility notes.

## Methodology

These notes are a static pass rather than a playtest -- no second mod jar was installed alongside
Gamma and launched. What follows is: every mixin target and every render
seam Gamma touches, cross-referenced against what chunk-rendering optimization mods (Sodium,
Embeddium, and similar forks, historically) are known to touch, to flag what's likely to
conflict before anyone tries it for real.

**This is not a substitute for an actual playtest.** Treat every "low risk" verdict below as
"didn't find a static conflict," not "confirmed compatible." Before shipping a release that
claims compatibility, install a real optimization mod for 26.2 and run through the checklist
at the bottom of this file.

## Known conflict: `Xray` / `RenderSectionRegionMixin`

Already flagged in `Xray`'s own class doc, repeated here because it's the one real finding:

`Xray` works by mixing into `RenderSectionRegion#getBlockState` (see
`mixin/render/RenderSectionRegionMixin.java`) — the `BlockAndTintGetter` view chunk meshing
reads from — and substituting air (or glass) for culled blocks at that read. This is
deliberately *not* a mixin into vanilla's mesh-building algorithm itself, so it only affects
what gets rendered, never collision or world state.

Chunk-rendering optimization mods rewrite the mesh-building path itself, and historically that
rewrite has meant bypassing or replacing `RenderSectionRegion` (or its equivalent) with their
own chunk-data view for performance. If a 26.2-era optimization mod does the same:

- Its replacement view won't run through `RenderSectionRegionMixin`, so `Xray` will silently
  stop culling anything — the world renders normally, no crash, just the module doing nothing.
- If it *extends* `RenderSectionRegion` rather than replacing it outright, our mixin should
  still apply and Xray should keep working — this is the case worth checking first.

Mitigation if this turns out to be a real conflict: the fix is a second, mod-specific mixin
target added alongside the existing one (never a replacement — the vanilla path must keep
working when the optimization mod isn't installed), gated so it only registers if the other
mod's class is present on the classpath. Not implemented speculatively here since there's
nothing concrete to target yet — do this once a specific mod and its actual class names are
known.

## Everything else Gamma mixins into

None of these touch the chunk-mesh-building seam, so a chunk-rendering optimization mod is
unlikely to conflict with them — they sit on different vanilla classes entirely (entity
rendering, screen effects, fog, particles, input, networking):

| Mixin | Target | Why low risk |
|---|---|---|
| `CameraMixin` | `Camera` | Camera position/rotation, not chunk geometry |
| `MouseHandlerMixin` | `MouseHandler` | Input handling only |
| `FogRendererMixin` / `FogRendererAccessor` | `FogRenderer` | Fog color/density, not mesh data |
| `MobEffectFogEnvironmentMixin` | `Darkness`/`BlindnessFogEnvironment` | Same — fog, not geometry |
| `GameRendererMixin` | `GameRenderer` | Hurt cam / view bobbing, post-processing |
| `HumanoidArmorLayerMixin` | `HumanoidArmorLayer` | Entity render layer |
| `ItemEntityMixin` | `ItemEntity` | Dropped-item spin, entity-side |
| `ItemInHandRendererMixin` | `ItemInHandRenderer` | First-person view model |
| `ScreenEffectRendererMixin` | `ScreenEffectRenderer` | Full-screen overlays |
| `TotemParticleMixin` | `TotemParticle` | Particle lifecycle |
| `WeatherEffectRendererMixin` | `WeatherEffectRenderer` | Rain/snow render pass |
| `LightmapRenderStateExtractorMixin` | `LightmapRenderStateExtractor` | Lightmap values, not chunk geometry — worth a real-world check anyway since some optimization mods ship their own lighting engine, see below |
| `ConnectionMixin` | `Connection` | Packet interception, no render involvement |
| `PlayerNameMixin` | `Player` | Name text, no render involvement |

**One caveat inside that "low risk" list:** some historical chunk-rendering optimization mods
bundle a custom light engine, not just a chunk mesher. If a 26.2 mod does that, it might
change what `LightmapRenderStateExtractor` sees before `LightmapRenderStateExtractorMixin`
runs — Fullbright's night-vision override should still apply (it runs at `@At("RETURN")` and
just overwrites two fields), but verify Fullbright's *gamma* override (not the night-vision
mode) still reads correctly if that mod ships a fundamentally different brightness path.

## `Renderer3D` / `Renderer2D` / ESP modules — no conflict risk expected

Every ESP, tracer, waypoint, and chunk-border draw goes through `Renderer3D`, which is a facade
over vanilla's own public `Gizmos` API (see the design notes, 2026-07-28), not a hand-rolled
pipeline and not anything derived from the chunk mesh. `Renderer2D` similarly only touches
`GuiGraphicsExtractor` in GUI space. Neither seam is one a chunk-rendering optimization mod has
any reason to touch, so ESP/HUD/waypoints should keep working regardless of what's installed
alongside Gamma — this is the strongest guarantee in this document, precisely because it was
an architectural choice made to avoid exactly this class of conflict.

## Manual verification checklist (do this with a real client + a real optimization mod)

1. Both mods installed, game launches without a Mixin apply failure in the log.
2. `Xray` on, walk through terrain — confirm culling still happens. If not, see the conflict
   section above.
3. `ChunkBorders` on — confirm boundaries still draw and align with actual chunk edges (a
   mismatched chunk-data view would show borders in the wrong place, not just fail to render).
4. `Fullbright` — both gamma-override and night-vision modes, confirm brightness actually
   changes.
5. `.gamma profile` with a typical ESP loadout on, both with and without the optimization mod
   installed — confirms Gamma's own extraction cost is unaffected by whichever chunk renderer
   is active (it should be, since extraction never touches chunk mesh data), and gives real
   numbers for the "under 5% FPS cost" target instead of the estimate in the design notes.
6. `StorageESP` / `BlockESP` on a busy base — confirm block-entity/block-state reads still
   return correct data (these read `ClientLevel`/`LevelChunk` directly, not the render-side
   `RenderSectionRegion` view, so they should be unaffected — but worth confirming a chunk
   optimization mod hasn't also intercepted those reads).

Record results here (update this file) once a specific mod and version has actually been
tested — until then, everything above is analysis, not verification.
