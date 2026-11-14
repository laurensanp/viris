# VIRIS

Repository github.com/laurensanp/viris (MIT): a Fabric mod for Minecraft 26.3 that runs Voxy's own renderer on the
game's Vulkan renderer through OpenGL interop. Own repo since 2026-11-02 (before: `viris/` in laurensanp/siris; the
history came along, older paths `voxy-vulkan/`, package `com.siris.viris`). Voxy is All-Rights-Reserved: never pack,
commit or modify its jar (runtime mixins only). Vitrail (LGPL-3.0) is not ours either.

Works with SIRIS (DLSS SR + FG, github.com/laurensanp/siris, local clone `../siris`), not required. Same repo layout and README sections as SIRIS, keep them that way. Docs mention it
only as "works with"; no dependency in either direction. The one link in code: SIRIS checks `isModLoaded("viris")` and
then keeps Vitrail's reload on join (SIRIS 1.1.4+).

The game's default "Graphics API" tries OpenGL first; since 0.3.5 `PreferredGraphicsApiMixin` tries Vulkan first then
(Windows, Voxy loaded), so VIRIS doesn't need SIRIS for that. An explicit `preferredGraphicsBackend:"opengl"` stays:
Voxy draws by itself and VIRIS does nothing (tested 2026-11-02, also with SIRIS, which forces Vulkan either way).

| Path | What |
|---|---|
| `mod/` | the Fabric mod (Java), same layout as SIRIS; `src/main/java/com/viris/`: `VoxyFrame` (the frame), `GlContext`, `Interop`, `AtlasReader`, `pack/` (shader packs), `mixin/` |
| `libs/` (ignored) | compile-only jars: Voxy 0.2.20-beta, Sodium 0.9.2, Vitrail 0.13.0-beta (Modrinth) |
| `mod/run/` (ignored) | test game dir: Sodium, Fabric API, Voxy, optional Vitrail in `mods/`, `options.txt`, `saves/vulkan-test` |

Social preview: `.github/assets/social-preview.png`, 1280x640, SIRIS' design in VIRIS' colours (Bahnschrift, same positions).
GitHub has no API for it: upload by hand in the repo's Settings, Social preview.

## Build & test

- Gradle needs **JDK 25**: `JAVA_HOME=~/.jdks/jdk-25.0.4.1+1`, use `--no-daemon`. `cd mod && gradlew build` -> `mod/build/libs/viris-*.jar`.
- `cd mod && gradlew runClient --no-daemon --args="--quickPlaySingleplayer vulkan-test"`. SIRIS' DevTest options don't exist here.
  For a screenshot use a temporary (never committed) `@Inject(method = "tick")` in `MinecraftMixin`: send commands with
  `mc.player.connection.sendCommand`, `Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, ...)`
  at tick 500, `mc.stop()` later. Spectator at `-36 200 18 -90 25` shows the LODs. F2 sent from outside doesn't reach the game.
- One run at render distance 32 so Voxy ingests, then lower it. To test with SIRIS use its test loop (`../siris`).

## Notes

