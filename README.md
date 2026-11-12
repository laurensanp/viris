<div align="center">

<img src=".github/assets/viris-icon.svg" width="128" height="128" alt="VIRIS icon">

# VIRIS

**Voxy LODs on Minecraft's Vulkan renderer.**

![Vulkan](https://img.shields.io/badge/Vulkan-3D8BFF?logo=vulkan&logoColor=white)
![OpenGL interop](https://img.shields.io/badge/OpenGL-interop-3D8BFF?logo=opengl&logoColor=white)
![Voxy](https://img.shields.io/badge/Voxy-LODs-3D8BFF?logoColor=white)
![Vitrail](https://img.shields.io/badge/Vitrail-Shaders-3D8BFF?logoColor=white)

</div>

---

[Voxy](https://modrinth.com/mod/voxy) only renders through OpenGL and refuses the Vulkan backend. VIRIS runs Voxy's
own, unchanged renderer on Vulkan: it opens a hidden OpenGL context next to the game's Vulkan device, Voxy draws into
images both APIs share (GL/Vulkan interop), and the game blends them in before the world's terrain. The picture is
Voxy's own (textures, lighting, water, fog, SSAO), not an imitation.

Status: **experimental**, personal project, Windows only. Tested on an RTX 4060 Ti (driver 617, Windows 11). Bug
reports welcome in the [issues](https://github.com/laurensanp/viris/issues).

The project was called `voxy-vulkan` before 0.3.0.

## Contents

* [What you need](#what-you-need)
* [Install (players)](#install-players)
* [Lunar Client](#lunar-client)
* [Settings](#settings)
* [Shader packs (Vitrail)](#shader-packs-vitrail)
* [Works with](#works-with)
* [Performance](#performance)
* [Troubleshooting](#troubleshooting)
* [Build from source (developers)](#build-from-source-developers)
* [How it works](#how-it-works)
* [Known issues](#known-issues)
* [License](#license)

## What you need

| | |
|---|---|
| OS | Windows 10 / 11, 64-bit |
| GPU | A driver with OpenGL/Vulkan interop (`GL_EXT_memory_object_win32`, `GL_EXT_semaphore_win32`; NVIDIA has it) |
| Game | Minecraft **26.3** with Fabric Loader **0.20.1+** |

Mods, all in `.minecraft/mods/` (or the Lunar profile's `mods/`):

| Mod | Where from | Needed for |
|---|---|---|
| `viris-<version>+mc26.3.jar` (this mod) | [Modrinth](https://modrinth.com/mod/viris) or [Releases](https://github.com/laurensanp/viris/releases) | Voxy on Vulkan |
| [Voxy](https://modrinth.com/mod/voxy) 0.2.20-beta | Modrinth | the LODs |
| [Sodium](https://modrinth.com/mod/sodium) 0.9.x for 26.3 | Modrinth | the terrain pass hook |
| [Fabric API](https://modrinth.com/mod/fabric-api) | Modrinth | Voxy |
| [Vitrail](https://modrinth.com/mod/vitrail-shaders) for 26.3 (tested: 0.13.0-beta) | Modrinth | optional, shader packs on Vulkan |

## Install (players)

1. Install Fabric Loader for Minecraft 26.3.
2. Put the mods from the table above into `mods/`. Remove an old `voxy-vulkan-*.jar` (this mod's old name, different
   mod id, both would load).
3. Start the game. It starts on **Vulkan** while **Video Settings → Graphics API** is on its default (since 0.3.5;
   before, you had to set it to Vulkan yourself).

Updating: close the game, replace the jar, start again.

## Lunar Client

Works in Lunar Client profiles that run Fabric for 26.3 (put the jars into the profile's `mods` folder while the game
is closed; Lunar ships its own Sodium, Fabric API and Mod Menu, keep those). Lunar's game log doesn't show VIRIS' info
lines: to see that it runs, look for the Voxy line on F3 and for LODs past your render distance.

## Settings

VIRIS has no settings of its own. Voxy's settings work as usual, including switching it off and on in game. One game
setting matters:

| Setting | What it does |
|---|---|
| Video Settings → Graphics API | Default or Vulkan: Voxy draws through VIRIS. OpenGL: the game stays on OpenGL, Voxy draws by itself and VIRIS has nothing to do. |

## Shader packs (Vitrail)

[Vitrail](https://github.com/avpbynf/Vitrail-Shaders) (LGPL-3.0, by avpbynf) runs OptiFine-format shader packs on
Minecraft's Vulkan renderer. It is a separate mod, not part of this repository.

Packs with Voxy support (`voxy.json` in the pack: Complementary, BSL, Photon, Solas, MakeUp, ...) draw the LODs with
their own code, as under Iris. The LODs stay after reloading or toggling the pack and after switching Voxy off and on.
Tested with Vitrail 0.13.0-beta and Complementary Reimagined.

Packs without Voxy support show no LODs while they are on.

## Works with

[SIRIS](https://github.com/laurensanp/siris) (DLSS Super Resolution + Frame Generation): install it next to this mod if
you want those. It is not required, and it runs without this mod too. Use SIRIS 1.1.4 or newer, older versions make
the LODs lose their colour when you join a world a second time.

[Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) (LODs sent by the server): it feeds Voxy's own data,
VIRIS only draws.

## Performance

1920×1080, render distance 8, no shader pack, DLSS Quality + Frame Generation, RTX 4060 Ti, 2026-11-01:

| Voxy rendering off | Voxy rendering on | Voxy's share of a frame |
|---|---|---|
| ~230 fps rendered | ~200 fps rendered | ~0.85 ms render thread, ~0.7 ms OpenGL |

Numbers vary with the scene and with whatever else runs on the PC.

## Troubleshooting

* **No LODs**: search `logs/latest.log` for `VIRIS`. `could not create an OpenGL 4.6 context` or `can't share memory`
  followed by `Voxy stays off` mean the driver lacks the interop: choose OpenGL as the Graphics API and Voxy draws by
  itself. `drawing Voxy failed, LODs stay off for this session` is a bug, please attach the log to an issue.
* **No LODs with a shader pack only**: the pack has no Voxy support, see [Shader packs](#shader-packs-vitrail).
* **Grey LOD outlines after joining a world again**: update SIRIS to 1.1.4 or newer, see [Works with](#works-with).
* **Long hangs, or the game stops after a 5 s submit timeout**: video memory is full. Close other games (a second
  Minecraft takes about 4 GB) and lower the render distance.
* **Back to OpenGL** (e.g. to compare): choose OpenGL as the Graphics API, see [Settings](#settings).
* **Timing numbers**: start the game with `-Dviris.timing=true`. The log then shows Voxy's CPU and OpenGL time, free
  video memory and evictions.

## Build from source (developers)

Everything that is ours is in this repository. What is not ours is downloaded, never committed:

| Item | Where it goes | Source |
|---|---|---|
| Voxy 0.2.20-beta jar (compile only) | `libs/voxy-0.2.20-beta.jar` | [Modrinth](https://modrinth.com/mod/voxy) |
| Sodium 0.9.2 jar (compile only) | `libs/sodium-fabric-0.9.2+mc26.3.jar` | [Modrinth](https://modrinth.com/mod/sodium/version/mc26.3-0.9.2-fabric) |
| Vitrail 0.13.0-beta jar (compile only) | `libs/vitrail-0.13.0-beta+mc26.3.jar` | [Modrinth](https://modrinth.com/mod/vitrail-shaders) |
| Sodium, Fabric API, Voxy, Vitrail jars (test runs) | `mod/run/mods/` | Modrinth |
| Shader packs (test runs) | `mod/run/shaderpacks/` | Modrinth (Solas, Complementary, ...) |

Requirements: Windows 10/11, a GPU driver with the interop, **JDK 25** (Gradle needs it in `JAVA_HOME`), git.

```bat
git clone https://github.com/laurensanp/viris.git
cd viris\mod
set JAVA_HOME=C:\path\to\jdk-25
gradlew build                                             :: build\libs\viris-*.jar
```

### Test loop

`mod/run` is the test game directory (not in git). Set it up once:

* `mod/run/mods/`: Sodium, Fabric API, Voxy, optionally Vitrail (the mod itself comes from the build).
* `mod/run/saves/`: a test world. Play it once at render distance 32 so Voxy stores LODs, then lower the distance.
* `mod/run/options.txt`: `fullscreen:false` (a minimized fullscreen window is capped at 10 fps) and
  `inactivityFpsLimit:"minimized"` (else the AFK limit of 30 fps hits long runs).

Then, from `mod`:

```bat
gradlew runClient --args="--quickPlaySingleplayer <world>"
```

Meshing takes a few seconds after joining. If Gradle says "Could not connect to the Gradle daemon", run
`gradlew --stop` and try again.

More background for contributors: [`CLAUDE.md`](CLAUDE.md) (status, design, test notes).

## How it works

```
Minecraft (RenderPearl Vulkan backend)                 hidden OpenGL 4.6 context (Voxy's renderer)
──────────────────────────────────────                 ───────────────────────────────────────────
device with external memory + semaphores ────────────▶ imports the shared images
main pass starts, Vulkan waits ──────────────────────▶ Voxy draws its LODs into a shared image set
blends colour and depth in where Voxy drew ◀────────── signals when it is done
terrain draws over them by depth
  (Vitrail: the pack's own Voxy code, its gbuffer targets get the LODs where the scene depth is clear)
```

* Voxy's jar is used as installed. Its device check, its Iris hook and its draw hook in Sodium are switched off on
  Vulkan by mixins at runtime; on OpenGL they stay as they are.
* Three sets of shared images are used in turn, each with its own pair of semaphores.
* The images come from a Vulkan memory pool with exportable memory and are imported with
  `GL_EXT_memory_object_win32`.
* The block atlas and the lightmap are read on the Vulkan side and handed to OpenGL.

| Path | What |
|------|------|
| `mod/.../VoxyFrame.java` | the frame: calls Voxy, blends its result in, shared image sets |
| `mod/.../GlContext.java` | the hidden OpenGL context |
| `mod/.../Interop.java` | images and semaphores shared between Vulkan and OpenGL |
| `mod/.../AtlasReader.java` | rebuilds the block atlas from the sprites' images for Voxy |
| `mod/.../VirisMixinPlugin.java` | switches off Voxy's OpenGL-only hooks on Vulkan |
| `mod/.../pack/` | shader packs: the pack's Voxy patch, Voxy's pack pipeline, uniforms, depth conversion |
| `mod/.../mixin/` | Vulkan device features, texture export, main pass, graphics API choice, Voxy, Sodium and Vitrail hooks |

## Known issues

* Shader packs: no shadows on LODs yet (the pack's shadow maps aren't passed in), packs that render into vanilla depth
  aren't supported, packs without Voxy support show no LODs.
* Known Shader packs: Complementary show visual glitches. See issues.
* Thin dark lines can show over LOD water. They also show when the game runs on OpenGL, so the interop isn't the cause.
* Only Windows.

## License

This mod: [MIT](LICENSE). Voxy is All-Rights-Reserved: VIRIS contains none of its code, is only built against it and
hooks into the jar you install at runtime. Vitrail (LGPL-3.0), Sodium, Voxy and shader packs are not included; get them
from their authors.
