# Fight replay format, version 2

One JSON file per recorded fight, UTF-8, no gzip. Written by the game (Java), read by `viewer/replay.html`.

Version 2 records the site block by block, in `blocks`, instead of version 1's top-down `terrain` of one height and one
colour per column. Readers need `blocks`; the viewer no longer draws version 1 replays, and lists them only to be
deleted.

## Where

- Directory: `runs/<name>/replays/`. Training runs use their run name; one-off tests and evaluations use a name of their own under `runs/`, and the script that ran them prints where it went.
- File name: `w<worker, 2 digits>-f<fight number on that worker, 6 digits>.json`, e.g. `w01-f000400.json`.
- A worker numbers its fights on from the last replay it already left in the directory, rounded up to a multiple of N (below). A training run starts fresh worker processes every round and can be resumed, so without this each round would write over the last one's replays.
- A file is written whole under a temporary name and renamed at the end, so a reader never sees half of one.

## Which fights

System properties on the game process:

- `modular_mob_ai.replays` is the directory. Empty or unset turns recording off.
- `modular_mob_ai.replays.every` = N (>= 1) records fight numbers 0, N, 2N, … of each worker. Default 1 when the directory is set.

## Contents

```jsonc
{
  "format": "mmai-replay",
  "version": 2,

  "run": "imitate",            // training run name, or null
  "iteration": 42,             // iteration of the weights driving the agent when the fight started, or null
  "brain": "training",         // "scripted", "neural", "training", …, whatever describes what drove the agent
  "worker": 1,
  "fight": 400,
  "biome": "minecraft:plains", // biome at the site's centre, or null
  "outcome": "win",            // "win" | "loss" | "timeout" | "draw" (league only: the opponent went unkilled, a creeper
                               // that blew itself up)
  "ticks": 187,                // T: frames recorded, one per server tick of the fight
  "tickRate": 20,

  // The site's blocks as they were when the fight started, see "Blocks" below.
  "blocks": { "x": -1040, "y": 58, "z": 2080, "width": 80, "height": 41, "depth": 80,
              "palette": [], "color": [], "flags": [], "runs": [] },

  // Index 0 is always the agent, index 1 its opponent. More entries may follow later (more opponents).
  "entities": [
    { "role": "agent",    "type": "modular_mob_ai:training_agent", "width": 0.6, "height": 1.8,  "maxHealth": 20.0 },
    { "role": "opponent", "type": "minecraft:vindicator",          "width": 0.6, "height": 1.95, "maxHealth": 24.0 }
  ],

  // One entry per entity, same order as "entities". Every array has exactly T values.
  "frames": [
    {
      "x": [], "y": [], "z": [],   // feet position, 2 decimals
      "yaw": [],                   // degrees, Minecraft convention: 0 faces +z (south), 90 faces -x (west),
                                   // 180 faces -z (north), -90 faces +x (east). Look direction on the map: (-sin yaw, cos yaw)
      "pitch": [],                 // degrees, positive looks down
      "health": [],                // 1 decimal; 0 once dead
      "swing": [],                 // 1 on a tick where it started an attack swing, else 0
      "hurt": []                   // 1 on a tick where it took damage, else 0
    }
  ],

  // What the agent's brain asked for on each tick, as the 11 action values, before the game applied its limits.
  "actions": {
    "names": ["moveForward", "moveStrafe", "aimYaw", "aimPitch", "jump", "sprint", "sneak",
              "attack", "use", "useOffhand", "selectedSlot"],
    "values": [/* T arrays of 11 numbers, 3 decimals */]
  },

  // Optional: the reward the agent earned on each tick, T numbers. Absent when not known.
  "reward": [],

  // Optional: every projectile seen in flight, see "Projectiles" below. Absent when none were seen.
  "projectiles": []
}
```

## Blocks

Every block of a box around the fight, read once as the fight starts: the site's whole horizontal extent (the 80 by 80
blocks of a terrain fight, the box and its walls in the closed arena), from a few layers under the lowest ground of the
site up to the highest block standing on it. Downwards it goes at most as far as the fighters may perceive, 16 blocks
under the lower of them, so a pit or a pond bed is kept; upwards up to 32 blocks past what they may perceive, so a tall
tree or a cliff is kept whole. Under a roof, as in the closed arena, it stops below the roof.

