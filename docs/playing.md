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

**If a world comes up dark**, or anything else about a development game does not behave as the built jar does, suspect the
game-test source set: both loaders hand it to the client and server runs as well, so that `/test runall` works in a dev
world, and its mixins are loaded in a real game with it. Everything in `GameTestTuning` is inert outside a game-test server
for that reason — a world with no light was one such setting reaching a client — and any new toggle there has to be too;
see [findings.md](findings.md) and `GameTestTuning.gameTestServer`.

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
  | `pickup` | `true` | whether a new agent takes the items it walks over; each agent then keeps its own answer |

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
- **It perceives like a player, not like a radar.** Something is in its view if it is in front of it — a 100° cone about where it
  is aiming, with a clear line of sight — or within 6 blocks in any direction, which is hearing, or if it is what last hit it. A
  body it stops perceiving stays in its view for 3 seconds at the place it was last seen, and is then forgotten: it remembers
  what walked behind it, and it cannot see through rock. So turning matters, getting behind it works, and a horde on the far side
  of a hill is not in its head. `/mmai info` prints what each agent last spent perceiving — how many bodies it is aware of, how
  many sight checks it made, and how long it took — which is the number to look at in a world with thousands of mobs in it.
- **With nobody in view it stands still** (swimming up in water), whatever its brain says, and meets the next opponent
  with a fresh memory, the way every training fight started. A network was never trained with nobody there.
