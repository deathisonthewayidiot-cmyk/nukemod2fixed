# Nuke (Fabric, Minecraft Java 26.3)

Adds one block: the **Nuke**. TNT's much worse cousin.

* **Arm it** with flint and steel, a fire charge, or a redstone signal.
* **10 second countdown** (the block glows red), then it detonates.
* **Perfect spherical crater**, radius **48** (vanilla TNT is about 4). An expanding
  shockwave digs it out shell by shell over about a second, so it does not freeze the server
  in one tick. Bedrock and other unbreakable blocks survive; block contents are vaporised.
* **Nuke effects**: initial fireball, an expanding shockwave (ground ring plus dome), and a
  mushroom cloud that rises for 12 seconds. All vanilla particles, visible from up to 512
  blocks away.
* Entities within roughly 96 blocks are damaged and launched by a vanilla explosion
  that does no block damage.
* **Craft it**: 4 TNT (corners) + 4 gunpowder (edges) + 1 nether star (centre).
  Also in the creative inventory, Redstone Blocks tab.
* Respects the `tntExplodes` game rule.

Tune it at the top of `src/main/java/com/nukemod/NukeBlast.java`: `CRATER_RADIUS`,
`SHOCKWAVE_SPEED`, `DAMAGE_POWER`, `CLOUD_TICKS`, `CLOUD_HEIGHT`, `CAP_RADIUS` and the
`*_PUFFS_PER_TICK` particle counts (lower these if clients lag). `FUSE_TICKS` is in `NukeBlock.java`.
A radius of 48 is big (about 460,000 blocks). If a server stutters, lower `CRATER_RADIUS`.
Nuking at sea will flood the crater with vanilla water physics, which can also lag.

## Getting the jar

### Option A: no setup, let GitHub build it
1. Create a new GitHub repo and push this folder to it.
2. Open the **Actions** tab, wait for the `build` run to finish.
3. Download the **Artifacts** zip. Use `nuke-1.1.0.jar` (not the `-sources` jar).

### Option B: build locally
Requires **JDK 25** and an internet connection.

    ./gradlew build          # Windows: gradlew.bat build

The jar is written to `build/libs/nuke-1.1.0.jar`.

## Installing
1. Install Fabric Loader 0.20.1 or newer for Minecraft 26.3.
2. Put these in your `mods` folder:
   * `nuke-1.1.0.jar`
   * Fabric API `0.165.0+26.3` (or newer for 26.3)

Also works on a dedicated server, as it has no client-only code.
Players on a server also need the mod (the block needs its textures).

## Status
Written against the official Fabric 26.3 template (Loader 0.20.1, Loom 1.19,
Fabric API 0.165.0+26.3). v1.0.0 compiled cleanly on 26.3. v1.1.0 (spherical crater, effects)
had its Minecraft API calls cross-checked against Fabric API, NeoForge and Paper sources for
26.3 and its crater logic tested against a simulated world, but it has **not yet been compiled
against the real game jars**. If `./gradlew build` reports an error, it will point at a
specific line in `NukeBlast.java`, `NukeBlock.java` or `NukeMod.java`.
