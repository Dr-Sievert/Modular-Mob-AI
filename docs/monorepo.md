# The monorepo: `combat/` + `mind/` + `shared/`

**This is the layout now.** The combat tree moved into `combat/` in one commit on 2026-09-15, the frozen models moved
out of `mind/` into `shared/`, and the two paths that the move breaks were fixed with it. What follows is the layout,
then the history of why it was shaped this way and why it waited — which is worth keeping, because the reasons are the
same ones that make the two borrowed paths fragile.

## The layout

```
combat/     mod/  trainer/  viewer/  scripts/  models/  docs/   (and runs/ and .tools/ on disk, untracked)
mind/       dwarfsim/  text/  tools/  tests/  docs/
shared/     models/interpreter/  models/decisions/  models/MANIFEST.json
docs/       monorepo.md (this) and port.md, a pointer at mind/docs/port.md
README.md   CLAUDE.md   LICENSE   .gitignore   .gitattributes
```

Each half owns its own `docs/` and its own way of being run, and neither reaches into the other. `shared/` is the small
third thing the port needs: `.mbw` is one format with one header and one schema-id rule, and both sides have to agree on
it byte for byte — the mind's [port brief](../mind/docs/port.md) proposes a `kind` word in the header and two new
topologies, which is exactly the kind of decision that wants one home rather than two copies drifting apart. The frozen
models with their parity fixtures are the first thing that qualified, and they moved there from `mind/models/`;
`mind/tools/freeze.py` writes them and `mind/tools/check_parity.py` checks them, and that is the only path anything in
`mind/` follows outside `mind/`. Nothing else goes in `shared/` until both sides genuinely use it. See
[shared/README.md](../shared/README.md).

Every combat command is run from `combat/` — `scripts\test.ps1`, not `combat\scripts\test.ps1` — because
`scripts\_common.ps1` takes its root from its own folder's parent and every path hangs off that. From the repository
root the prefixed form works too; the docs are written for `combat/`.

## Why the move waited

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
  `.git`.** This is the part that actually broke. A worktree has no `trainer\.venv` and no terrain library of its own
  and must never link one in; instead `_common.ps1` asks git for `--git-common-dir` and joins `trainer\.venv` onto its
  parent, and the build's `mainCheckout` reads the `.git` **file** at `repositoryRoot` and walks its `gitdir:` line
  back to the main checkout. With the combat tree in `combat/`, `repositoryRoot` is `combat/`, where there is no `.git`
  at all: `mainCheckout()` returns null, the worktree stops borrowing, and the borrowed venv path loses its `combat\`
  segment. A worktree that silently stops borrowing is the failure mode that already cost this machine its trainer
  environment once — see the worktree rule in [CLAUDE.md](../CLAUDE.md) and
  [combat/docs/findings.md](../combat/docs/findings.md).

So the move was cheap in git and not cheap in running state. It wanted the run stopped at a round boundary, which is a
deliberate act, not something to do while a run is mid-round at two in the morning.

## What the move did

Done in a worktree, on a branch, with nothing training on the machine.

1. **The combat tree moved as one unit**, in one commit, so history follows: `git mv mod trainer viewer scripts models
   docs combat/`. `runs/` and `.tools/` are not in git and move on disk. `README.md`, `CLAUDE.md`, `LICENSE`,
   `.gitignore`, `.gitattributes`, `.claude/` and `mind/` stayed at the root, and `docs/monorepo.md` came back up to it.
2. **`mind/models/` moved to `shared/models/`.** `tools/freeze.py`, `tools/check_parity.py`, `tests/test_freeze.py`,
   `docs/port.md`, `docs/plan.md` and `README.md` in `mind/` go one level up to reach it; every `directory` the manifest
   names is relative to `shared/`, so it reads the same from either side.
3. **`combat/scripts/_common.ps1`.** `$Root` still resolves correctly — the scripts folder's parent is `combat/` — but
   the borrowing block did not: what `--git-common-dir` finds is the *repository* root, so the borrowed path is
   `Join-Path $main 'combat\trainer\.venv\Scripts\python.exe'`. The old location is tried after it, so a worktree on
   this branch borrows whether or not the main checkout has moved yet; drop that second candidate once it has.
4. **The build's two anchors.** In `combat/mod/buildSrc/src/main/groovy/multiloader-loader.gradle`, `repositoryRoot` is
   the *subproject* root, which is what the `models/`, `runs/`, `trainer/` and `viewer/` joins want, so those were left
   alone. `mainCheckout` got its own anchor: it reads `.git` from `repositoryRoot.parentFile`, and a new `borrowedFile`
   helper joins `combat/` back on when composing the borrowed venv and terrain-library paths (with the old location as
   a fallback, as above). `viewer/serve.py` borrows `runs/` the same way and was fixed the same way. Test it from a
   worktree, because the main checkout has a `.git` **directory** and will pass either way.
5. **`multiloader-common.gradle`** packs `LICENSE` into the jar from `rootProject.file('../LICENSE')`, which is now
   `../../LICENSE`: the licence stayed at the repository root and the build went a level deeper.
6. **`.gitignore`.** A bare `runs` matched at any depth and covered the training runs *and* the loaders' own run
   folders at once. It is now `combat/runs/`, `mind/runs/` and `combat/mod/*/runs/`, and `.tools` is `combat/.tools/`.

## What the main checkout has to do when it switches

Nothing in git beyond a fast-forward — but four untracked things have to be moved on disk by hand, and they are the
expensive ones:

1. `git pull --ff-only` (or fast-forward to this branch).
2. Move `runs\` to `combat\runs\` — a rename on the same volume, instant, and it carries `_bests`, `_seeds` and
   `terrain\1.21.1\library` with it. Half an hour of generated ground; do not regenerate it.
3. Move `trainer\.venv\` to `combat\trainer\.venv\` and `.tools\` to `combat\.tools\`.
4. Move the Gradle outputs, or delete them and let them rebuild: `mod\*\build`, `mod\buildSrc\build`, `mod\.gradle`.
5. Restart the run with `combat\` prefixed on the script path, and watch one full round land in `watch.ps1` before
   walking away.

`mind/` needed nothing from any of this: it is pure Python, it resolves its data relative to its own files, and the one
thing it now reaches for outside itself is `shared/models/`, one level up.