```jsonc
"blocks": {
  "x": -1040, "y": 58, "z": 2080,        // world block coordinates of cell (0, 0, 0), the lowest north-west corner
  "width": 80, "height": 41, "depth": 80, // W cells along +x (east), H up (+y), D along +z (south)

  // Every block state the box holds, index 0 always "minecraft:air" (all kinds of air). The id, then in brackets only
  // the properties that change how the block looks or its shape, in the game's order: axis, facing, half, type, shape,
  // layers, waterlogged, age, lit, snowy, level, the connections north/east/south/west/up/down, and a few more.
  // Properties that do not show, like a leaf's distance from a log, are left out, so such states share an entry.
  "palette": ["minecraft:air", "minecraft:stone", "minecraft:oak_log[axis=y]", "minecraft:grass_block[snowy=false]",
              "minecraft:water[level=0]", "minecraft:vine[east=false,north=true,south=false,up=false,west=false]"],

  // Per palette entry: its map colour, 0xRRGGBB (BlockState#getMapColor(level, pos).col; 0 for none), the colour to
  // draw it in without Minecraft's textures.
  "color": [0, 7368816, 9402184, 8368696, 4210943, 31744],

  // Per palette entry, bits: 1 = a full opaque cube, which hides the faces of blocks next to it
  // (BlockState#isSolidRender); 2 = it stops movement (BlockState#blocksMotion: ground, leaves, logs, not grass,
  // flowers or snow layers); 4 = it holds a fluid (water, lava, anything waterlogged, kelp, seagrass).
  // A column's top block with 2 or 4 set is what the game's MOTION_BLOCKING heightmap gives.
  "flags": [0, 3, 3, 3, 4, 0],

  // The cells as palette indices, run-length encoded as pairs: index, count, index, count, …, adding up to W*H*D.
  // Cells go layer by layer from the bottom; in a layer, row by row from north to south; in a row, from west to east.
  // Cell (dx, dy, dz) is number (dy * D + dz) * W + dx.
  "runs": [1, 12800, 3, 4, 0, 2, 2, 1, 0, 76 /* , … */]
}
```

Rules:
- The box is read from chunks that are already loaded; recording never loads or generates one. A site's chunks always
  are. A chunk that is not stays air.
- Every block that is not air is in the palette, including invisible ones such as barriers; a reader decides what to
  draw.
- Sizes, measured over 700 terrain fights in 20 biomes: an 80 by 80 site is 25 to 45 layers high (up to 75 on a
  cliff), with 11,000 to 21,000 runs and 15 to 40 palette entries. That is 50 to 100 kB of `blocks`, up to 180 kB in a
  jungle, whose canopy breaks the runs up, and 60 to 110 kB for a whole replay. A fight that runs its full minute adds
  about 150 kB of frames and actions, so the largest replays, jungle fights that timed out, come to 300 kB.

## Projectiles

An addition to version 1; readers must accept files without it.

A new top-level array. It is written only when the fight had projectiles (arrows, tridents, snowballs, fireballs and so
on: every `net.minecraft.world.entity.projectile.Projectile`). A file without it means none were seen.

```jsonc
"projectiles": [
  {
    "type": "minecraft:arrow",   // entity type id
    "owner": 1,                  // index into "entities" of whoever fired it; -1 for anyone else, or nobody
    "start": 37,                 // the fight tick of its first frame
    "x": [], "y": [], "z": [],   // one value per tick from "start" while it is in flight, the same precision as the fighters
    "end": {                     // how its flight ended; absent if the fight ended first or it left the recorded area
      "tick": 52,                // the fight tick it ended on
      "hit": 0,                  // index into "entities" of the fighter it struck; -1 for a block, or it just vanished
      "x": 12.34, "y": 70.1, "z": -5.6   // where it ended
    }
  }
]
```

Rules:
- Tracking starts on the first tick the projectile is inside the recorded area. It stops once it hits something,
  comes to rest in a block (an arrow stuck in the ground counts as ended, `hit: -1`), is removed, or leaves the area. A
  resting arrow is not tracked tick after tick for the minute it lingers.
- `x`/`y`/`z` are the projectile's position on each tick, with exactly `end.tick - start + 1` values when it ended
  (the last value is where it ended), or `ticks - start` values when the fight ended first.
- A hit on a fighter is also a `hurt` tick in that fighter's frames, as it already is.

## Viewer conventions

- North up (-z), east right (+x), like a Minecraft map. Screen x = world x, screen y = world z.
- Agent green, opponent red.
- Continuous actions run from -1 to 1. Buttons count as held at >= 0.5. `aimYaw`/`aimPitch` are the turn this tick as a fraction of 60 degrees.
- Readers ignore unknown fields and handle a missing `reward`, `biome`, `iteration` or `projectiles`.
