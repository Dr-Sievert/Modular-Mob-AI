# Playing with agents

The **agent** is also a mob you can meet in a real game: `modular_mob_ai:agent_mob`, called "Agent". It has the same
body, observation and controls as the training agent, so a trained network drives it unchanged. Unlike the training
agent it can be summoned, it is saved with the world and it never despawns. The training agent,
`modular_mob_ai:training_agent`, stays arena-only: never saved, never summonable, and it ignores everything on this page
except sides.

Everything below runs from the repository root in Windows PowerShell once `scripts\setup.ps1` has run.

## Start the game with a model

```
scripts\play.ps1                          Fabric dev client, agents on the best network in models\
scripts\play.ps1 -Model blast             a published network by name, by its folder under models\
scripts\play.ps1 -Weights runs\blast\weights\002000.mbw       any weight file, such as a local checkpoint
scripts\play.ps1 -Scripted                the hand-written fighter instead
scripts\play.ps1 -Loader neoforge         the NeoForge client
scripts\play.ps1 -World arena             straight into the saved world 'arena' (its folder under saves\), no menus
```

- **The best network** is the one whose `models\<name>\model.json` records the highest win rate (more fights breaks a
  tie). Today that is `blast4`; see [models.md](models.md) for everything published.
- It builds the mod in this checkout and starts `:<loader>:runClient` with
  `-Pbrain=neural -PbrainWeights=<file> -Pmodels=<repo>\models`. Those become `-Dmodular_mob_ai.brain`,
  `-Dmodular_mob_ai.brain.weights` and `-Dmodular_mob_ai.models` in the game.
- The client's game directory is `mod\fabric\runs\client` (NeoForge: `mod\neoforge\run`). It needs about 3 GB of memory.
- Create a world (creative is fine: agents ignore creative and spectator players), then `/mmai spawn`.

**Check the network loaded.** As soon as a world opens, `logs\latest.log` in the game directory says:

```
Loaded C:\...\models\blast4\best.mbw from iteration 8825: 792 -> 792 -> 256 -> GRU 128 -> 128 -> 19 (371,783 parameters)
humanoid agents with no brain of their own run on best.mbw from C:\...\models\blast4, iteration 8825
```

In the game, `/mmai info` shows what each agent runs on, and `/mmai models` shows every network the game can find.

## Outside the development environment

A mod jar built from this repository carries its networks with it. Nothing has to point it at a file.

```
mod\gradlew.bat -p mod :fabric:build       mod\fabric\build\libs\modular_mob_ai-fabric-1.21.1-1.0.0.jar (needs Fabric API)
mod\gradlew.bat -p mod :neoforge:build     mod\neoforge\build\libs\modular_mob_ai-neoforge-1.21.1-1.0.0.jar
```

- **Bundled networks.** The build (`bundleModels` in `mod\buildSrc\...\multiloader-loader.gradle`) copies every
  `models\<name>\best.mbw` into the jar as `modular_mob_ai/models/<name>.mbw`, beside `models.properties`, an index
  naming them, the best one, and each one's win rate. About 1.3 MB each. `best` means the best of them, picked the same
  way `play.ps1` picks. Publish a network (`scripts\publish.ps1`) and build again to ship it.
- **The config file**, `config/modular_mob_ai.properties`, is written with comments on the first start:

  | Key | Default | What it does |
  | --- | --- | --- |
  | `brain` | `best` | what drives an agent that has no brain of its own: `best`, `scripted`, a network's name, or a `.mbw` path |
  | `loadout` | `sword` | what an agent spawned from an egg or a bare `/summon` carries |
  | `models` | `modular_mob_ai/models` | a folder searched for networks by name before the jar; relative to the game directory |

  Put more networks in that folder as `<name>/best.mbw` (a `models\<name>` folder from the repository copies in as it
  is) or `<name>.mbw`; `/mmai brain @e vs-new` finds them without a rebuild.
- A dedicated server reads the same file from its own `config` folder. The commands need operator level 2.
- The `-D` properties of a development run win over the file: `-Dmodular_mob_ai.brain` (anything `/mmai brain` takes, or
  `neural` with `-Dmodular_mob_ai.brain.weights=<file>`) and `-Dmodular_mob_ai.models=<folder>`.

## Spawning agents

| How | Carries | Brain |
| --- | --- | --- |
| `/mmai spawn [loadout] [brain] [pos]` | the loadout named, else the config's | the brain named, else the default |
| Agent Spawn Egg (Spawn Eggs creative tab) | the config's loadout | the default |
| `/summon modular_mob_ai:agent_mob ~ ~ ~` | the config's loadout | the default |
| `/summon modular_mob_ai:agent_mob ~ ~ ~ {Loadout:"bow_infinity",BrainName:"blast"}` | the loadout named | the brain named |