- VIRIS (2026-11-01, user's wish: voxy-vulkan renamed for 0.3.0, blue theme): mod id `viris`, package
  `com.viris` (main class `Viris`), `viris.mixins.json`, `assets/viris/icon.png`, `.github/assets/viris-icon.*`
  (frame stack as in SIRIS' icon, in blue: ground `#0F1522`, `#4F5B6E`, `#7CC4FF`, `#3D8BFF`, 2x2 voxel grid instead of the plus),
  log prefix `VIRIS:`, dev property `-Dviris.timing`, jar `viris-<version>`, Modrinth `viris` (own project). Lunar:
  remove `voxy-vulkan-*.jar` (other mod id, else both load). Theme colour for VIRIS: `#3D8BFF`.
- VIRIS in Lunar: see "Current Lunar install". Lunar's game log shows none of VIRIS' INFO lines (only `Enabling
  optional FeatureSet [VIRIS OpenGL interop]`); check with F3 (voxy line) and F2 screenshots. Voxy Server Side (`lss`
  0.19.0) works with VIRIS: it feeds Voxy's own ingest (`VoxyCompat` raw ingest bridge), VIRIS only draws; dev test via
  LAN (`publish` in -PtestCommands; singleplayer alone waits for LAN). Used by the user on survival.glaaaspenis.de.
- voxy-vulkan 0.2.0 (2026-11-01, user asked for a 1:1 port; the own LodRenderer of 0.1.x is gone): Voxy's own
  GL renderer runs unchanged on a hidden SDL OpenGL 4.6 context (`GlContext`, made current at
  `RenderSystem.initRenderer` RETURN order 800, before Voxy's own init at 900). Voxy's only draw entry is
  `VoxyRenderSystem.setupViewport(proj, modelView, fog, w, h, camXYZ)` + `renderOpaque(viewport, depthGlTex,
  colorGlTex)`; `VoxyFrame` calls it right before the game's "Solid" pass (`LevelRendererMixin` on
  `lambda$addMainPass$0`, matrices from `SodiumWorldRenderer.prepareChunkRendering`) into shared images over an
  empty picture, then a Vulkan pass writes colour (blend ONE, 1-srcA) and depth where Voxy drew; terrain draws
  over them by depth (= Voxy's stencil). Shared images: RenderPearl textures from a VMA pool with exportable
  dedicated memory (`VulkanGpuTextureMixin`, `Interop`), imported with GL_EXT_memory_object_win32; device gets
  VK_KHR_external_memory/semaphore_win32 as an optional FeatureSet. Three image sets in turn, binary semaphores per
  set (glDone/vkDone with texture lists). Lightmap: shared 16x16 copy per set. Atlas: `AtlasReader` rebuilds the
  block atlas from sprite images (texels start at u0/v0 = x + padding, not getX). Rows: RenderPearl's Vulkan images
  keep GL's row order, no flip. Patched Voxy hooks: device check, Iris hook, Sodium draw hook (`postApply`),
  `LightMapHelper.getLightmapTextureId`, `SoftwareModelTextureBakery.setupTexture`.
  Verified 2026-11-01: day and night look like Voxy on OpenGL; with DLSS Quality + FG x2 at 1080p, render distance
  8: Voxy ~0.85 ms render thread + ~0.7 ms GL per frame, 200 vs 230 rendered fps (Voxy rendering off). Lunar
  (`DLSS Optimized`, siris-1.1.2, backup `mods-backup-2026-11-01-voxy/`): Voxy 0.2.20 + voxy-vulkan 0.2.0 run, LODs
  seamless past render distance 8, FG ~357/180 fps (shaders off; the profile has Complementary on, so LODs are off
  there by default). Open: thin dark horizontal lines over LOD water; first seen with DLSS, but on 2026-11-02 the same lines
  showed without SIRIS, on Vulkan and on OpenGL (same places), so not DLSS and not the interop. Hangs seen earlier were VRAM: with Lunar's game open
  (~4 GB) GL frames took up to 1.4 s with 474 evictions and RenderPearl's 5 s submit timeout hit; close Lunar's game
  before dev tests. SIRIS side note: closing Lunar's window once wrote a watchdog crash report
  (`DlssNative.destroyPresenter` hung on shutdown). Tried and dropped: timeline
  semaphores (NVIDIA 617 passed only GL's first signal to Vulkan), GL fence + CPU wait before submit (hung with
  DLSS), reading back a shared texture on the GL side (glGetTextureImage, hung). `-Dviris.timing=true` logs
  Voxy CPU/GL time, free VRAM and evictions. Without Voxy support in the pack the LODs are skipped while it is on.
  Shader packs (2026-11-01, milestone 1): packs with Voxy support (`shaders/<world>/voxy.json` + `voxy_opaque.glsl`/
  `voxy_translucent.glsl`: Complementary, BSL, Photon, Solas, MakeUp...) draw the LODs with their own code, as under
  Iris. `pack/PackPatch` reads the patch with Vitrail's IncludeExpander (place from `DimensionSet.place`,
  `minecraft:overworld` -> `world0`), `pack/PackPipeline` builds Voxy's `IrisVoxyRenderPipelineData` without Iris
  (Unsafe; `VirisMixinPlugin` strips its Iris-only builder methods at load or the verifier fails), std140 uniform
  block filled from Vitrail's `PackValues.catalog()`, `RenderPipelineFactoryMixin` makes Voxy build its
  `IrisVoxyRenderPipeline`, block ids from Vitrail's `BlockStateIds.table`. Vitrail side (`mixin/vitrail/`): `VOXY 3`
  define, vx* uniforms in the engine catalog (`VoxyPackUniforms`, projection un-reversed like Voxy), vxDepthTex samplers
  bound in `PackPass.bindSamplers` to Voxy's depth converted to far = 1 (`DepthConvert`). Frame: GL draws at the main
  pass start, Vulkan waits there (after `GeometryHold.flushIdle` + `GraphicsApi.suspendLevelPass`, Vitrail keeps
  passes open), `PackChain.drawBeforeTranslucents` HEAD writes the LOD gbuffer targets into the pack's targets where
  the scene depth is clear (side = first deferred step's read side), vkDone with the frame's submit. Not yet: shadow
  maps and depthtex for the patch (dummies: LODs get no shadows), renderToVanillaDepth packs.
  LOD water (2026-11-03, user: "after the line it is just blue", black line at the LOD start): the translucent patch had
  an empty vxDepthTex, black noisetex and reflection colours. Now: `postOpaquePreTranslucent` TAIL converts Voxy's opaque
  depth into `patchDepth`; noisetex and `texture.gbuffers.*` PNGs come from the pack (`customTexture`); other colortexN/gauxN
  samplers are copied from Vulkan after deferred into `packInputs` and read when the set comes round (3 frames old, so
  reflections lag slightly in motion). Black shores were reflections of black.
  LOD shadows lag when turning (2026-11-14, issue #1, Complementary): its patch reads screen-space shadows (colortex18)
  and reflections "of the previous frame" and reprojects them by one frame, but packInputs are SETS frames old. The
  patch's uniform block (`PackPipeline.uniformLayout`, not the pack's Vulkan passes) now gives vx*Prev and
  previousCameraPosition* from SETS frames ago (ring per frame, `VoxyPackUniforms.frames()`). Verified in dev with a
  temporary hook turning 1.5 deg/frame at y 200, time 11000: moving and still shots match, before the shadows were off.
  Seam at the render distance + far water/reflections clearer than the fogged terrain (same day, user's shots, Unbound at
  sunset, reproduced in dev from y 110 pitch 35, `time set 11800` every tick, RD 8 vs 16 of the same spot): two causes. (1) Vitrail
  binds a black stand-in for `vxDepthTexOpaque` in geometry programs ("read one pixel, because nothing fills them yet"), and
  the pack's gbuffers_water takes min(depthtex1, LOD depth), so all normal water counted as 0 deep: alpha 0.25, nearly clear
  whatever the depth. `GeometryProgramMixin` binds the real one (far before the composite). So near water is deeper in colour
  than before, as under Iris. (2) Translucents were drawn after deferred (a split added that morning), but packs fog LOD water
  in deferred (no DoFog in voxy_translucent): water stays in target 0 and goes through deferred again, as under Iris.
  Seam and clear reflections gone with Unbound, Reimagined; not tested on the user's server. Seen, unchanged by this (same with the old jar):
  with Solas + Voxy all water is milky pale blue and the far terrain isn't fogged like without Voxy (compare no Voxy, RD 16).
  Thin dark lines through LOD water along the section grid, stepped (same day, user's Lunar shot; the old "Open" item above): Voxy's
  quad_util scales a top's indentation with the voxel (`quadStart*lodScale`: water 1/8 block at level 0, 2/8, 4/8...), and a voxel of a coarse
  level ends above the real water, so the sea steps by level (sea top 63: 62.875, 63.75, 63.5, 63, 62...). Where the farther part is
  higher its front shows. Debug colours per face in the translucent patch: red/green on the lines; without the side faces the lines
  became holes to the sky, proof of the step. `ShaderLoaderMixin` (text patch of ShaderLoader.parse) puts translucent indented tops of
  level >= 1 at voxel top - 1 - indent = 62.875 at every level, side faces capped there (`maxY`); `RenderDataFactoryMixin` drops water's
  side faces on section borders, they showed through the surface. Works without a pack too (not looked at). Tried: shading the fronts as surface
  (face = 1) and forcing them opaque, both left lines. Limits: water tops not at an even height still step, by less than before, and
  there the missing sides can show a hole (no lake seen yet). A short line stays in shallow water (y 668 in the dev view -36 110 18 180 6): stays
  with all water sides removed, gone with real chunks, probably the LOD seabed's own step seen through the water.
 Exceptions in these hooks would stop Vitrail:
  they only switch the LODs off.
  Hard edge at the render distance, near water dark/see-through, LOD water hazy (2026-11-03, user's Lunar shots, flying high; NOT fixed, user's Unbound in DLSS Optimized has clouds at 400/496 as a workaround): only
  with the camera above the clouds. Reproduced in dev with the user's world (Unbound, RD 12): y 200 pitch 40 edge, y 165 none, y 200 with
  `CLOUD_STYLE_DEFINE=0` none, with `CLOUD_ALT1=400 CLOUD_ALT2=496` none; rain, mods, LOD data, atmospheric fog not the cause. gbuffers_water
  discards water when `colortex5.a` (cloud distance, set where cloud alpha > 0.5) is nearer than the water; LOD water sits in the target
  before deferred and gets the clouds on top. Tried in temporary pack copies: no discard (water everywhere, near clearer than far), alpha * 0.35 under cloud
  (edge stays); same discard in the LOD patch (gaux2.a; hardly changed anything, reverted). A real fix needs the cloud's alpha in near water. Debug pack idea
  that ruled out wrong depth in Lunar: gbuffers_water writing depthtex1 = far (r), vxDepthTexOpaque 0/far (g), water depth bands (b): same in Lunar and dev.
  Ghost LODs (0.3.1, 2026-11-01, user's server shot): switching Voxy off in its settings calls
  `voxy$shutdownRenderer`, `IVoxyRenderSystemHolder.getNullable()` turns null and VoxyFrame drew nothing, but
  `packReadSet` kept the last set, so the pack's vxDepthTex still saw LOD depth and shaded grey terrain outlines over
  the sky. `beforeOpaquePass` now clears `packReadSet` every frame. Reproduced in dev with a temporary
  shutdown-after-N-frames hook (not committed). Possibly also the "grey/black-white background" from the user's first
  server shot.
  Rejoin (0.3.2, user: "sometimes outlines when I join a world again"): `compositePack` exposed `packReadSet` before
  checking the pack's gbuffer targets (missing while Vitrail sets the pack up) = depth without colour; it is now set
  after them. And when Voxy built its renderer while the pack wasn't on, it ran its own pipeline and `packFrame` waited
  forever on `data().thePipeline`; now it calls `recreateRenderer()` (at most every 2 s). Verified in dev with a
  temporary hook that rebuilds Voxy's renderer once without the pack pipeline: LODs come back in colour.
  Shader reload (0.3.3, user: "after reloading shaders the LODs are completely gone"): (1) `dropPackPipeline` kept
  `packKey`, so the same pack coming back never rebuilt the pipeline; it now resets it (the key-change branch drops first,
  then sets the key). (2) While the pack switched off, Vitrail still held a pass open and the own-pipeline `render()`
  threw "Cannot add semaphore operation while inside RenderPass" -> `failed` for the session; it now calls
  `closeVitrailPasses()` first when Vitrail is installed. Verified with a temporary pack-off-for-100-frames hook.
  Grey LODs on every join after the first (user's server, "first join fine, second corrupted, shader reload fixes
  it"): SIRIS' `VitrailJoinReloadMixin` skipped Vitrail's pack reload on rejoin and the pack then drew the LODs without
  colour. SIRIS 1.1.4 keeps the reload when `viris` is loaded. Reproduced and verified with `-PtestRejoin=300`. A
  delayed VIRIS pipeline rebuild after join did not help (dropped). Sky light from Voxy Server Side is not the cause.
  Voxy is All-Rights-Reserved: never pack or modify its jar (runtime mixins only).
- Photon 1.4 (2026-11-09, 0.3.7, user's Lunar profile `just-optimized`): `PackPassMixin` bound a null vxDepthTex view in
  Photon's prepare passes (before Voxy's first frame), Vitrail threw `textureView and sampler must both or neither be null`
  and stopped the pack (vanilla look); now Vitrail's stand-in stays. Its voxy.json lists `samplers` as names (Voxy reads
  that as sampler2D) and its patch has `#if A \` + `|| B`: both read now. Separate Vitrail issue: Photon's default
  `BLOOM_UPSAMPLING_FILTER texture` becomes `ofTexture` (composite14 fails, whole pack dropped);
  `BLOOM_UPSAMPLING_FILTER=bicubic_filter` in the pack's .txt avoids it. Verified in dev (pack + LODs drawn, no errors) and by the user in Lunar on a server.
  Same day, LODs were dark outlines only (user's Lunar shot, reproduced in dev with Photon TAA on, RD 6, y 150): Vitrail's
  scene seed (`PackChain.paintSeed` -> `SceneSeed`) copies the game's picture into the pack's first draw buffer wherever no
  block was drawn, after VIRIS' LOD copy; for Photon that buffer is colortex1 = the LOD gbuffer, so the lighting pass saw
  albedo 0 (fog over bare depth). Now `PackChainMixin` copies the LODs again after `paintSeed`. Also LOD targets are
  RGBA32F (GL patch targets, shared targets, inputs): Photon packs 2x8 bits into RGBA16 unorm, RGBA16F cut them.
  `-Dviris.glDebug=true` logs GL errors with the Java stack. LOD water was missing: Photon's voxy_translucent discards
  where `texelFetch(depthtex1, gl_FragCoord)` < 1, and the patch's depthtex/shadowtex stand-in was 1x1 (0 outside it).
  Now screen sized, far = 1. Photon water verified in dev against a no-Voxy RD 24 shot; Complementary unchanged.
- Lunar drops Vitrail's log lines; since 2026-11-09 VIRIS copies them to `logs/vitrail.log` in Lunar's profile
  (`LunarVitrailLog`, wraps `Vitrail.logger()`; `-Dviris.vitrailLog=true` in dev). Mobs and the hand glowing with
  Photon in Lunar (2026-11-09) were Lunar's Fullbright mod: it gives entities full block light, Photon reads that as
  fire (`light_levels.x > 0.99` -> emissive). Not ours; Fullbright off fixes it (user confirmed). Diagnose such cases
  with a pack copy that tints entities by `light_levels`; `-Dmixin.debug.export=true` can be set per Lunar profile in
  `db/profiles.db` column `jvm_arguments` (launcher closed).

## Releases

`"C:\Program Files\GitHub CLI\gh.exe" release create <tag> <jar> -R laurensanp/viris --title "VIRIS <version> (Minecraft 26.3)"
--latest --notes-file <file>`. Only this mod's jar; Voxy and Vitrail are never attached.

Modrinth (token `MODRINTH_TOKEN` in `.env` of `../siris`, API v2, header `Authorization: <token>`): `viris` (id
IHYZKsvL, created 2026-11-01 by API, still waiting for review). New version: POST /v2/version multipart (`data` JSON +
`file`; version_type release, game_versions 26.3, loaders fabric, featured; deps: Voxy 0.2.20 version iIrM8ZKQ, Sodium,
Fabric API required, Vitrail optional), then PATCH /v3/version `client_only`. Gallery: 4 images (retaken 2026-11-02 with DLSS model M; dev world, `gamemode spectator`, `tp @p 0 130 0 -90 10` at time 2000, compare shot without the Voxy and VIRIS jars).

Newest first:
- v0.4.0 (2026-11-09): Photon works (LOD colours and water), Vitrail log copied to logs/vitrail.log in Lunar. Modrinth bez0iC7n.
- v0.3.6 (2026-11-03): LOD water like the pack's own, no seam from clear near water, one surface height. Modrinth thQ4sFuA.
- v0.3.5 (2026-11-02): Vulkan first when the Graphics API option is on its default. Modrinth TLmz6juf.
- v0.3.4 (2026-11-02): own repo, package `com.viris`; no change in the game. Modrinth NbOJ6O8J.
- v0.3.3 (2026-11-02): LODs survive shader reloads/toggles. Modrinth o1vibgM3.
- v0.3.2 (2026-11-01): pack sees LOD depth only with colour; Voxy pipeline re-requested. Modrinth ieYulT8H.
- v0.3.1 (2026-11-01): no ghost LODs after Voxy is switched off. Modrinth auUQfWsM.
- v0.3.0 (2026-11-01): voxy-vulkan renamed VIRIS, shader pack LODs.

## Working with the user

- Verify every change with screenshots as above. Ask before downloading third-party code.
- Install in Lunar only while its game isn't running, back up the mods folder first (`mods-backup-<date>-<what>/`).