- **It picks up what it walks over**, into its own hotbar, and a player can right click it to take things back or hand it
  something else; see [What it carries](#what-it-carries).
- **It is saved with the world.** It keeps its hotbar as it stands (arrows spent, shield worn), what is in its pocket,
  whether it picks things up, its brain's name (`BrainName`) and its loadout's name (`Loadout`). A saved brain the game
  can't find (a network another install had) falls back to the default with one warning in the log, and comes back once
  the network is there.
- **It never despawns**, and doesn't count towards the mob cap.
- **Monsters come for it the way they come for a player.** A zombie, a skeleton, a spider, anything vanilla marks hostile,
  goes after an agent it can see within its own follow range, and the agent is a fight from the moment it is spawned — no
  teams, no commands. That is new: vanilla's hostiles look for players, villagers, golems and turtles, so until this an agent
  stood in a field of zombies and every one of them walked past it, and the reported "spawn the agent after the mobs are
  already there and it doesn't work" was exactly that. Animals, villagers and anything that would leave a player alone still
  leave the agent alone; sides override it as they override everything (an ally is never a target). Two limits worth knowing:
  a spider comes for an agent in daylight where it would leave a player alone, and a warden, a piglin and a hoglin have to be
  angered as they always did, because vanilla drives them with a brain rather than with goals.
- A mob the agent hits fights back, and so does **every mob of its own kind within its follow range** — vanilla's own
  `HurtByTargetGoal` alerts its own kind as far as that reaches, tens of blocks for most monsters, so one blow on one zombie
  brings the whole group and none of them needed to see anything. That was the
  answer to "three plain zombies with nothing having touched them all took a nearby agent as their target on one tick", which
  was written down here as unexplained; see [findings.md](findings.md#perception).
- **A crowd in view used to be what a network could not do, and half of that is fixed.** The agent's ten enemy slots now go
  only to what it could actually see: a monster behind rock, across a valley or in the caves below takes none, which is where
  most of a real night's crowd was coming from. Measured in the box, with eleven monsters standing about behind its wall, an
  agent on `best` kills the zombie beside it in 67 ticks where before it died without touching it. What is left is the crowd
  that really is in sight — a night in the open with several monsters around and only one of them interested — and that is a
  curriculum hole a run now trains on rather than something the game can fix; a network published before that training will
  still fight worse in a real crowd than the bench says. See [findings.md](findings.md#perception) and
  [training.md](training.md#a-crowded-view).

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

## What it carries

An agent in a real game has a **hotbar of nine**, which is what it fights from and the only part of it a network sees, and
a **pocket of twenty-seven** behind it. Both are saved with the world.

### Picking things up

An agent walks over a dropped item and takes it, the way a player does. Where it goes, in order:

1. **armour is worn**, and only into a slot that is empty. It will put on a helmet it hasn't got; it won't swap the one
   it's wearing for a better one, and it never takes anything off. A piece it won't wear goes in the hotbar or the pocket
   like anything else. **Armour only** — a shield it walks over goes into the hotbar, not the off hand; put one in the off
   hand through the screen or with a loadout;
2. **into a stack of the same thing**, hotbar before pocket. This is the branch arrows take, and it's why they count: the
   quiver the network reads and the stack a bow fires from are both the hotbar;
3. **the first empty hotbar slot**. A bare-handed agent that walks over a bow can draw it, and the network reads a ranged
   item in that slot on the next tick;
4. **the first empty pocket slot**;
5. and what won't fit is left lying.

**Nothing it is already holding is ever dropped by a pickup.** That is not vanilla's rule — vanilla's `Mob` compares the
new item against the one in the mob's hand and throws the loser on the ground, which for an agent would mean losing the
sword it fights with because it walked over a shovel.

**The pocket is storage.** It is not where a bow looks for arrows, and the observation does not read it: the layout is
fixed and every trained network depends on it, so a pocket the quiver counted would be telling a network about arrows it
can't reach. Move something from the pocket into the hotbar and the agent has it; leave it there and it's luggage.

**A training agent never picks anything up**, whatever the config says. An arena hands out every item in the fight and the
league rates the result under the loadout's name, so a fight that changed its own loadout half way through would be a fight
rated as something it wasn't. It's the same reasoning that leaves a training agent out of `HuntAgentsGoal`. With the flag
off, vanilla's loot scan doesn't run at all, so a training worker pays nothing for any of this.

One vanilla limit worth knowing: **vanilla gates a mob's loot pickup on the `mobGriefing` game rule**. In a world with that
rule off an agent picks nothing up, and `/mmai info` will still say it would.

### The screen

**Right click an agent with an empty hand** and its inventory opens as a screen: its four armour slots and its off hand
along the top, the pickup button under them, its pocket, its hotbar, and your own inventory below, laid out like a chest.
Drag, shift-click and drop as you would in any container — that's how you hand it a bow and how you take back what it
picked up. Anything you put in the slot it's holding is in its hand on the next tick.

The server is the authority for every slot, as it is for a chest, and the screen closes itself if the agent dies or you
walk more than eight blocks away.

**The button reads "Picks up items: on/off"** and flips that agent's own flag. It's the same flag `/mmai pickup` sets and
the one saved with the agent.

**Sneak and right click with an empty hand** and the agent drops whatever is in its main hand at your feet — the quick way
to take one thing back without opening anything. It leaves the drop alone for three seconds afterwards, so it doesn't pick
straight back up what it just handed over.

```
/mmai pickup @e[type=modular_mob_ai:agent_mob] off        none of them takes anything off the floor
/mmai pickup @e[type=modular_mob_ai:agent_mob,limit=1,sort=nearest] on
```

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
- **Monsters go after a playable agent as they go after a player**, on no team at all: a second goal
  (`allegiance/HuntAgentsGoal`) given to every pathfinding mob vanilla marks hostile, with vanilla's own reach, line of sight
  and refusal to pick an ally. An agent in training is deliberately left out of it — an arena hands out its own targets, and a
  goal reaching into that would turn the league's crowded fights into fights with opponents nobody rates. So a fight in a game
  is a fight from the first tick, and every training fight is byte for byte the fight it was.
- **Friendly fire** off keeps an agent and its side from hurting each other, as vanilla does between players. It covers
  swings, sweeps, arrows and bolts.

| Command | Does |
| --- | --- |
| `/mmai ally <targets> <others>` | puts everyone on one team: the first existing team among them, else a new one |
| `/mmai enemy <targets> <others>` | puts the first group on one team and the second on another |
| `/mmai horde <mob> <count> [radius]` | stands `count` of that mob in a ring round you, on the ground, with their own minds, and sets them against every agent within twice the radius — a hundred zombies without writing a hundred `/summon`s. `radius` is 24 by default and the count is capped at 2,000 |
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
  axolotls and a few others) keep their own targeting for everything else, but a **hostile** one does come for an agent: the goal
  writes the attack target its brain actually reads, and the anger a piglin needs to keep one. The **warden** is the exception
  and stays one — it picks what to fight by how angry it is rather than by seeing anything, and its anger drains, so it ignores
  an agent until something wakes it, exactly as it ignores a player standing still.
- Teams are saved with the world. Remove the ones you're done with.
- A team is one side. Two teams are always opposed; vanilla has no alliances between teams.

## Every command

All need operator level 2, like `/summon`.

| Command | Does |
| --- | --- |
| `/mmai spawn [loadout] [brain] [pos]` | spawns an agent, facing the way you face |
| `/mmai loadout <targets> <loadout>` | arms agents and mobs |
| `/mmai brain <targets> <brain>` | gives agents a brain of their own, or `default` |
| `/mmai pickup <targets> on\|off` | whether those agents take the items they walk over; kept through saving, and the same flag the button in an agent's screen flips |
| `/mmai ally <targets> <others>` | puts them all on one side |
| `/mmai enemy <targets> <others>` | sets the two groups against each other |
| `/mmai horde <mob> <count> [radius]` | stands a horde of one mob round you and sets it against every agent near them |
| `/mmai info [targets]` | brain, loadout, whether it picks things up, health, team and last-tick perception cost of each; every agent in the dimension without targets |
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
| `allegiance/HuntAgentsGoal.java` | monsters going after a playable agent as they go after a player |
| `mixin/LivingEntityMixin.java` | friendly fire for agents |
| `command/AgentCommands.java` | `/mmai` |
| `entity/agent/AgentMob.java` | brain and loadout names, saving, spawn arming, persistence, standing still when alone, what a pickup does with what it takes, the right click |
| `entity/agent/AgentInventory.java` | the hotbar, the pocket, the off hand and the armour as one container |
| `menu/AgentMenu.java`, `menu/AgentScreen.java` | the screen: the slots, the button, and what it looks like |
| `menu/ModMenus.java` | the menu type each loader registers |