```
/mmai spawn                                  a sword, the default brain, where you stand, facing where you face
/mmai spawn bow_infinity                     an archer that never runs out
/mmai spawn sword_and_shield blast           a network by name
/mmai spawn axe_and_shield scripted 10 64 -5     the hand-written fighter, at a position
/mmai spawn sword "C:/nets/test.mbw"         a weight file; quote anything with a slash or colon
```

What an agent does in the world:
- **It fights** monsters, players in survival or adventure mode, other agents, and anything that targets it, within 32
  blocks. It ignores animals, villagers, and creative or spectator players. Sides change this; see below.
- **With nobody in view it stands still** (swimming up in water), whatever its brain says, and meets the next opponent
  with a fresh memory, the way every training fight started. A network was never trained with nobody there.
- **It is saved with the world.** It keeps its hotbar as it stands (arrows spent, shield worn), its brain's name
  (`BrainName`) and its loadout's name (`Loadout`). A saved brain the game can't find (a network another install had)
  falls back to the default with one warning in the log, and comes back once the network is there.
- **It never despawns**, and doesn't count towards the mob cap.
- Mobs don't go after agents on their own unless they're on opposing sides. An agent that hits one gets hit back.

## Loadouts

`/mmai loadout <targets> <loadout>` arms agents (the whole hotbar) and any vanilla mob (main hand and off hand). Players
are skipped. `/mmai loadouts` lists them.

| Name | Hotbar | Off hand | Use |
| --- | --- | --- | --- |
| `sword` | iron sword | | what the networks are trained with |
| `sword_and_shield` | iron sword | shield | |
| `axe_and_shield` | iron axe | shield | the axe knocks shields aside |
| `bow` | bow, 64 arrows | | training: finite, one arrow a shot |
| `crossbow` | crossbow, 64 arrows | | training: finite |
| `bow_infinity` | Infinity bow, 1 arrow | | real play: never runs out |
| `crossbow_infinity` | Infinity crossbow, 1 arrow | | real play: never runs out |

```
/mmai loadout @e[type=modular_mob_ai:agent_mob] bow_infinity
/mmai loadout @e[type=minecraft:zombie,limit=1,sort=nearest] axe_and_shield
```

**Why Infinity for real play.** The training loadouts carry 64 arrows and never pick one back up. That lasts a 60-second
fight, since a full-draw shot takes 20 ticks, and training keeps them. In a real game an agent would run dry while
skeletons, whose arrows vanilla conjures, keep shooting. Of the ways to stay armed, Infinity was chosen because:
- it's vanilla's own rule, applied by the item code the agent already fires through, so nothing new was added to the
  body, and the arrows it looses can only be picked up by a creative player, as a player's Infinity arrows;
- the observation sees item kinds, not counts or enchantments, so an Infinity bow looks exactly like the trained one;
- refilling "between fights" needs a between, which a real game doesn't have, and picking arrows up needs the agent to
  walk over to them, which it was never trained to do.

Infinity on a crossbow isn't something an enchanting table gives, but it works for anyone holding one.

## Brains

`/mmai brain <targets> <brain>` gives agents a brain of their own, kept through saving. Every agent on the same network
shares one forward pass per tick, however the network was named.

| Brain | Means |
| --- | --- |
| `default` | no brain of its own: follow the game's default (below) |
| `scripted` | the hand-written fighter (`brain/ScriptedBrain.java`) |
| `best` | the best network the jar carries |
| `<name>` | a network by name: `<models folder>/<name>/best.mbw` or `<name>.mbw`, else the one the jar carries |
| `<file>.mbw` | a weight file, absolute or relative to the game directory; quote it |

An agent with no brain of its own runs on:
1. `-Dmodular_mob_ai.brain` (and `.weights`) when the game was started with it, which is what `play.ps1` does;
2. otherwise the config's `brain`, `best` by default;
3. the scripted fighter if that can't be loaded, with an error in the log.

The training agent never reads the config: it follows `-Dmodular_mob_ai.brain`, scripted when unset, as it always has.

```
/mmai brain @e[type=modular_mob_ai:agent_mob] blast
/mmai brain @e[type=modular_mob_ai:agent_mob,limit=1,sort=nearest] scripted
/mmai brain @e[type=modular_mob_ai:agent_mob] default
/mmai models
```

A network only loads if it was trained on this game's observation and action layout; anything else is refused with the
reason.

## Allies and enemies

Sides are **vanilla scoreboard teams**. `/team add`, `/team join` and `/team leave` work on them, and vanilla's
targeting already never picks an ally. The mod adds what vanilla lacks:
- **The agent's enemies follow sides.** It never counts an ally as an enemy, whatever it is (a zombie on its team is a
  friend), and it always counts a member of an opposing team as one, whatever it is (a cow on the other side is a
  target). Otherwise it fights what it always fought. This decides which entities take the ten enemy slots of the
  observation, so an ally next to the agent isn't in its view at all, and an ally that becomes an enemy takes a slot on
  the next tick. The layout doesn't change.
