# shared

What both halves of the repository have to agree on, byte for byte. Everything here is **generated**, never hand-edited,
and it is the only place [`mind/`](../mind/README.md) writes to or reads from outside itself; [`combat/`](../combat/README.md)
reads it when the port lands.

```
models/interpreter/    the dialogue classifier, frozen
models/decisions/      the learned arbitrator, frozen
models/MANIFEST.json   what the two are, what they were trained on, and the hashes that prove a copy is the right one
```

Nothing goes in here until both halves genuinely use it. A thing only one side needs belongs to that side.

## What belongs here

**The weight-file format decisions.** `.mbw` is one format with one header and one layout convention, and the mod and the
mind both have to produce and read it identically. Matrices are stored `[out][in]` and read straight through without
seeking; arrays appear in a frozen order that both sides name the same way; floats are float32. A change to any of that
is a change to both halves at once, which is why it is decided in one place. The segment order proposed for each of the
two models is in [`mind/docs/port.md`](../mind/docs/port.md), and the per-model array order is in each model's
`README.md` here.

**The schema-id rule.** A model's schema id is the sha256 of its `layout.json` — the same idea as the combat mod's schema
id, which is the CRC32 of its `schema.json`, and used for the same reason: a weight file that does not match the layout
the reader expects is *refused* rather than silently misread. `layout.json` is the whole contract — array names, shapes,
offsets, the label lists, the vector widths — so if it changes, the id changes, and every artefact stamped with the old
one stops being accepted. Nothing downstream is allowed to "cope" with a mismatch.

**The parity procedure.** Each model ships a `parity.jsonl` of 200 fixed records: the inputs and the exact outputs the
numpy reference produced. They are chosen by a fixed rule and not a fresh random draw, so a retrain that does not change
the data changes the *numbers* and not the *lines*, and a diff of the file is readable. The procedure is:

1. `cd mind && python -m tools.freeze` regenerates everything here from the current exports, including the manifest.
2. `cd mind && python -m tools.check_parity` re-runs both parity files through the numpy implementations and fails on any
   drift past 1e-5, and on any weight or layout whose sha256 is not the one the manifest recorded. It is part of
   `mind/tests/test_freeze.py`, so the mind's suite runs it.
3. The Java port reproduces the same file. That is what "ported" means here: not that it compiles, but that it answers
   all 200 records to 1e-5.

**The frozen models.** Both are here because both are port-ready and neither is regenerable from git alone — the training
checkpoints they come from live in `mind/runs/` and `mind/text/models/`, which are not tracked.

| Model | What it does | In | Out |
| --- | --- | --- | --- |
| `interpreter` | reads one line of chat into the `text/SCHEMA.md` label set | one chat line, plus the previous line's intent | 4 enum heads, 3 floats, names |
| `decisions` | scores one candidate action for the arbitrator | 69 observation floats + 64 candidate floats | one score |

Each folder holds its weights (`weights.npz` / `imitator.npz`), the exported metadata it came with, `layout.json`,
`parity.jsonl`, and a `README.md` specifying the model completely enough to implement in Java without reading any Python.
`MANIFEST.json` names all of that, with the training-data hash and date and the held-out metrics, and every `directory`
in it is relative to *this* folder.

## Changing anything here

Don't edit these files. Retrain in `mind/`, re-run `python -m tools.freeze`, and commit what it wrote; the diff will show
the numbers moving and the hashes with them. If a layout changed, the schema id in the manifest moves too, and that is
the signal to the Java side that its reader needs the new contract — see [`mind/docs/port.md`](../mind/docs/port.md).
