# Fight replay format, version 1

One JSON file per recorded fight, UTF-8, no gzip. Written by the game (Java), read by `viewer/replay.html`.

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
  "version": 1,

  "run": "imitate",            // training run name, or null
  "iteration": 42,             // iteration of the weights driving the agent when the fight started, or null
  "brain": "training",         // "scripted", "neural", "training", …, whatever describes what drove the agent
  "worker": 1,
  "fight": 400,
  "biome": "minecraft:plains", // biome at the site's centre, or null
  "outcome": "win",            // "win" | "loss" | "timeout"
  "ticks": 187,                // T: frames recorded, one per server tick of the fight
  "tickRate": 20,

  // Top-down terrain covering at least the whole fight site (80x80 blocks for terrain fights).
  "terrain": {
    "x": -1040, "z": 2080,     // world block coordinates of cell (0, 0), the north-west corner
    "width": 80, "depth": 80,  // W cells along +x (east), D cells along +z (south)
    "height": [/* W*D ints */],// y of the topmost non-air block of each column; index = dz * W + dx
                               // (in the closed arena: the topmost one under its roof, so the floor and walls show)
    "color":  [/* W*D ints */] // that block's map colour, 0xRRGGBB (BlockState#getMapColor(level, pos).col); 0 = none
  },

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