- **Vanilla mobs on a team go after members of other teams**, whatever they are. Vanilla would only send them after
  players, villagers and golems. A goal added to every pathfinding mob (`allegiance/OtherTeamTargetGoal`) does this, and
  does nothing while the mob has no team. The mob's own attack goals do the fighting, so a cow on a team picks a target
  and does nothing about it.
- **Friendly fire** off keeps an agent and its side from hurting each other, as vanilla does between players. It covers
  swings, sweeps, arrows and bolts.

| Command | Does |
| --- | --- |
| `/mmai ally <targets> <others>` | puts everyone on one team: the first existing team among them, else a new one |
| `/mmai enemy <targets> <others>` | puts the first group on one team and the second on another |
| `/team leave <targets>` | takes them off their team: back to the default rules |
| `/team remove <team>` | removes a team and every membership in it |

Teams the mod makes are named `mmai_1`, `mmai_2` and so on, each with its own colour (name tags and glow) and friendly
fire off. A player's own team is kept: allying an agent with a player on team `red` adds the agent to `red`.

```
/mmai ally @s @e[type=modular_mob_ai:agent_mob,limit=1,sort=nearest]      a bodyguard
/mmai ally @e[type=modular_mob_ai:agent_mob] @e[type=minecraft:zombie]    agents and zombies on one side
/mmai enemy @e[type=modular_mob_ai:agent_mob,limit=2,sort=nearest] @e[type=minecraft:vindicator,limit=1,sort=nearest]   2v1
/mmai enemy @e[type=minecraft:zombie] @e[type=minecraft:skeleton]        mobs against mobs
/effect give @e[team=mmai_1] minecraft:glowing 60                        see a side through walls
```

Limits:
- Mobs that vanilla drives with its newer Brain system rather than goals (piglins, hoglins, wardens, villagers, goats,
  axolotls and a few others) keep their own targeting.
- Teams are saved with the world. Remove the ones you're done with.
- A team is one side. Two teams are always opposed; vanilla has no alliances between teams.

## Every command

All need operator level 2, like `/summon`.

| Command | Does |
| --- | --- |
| `/mmai spawn [loadout] [brain] [pos]` | spawns an agent, facing the way you face |
| `/mmai loadout <targets> <loadout>` | arms agents and mobs |
| `/mmai brain <targets> <brain>` | gives agents a brain of their own, or `default` |
| `/mmai ally <targets> <others>` | puts them all on one side |
| `/mmai enemy <targets> <others>` | sets the two groups against each other |
| `/mmai info [targets]` | brain, loadout, health and team of each; every agent in the dimension without targets |
| `/mmai models` | every brain by name, the jar's networks, the folders searched, and what the default runs on |
| `/mmai loadouts` | every loadout by name |

## From code

For game tests and the arenas (the league's 2v1s), everything above is plain Java:

```java
AgentMob agent = ModEntities.agentMob().create(level);        // or trainingAgent() in an arena
agent.equip(Loadout.SWORD);                                    // Loadouts.byName("bow_infinity", level.registryAccess())
agent.setBrainName("blast");                                   // null for the default; throws for a name that leads nowhere

PlayerTeam red = Allegiance.side(agentA, agentB);             // a new team with just these on it
PlayerTeam blue = Allegiance.side(vindicator);
Allegiance.isEnemy(agentA, vindicator);                        // the agent's rule: true
Allegiance.allied(agentA, agentB);                             // vanilla's rule, both ways round: true
...
Allegiance.disband(red);                                       // teams outlive the fight; take them down
Allegiance.disband(blue);
```

`Allegiance` also has `join`, `leave`, `ally`, `enemy`, `opposed` and `newTeam`. `Brains.named(name)` resolves any brain
name, and `Models` lists the networks the jar and the folders have.

## Tests

`scripts\test.ps1 -Play` runs the play suite (`gametest/tests/PlayGameTest`), one test at a time so no agent sees into
the next test's box. See [testing.md](testing.md).

## Where it lives

| File | What |
| --- | --- |
| `scripts\play.ps1` | the dev client on a network |
| `Config.java` | the config file |
| `brain/Brains.java` | brains by name, the default for each agent, the start-up log line |
| `brain/Models.java` | networks by name: the jar and the folders |
| `arena/Loadouts.java` | loadouts by name, and the Infinity ones |
| `allegiance/Allegiance.java` | sides: the agent's enemy rule, friendly fire, making and removing teams |
| `allegiance/OtherTeamTargetGoal.java`, `mixin/MobMixin.java` | vanilla mobs going after other teams |
| `mixin/LivingEntityMixin.java` | friendly fire for agents |
| `command/AgentCommands.java` | `/mmai` |
| `entity/agent/AgentMob.java` | brain and loadout names, saving, spawn arming, persistence, standing still when alone |
