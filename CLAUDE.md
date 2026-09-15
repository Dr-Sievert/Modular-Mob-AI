# Modular Mob AI: notes for Claude

One mob, two halves, and a small third thing they share. This file is the map and the rules that bind both halves; each
half's own notes are its own file, and that is where the work is.

```
combat/     the Minecraft mod, the trainer, the fights          combat/CLAUDE.md
mind/       the pure-Python behaviour testbed                   mind/README.md
shared/     the frozen, port-ready models both halves read      shared/README.md
docs/       how this layout came to be                          docs/monorepo.md
```

| Where | What it is | Read |
| --- | --- | --- |
| [`combat/`](combat/CLAUDE.md) | A neural-network brain for Minecraft mobs: `mod/` (MultiLoader, 1.21.1, Java 21), `trainer/` (PyTorch PPO), `viewer/`, `scripts/`, `models/`, `docs/`. Java, Gradle and Minecraft. | [combat/CLAUDE.md](combat/CLAUDE.md), then [combat/docs/README.md](combat/docs/README.md) |
| [`mind/`](mind/README.md) | Everything the fight is not: emotional and social state, episodic memory, goals, obligations, and one arbitrator choosing among skills. Pure Python, no Java, no Gradle, no Minecraft. | [mind/README.md](mind/README.md), then [mind/docs/plan.md](mind/docs/plan.md) and [mind/docs/design.md](mind/docs/design.md) |
| [`shared/`](shared/README.md) | The two frozen models the port carries across: weights, layouts, schema ids and parity fixtures. Written by `mind/tools/freeze.py`, read by both sides. | [shared/README.md](shared/README.md), then [mind/docs/port.md](mind/docs/port.md) |

Neither half reaches into the other. `shared/` is the single exception, and only `mind/tools/freeze.py` writes there.

## Hard rules about this machine

These are not style. Each one is here because breaking it already cost hours of somebody else's work on this machine,
and none of them belongs to one half.

- **A training run may be live, and another session may be supervising it.** Never modify, build or run anything in a
  checkout a run is training from, and never touch its `combat\runs\`. `combat\scripts\watch.ps1` shows every run on the
  machine; `combat\scripts\stop.ps1` stops them at a round boundary, which is a deliberate act, not a cleanup step.
- **Development happens in a git worktree**, never in the checkout a run uses. A Gradle build in that checkout breaks the
  run.
- **A worktree borrows the machine's trainer environment and terrain library; never link them in, and never mirror-delete
  a worktree.** A fresh worktree has no `combat\trainer\.venv` and no `combat\runs\terrain\...\library`, and both are
  found in the main checkout automatically (`combat\scripts\_common.ps1`, and `mainCheckout` in the build, which reads
  the `.git` at the repository root and joins `combat\` back on). Linking them was done by hand in four worktrees at once
  and cost both: `git worktree remove` refuses on Gradle's deep paths ("Filename too long"), and the usual answers —
  `robocopy /MIR` from an empty folder, `Remove-Item -Recurse` — **follow a junction** and mirrored the deletion through
  it. Torch, numpy and `pyvenv.cfg` went in seconds, and half an hour of generated ground with them. If a worktree must
  be deleted by hand, `robocopy /XJ` or `rmdir /s` do not follow junctions.
- **Never `gradlew --stop` while a run is training.** Gradle daemons are shared by every worktree on the machine, and a
  live run's supervisor (`runTraining`: the memory floor, worker restarts, the round loop) is one of them. A `--stop`
  from a worktree at 08:25 on 2026-09-14 took blast7's supervisor down mid-run; the trainer and its workers carried on
  orphaned until they were stopped and restarted by hand. A worktree that wants its own daemon uses `--no-daemon` on its
  own call.
- **Single process, single thread, in `mind/`.** The machine is shared with the live run: `torch.set_num_threads(1)` is
  at the top of every test module there that imports torch, and nothing in that half should ever raise it or run workers.
- **`runs/` is not in git, in either half**, and neither are `combat/trainer/.venv`, `combat/.tools/` or anything Gradle
  builds. Trained combat networks that matter are published into `combat/models/`; the mind's frozen models are in
  `shared/models/`.
- `.mbw`, `.mbr`, `.pt`, `.npz`, `.npy` and `.nbt` are **binary** in `.gitattributes` at this root, whose default is
  `* text eol=lf` — which silently corrupts a binary file that isn't listed. A new binary kind means a new line there.

## One command per half

```
cd combat    scripts\test.ps1              20 arena fights: expect 20/20
cd mind      python -m pytest -q tests     expect 214 passed, 2 skipped
```

Everything else each half has is in its own notes: [combat/CLAUDE.md](combat/CLAUDE.md) and
[mind/README.md](mind/README.md).
