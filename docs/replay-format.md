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
  "reward": []
}
```

## Viewer conventions

- North up (-z), east right (+x), like a Minecraft map. Screen x = world x, screen y = world z.
- Agent green, opponent red.
- Continuous actions run from -1 to 1. Buttons count as held at >= 0.5. `aimYaw`/`aimPitch` are the turn this tick as a fraction of 60 degrees.
- Readers ignore unknown fields and handle a missing `reward`, `biome` or `iteration`.
