# Dialogue classifier

Trained on `text/data/dialogue_partial.jsonl` (14,996 lines: 12,747 train, 2,249 test, stratified by source, seed 20250913).

- best epoch 17 of 21 run, test loss 3.0587
- 158.4s on 1 CPU thread(s)
- `sincerity` labelled on 10,796 of 14,996 rows; unlabelled rows are masked out of that head's loss and metrics
- data sha256 `fb6fb6b479f9acbd...`

## Test metrics

| head | n | accuracy | macro-F1 |
| --- | ---: | ---: | ---: |
| intent | 2,249 | 0.513 | 0.440 |
| topic | 2,249 | 0.777 | 0.211 |
| addressed | 2,249 | 0.855 | 0.360 |
| sincerity | 1,638 | 0.943 | 0.333 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.082 | 93.8% |
| valence | 0.207 | 68.0% |
| urgency | 0.095 | 94.4% |

## Most confused intent pairs

| true | predicted | count |
| --- | --- | ---: |
| SMALLTALK | PRAISE | 139 |
| SMALLTALK | COMMAND | 79 |
| SMALLTALK | OFFER | 76 |
| SMALLTALK | WARNING | 53 |
| SMALLTALK | QUESTION | 52 |
| QUESTION | PRAISE | 46 |

## Accuracy by source (test set)

| source | n | intent | topic | addressed | sincerity |
| --- | ---: | ---: | ---: | ---: | ---: |
| cornell_movie | 488 | 0.447 | 0.865 | 0.891 | 0.940 |
| dailydialog | 493 | 0.511 | 0.732 | 0.968 | 0.995 |
| empatheticdialogues | 495 | 0.489 | 0.826 | 0.919 | 0.970 |
| generated | 135 | 0.541 | 0.185 | 0.600 | 0.822 |
| minecraft_chat | 638 | 0.577 | 0.832 | 0.743 | 0.919 |

## Hard cases

40 hand-written lines from `text/data/hardcases.jsonl`, never trained on.

| head | n | accuracy |
| --- | ---: | ---: |
| intent | 40 | 0.675 |
| topic | 40 | 0.525 |
| addressed | 40 | 0.850 |
| sincerity | 40 | 0.850 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.218 | 65.0% |
| valence | 0.342 | 45.0% |
| urgency | 0.255 | 60.0% |

## Inference (pure numpy, one line at a time)

- mean 185.9 us, p99 350.1 us over 2,000 lines
- torch/numpy parity: max abs diff 3.58e-07 on 500 lines

## Files

| file | what |
| --- | --- |
| `model.pt` | torch state dict |
| `weights.npz` | float32 arrays, the order is in `model.json` |
| `model.json` | dims, buckets, hash, label order, tokenizer rules, metrics |
| `metrics.json` | all of the above plus the per-epoch history, machine readable |
| `report.md` | this file |
