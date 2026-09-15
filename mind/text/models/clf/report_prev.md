# Dialogue classifier

Trained on `text/data/dialogue_partial.jsonl` (154,618 lines: 190,285 train, 23,193 test, stratified by source, seed 20250913).

- best epoch 4 of 8 run, test loss 2.7535
- 289.2s on 1 CPU thread(s)
- `sincerity` labelled on 150,418 of 154,618 rows; unlabelled rows are masked out of that head's loss and metrics
- data sha256 `a240e210d4af3f5a...`

## Test metrics

| head | n | accuracy | macro-F1 |
| --- | ---: | ---: | ---: |
| intent | 23,193 | 0.663 | 0.599 |
| topic | 23,193 | 0.802 | 0.662 |
| addressed | 23,193 | 0.849 | 0.458 |
| sincerity | 22,559 | 0.912 | 0.479 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.095 | 95.0% |
| valence | 0.199 | 69.3% |
| urgency | 0.107 | 93.9% |

## Most confused intent pairs

| true | predicted | count |
| --- | --- | ---: |
| QUESTION | SMALLTALK | 1176 |
| SMALLTALK | QUESTION | 448 |
| COMMAND | SMALLTALK | 419 |
| PRAISE | SMALLTALK | 409 |
| SMALLTALK | COMMAND | 294 |
| REQUEST | SMALLTALK | 262 |

## Accuracy by source (test set)

| source | n | intent | topic | addressed | sincerity |
| --- | ---: | ---: | ---: | ---: | ---: |
| cornell_movie | 4,200 | 0.553 | 0.874 | 0.921 | 0.981 |
| dailydialog | 4,200 | 0.611 | 0.731 | 0.965 | 0.995 |
| empatheticdialogues | 4,200 | 0.743 | 0.837 | 0.934 | 0.978 |
| generated | 5,193 | 0.719 | 0.704 | 0.666 | 0.698 |
| minecraft_chat | 5,400 | 0.672 | 0.870 | 0.812 | 0.955 |

## Hard cases

40 hand-written lines from `text/data/hardcases.jsonl`, never trained on.

| head | n | accuracy |
| --- | ---: | ---: |
| intent | 40 | 0.600 |
| topic | 40 | 0.800 |
| addressed | 40 | 0.800 |
| sincerity | 40 | 0.850 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.187 | 72.5% |
| valence | 0.323 | 47.5% |
| urgency | 0.187 | 75.0% |

## Inference (pure numpy, one line at a time)

- mean 110.0 us, p99 204.5 us over 2,000 lines
- torch/numpy parity: max abs diff 3.28e-07 on 500 lines

## Files

| file | what |
| --- | --- |
| `model.pt` | torch state dict |
| `weights.npz` | float32 arrays, the order is in `model.json` |
| `model.json` | dims, buckets, hash, label order, tokenizer rules, metrics |
| `metrics.json` | all of the above plus the per-epoch history, machine readable |
| `report.md` | this file |
