# The monorepo: `combat/` + `mind/` + `shared/`

Two subprojects live here now, and only one of them is where it belongs. `mind/` arrived as a proper subdirectory; the
combat mod is still spread across the repository root. This is the layout that was intended, why it was not done the
night the mind landed, and the checklist to finish it when the machine is free.

## The intended layout

```
combat/     mod/  trainer/  viewer/  scripts/  models/  runs/  docs/
mind/       dwarfsim/  text/  models/  tools/  tests/  docs/
shared/     the weight-file format, the schema-id rule, the parity procedure
README.md   CLAUDE.md   LICENSE
```

Each subproject owns its own `models/`, its own `docs/` and its own way of being run, and neither reaches into the
other. `shared/` is the small third thing the port will need: `.mbw` is one format with one header and one schema-id
rule, and both sides already have to agree on it byte for byte — the mind's
[port brief](../mind/docs/port.md) proposes a `kind` word in the header and two new topologies, which is exactly the
kind of decision that wants one home rather than two copies drifting apart. Nothing goes in `shared/` until both sides
genuinely use it.

## Why it was not done tonight

A training run was live in the main checkout and had been for hours. Moving the combat tree is not a rename that a
running process survives:

- **The live run holds absolute paths** under `mod/`, `trainer/`, `runs/` and `models/` — rollout and checkpoint
  directories, the terrain library, the worker game-test servers and the `runTraining` supervisor that restarts them.
  A `git mv` under a live run does not move what the run already has open; it just makes the run and the tree disagree.
- **Every script resolves the root from its own location.** `scripts/_common.ps1` does
  `$Root = Split-Path -Parent $PSScriptRoot`, and `$Mod`, `$Python`, `$Runs` and `$Tools` hang off that. That one line
  keeps working when the whole tree moves as a unit — but only that one.
- **The build reads `models/` at the root.** `mod/buildSrc/src/main/groovy/multiloader-loader.gradle` takes
  `repositoryRoot` as `rootProject.projectDir.parentFile` (`mod/`'s parent) and reads `new File(..., 'models')` from it
  to pack networks into the jar. Under `combat/` that resolves to `combat/models`, which is right — again, only if
  everything moves together.
- **The crowd worktree borrows through `_common.ps1` and `mainCheckout`, and both find the main checkout through
  `.git`.** This is the part that actually breaks. A worktree has no `trainer\.venv` and no terrain library of its own
  and must never link one in; instead `_common.ps1` asks git for `--git-common-dir` and joins `trainer\.venv` onto its
  parent, and the build's `mainCheckout` reads the `.git` **file** at `repositoryRoot` and walks its `gitdir:` line
  back to the main checkout. Move the combat tree into `combat/` and `repositoryRoot` becomes `combat/`, where there is
  no `.git` at all: `mainCheckout()` returns null, the worktree stops borrowing, and the borrowed venv path loses its
  `combat\` segment. A worktree that silently stops borrowing is the failure mode that already cost this machine its
  trainer environment once — see the worktree rule in [CLAUDE.md](../CLAUDE.md) and [findings.md](findings.md).

So the move is cheap in git and not cheap in running state. It wants the run stopped at a round boundary, which is a
deliberate act, not something to do while a run is mid-round at two in the morning.

## The checklist, for when the run is stopped

Do it in a worktree first, on a branch, with every run on the machine stopped at a round boundary (`scripts\stop.ps1`,
and check `scripts\watch.ps1` is empty). Never `gradlew --stop`.

1. **Move the combat tree as one unit**, in one commit, so history follows:
   `git mv mod trainer viewer scripts models docs combat/`. `runs/` is not in git; move it on disk in the same step, or
   let it regenerate. Keep `README.md`, `CLAUDE.md`, `LICENSE`, `.gitignore`, `.gitattributes` and `mind/` at the root.
2. **`combat/scripts/_common.ps1`.** `$Root` still resolves correctly (the scripts folder's parent is now `combat/`),
   but the borrowing block does not: the main checkout found from `--git-common-dir` is the repository root, so the
   borrowed paths become `Join-Path $main 'combat\trainer\.venv\Scripts\python.exe'` and the terrain library likewise.
   Fix that one join, and grep the folder for any other path built from `$Root` that assumed the old depth.
3. **The build's models path and `mainCheckout`.** In `combat/mod/buildSrc/src/main/groovy/multiloader-loader.gradle`,
   `repositoryRoot` is now the *subproject* root, which is what the `models/`, `runs/` and `trainer/` joins want — so
   leave those alone and give `mainCheckout` its own anchor: read `.git` from `repositoryRoot.parentFile`, and join
   `combat/` back on when composing the borrowed venv and terrain paths. Test it from a worktree, because the main
   checkout has a `.git` directory and will pass either way.
4. **Paths in `CLAUDE.md` and `README.md`.** Every `scripts\*.ps1`, `mod/common`, `models\blast-8\best.mbw` and
   `docs/*.md` reference gains a `combat/`. The mind section already points at `mind/` and does not move.
   `docs/README.md` and the doc cross-links move with the docs.
5. **Test from a worktree**, never from the checkout a run will use: `combat\scripts\test.ps1` (expect 20/20),
   `-Mechanics` (expect 70 passed), and `-Play` (expect 29 passed). The last one proves the jar still found `models/`.
   Then `combat\scripts\eval.ps1 -Weights combat\models\blast-8\best.mbw` on a small count to prove a network still
   loads by path.
6. **Restart the run** from the main checkout and watch one full round land in `watch.ps1` before walking away.

`mind/` needs nothing from any of this: it is pure Python, it resolves its data relative to its own files, and it does
not care what sits beside it.
