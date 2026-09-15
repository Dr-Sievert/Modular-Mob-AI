# Dialogue classifier

Trained on `text/data/dialogue_partial.jsonl` (154,618 lines: 190,285 train, 23,193 test, stratified by source, seed 20250913).

- best epoch 9 of 12 run, test loss 2.5851
- 820.0s on 1 CPU thread(s)
- `sincerity` labelled on 150,418 of 154,618 rows; unlabelled rows are masked out of that head's loss and metrics
- data sha256 `a240e210d4af3f5a...`

## Test metrics

| head | n | accuracy | macro-F1 |
| --- | ---: | ---: | ---: |
| intent | 23,193 | 0.728 | 0.627 |
| topic | 23,193 | 0.800 | 0.665 |
| addressed | 23,193 | 0.854 | 0.539 |
| sincerity | 22,559 | 0.909 | 0.480 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.098 | 94.7% |
| valence | 0.210 | 66.6% |
| urgency | 0.101 | 94.1% |

## Most confused intent pairs

| true | predicted | count |
| --- | --- | ---: |
| COMMAND | SMALLTALK | 414 |
| PRAISE | SMALLTALK | 348 |
| SMALLTALK | PRAISE | 293 |
| SMALLTALK | COMMAND | 289 |
| QUESTION | SMALLTALK | 272 |
| REQUEST | SMALLTALK | 228 |

## Accuracy by source (test set)

| source | n | intent | topic | addressed | sincerity |
| --- | ---: | ---: | ---: | ---: | ---: |
| cornell_movie | 4,200 | 0.678 | 0.860 | 0.916 | 0.980 |
| dailydialog | 4,200 | 0.724 | 0.736 | 0.961 | 0.995 |
| empatheticdialogues | 4,200 | 0.789 | 0.816 | 0.930 | 0.978 |
| generated | 5,193 | 0.739 | 0.717 | 0.695 | 0.686 |
| minecraft_chat | 5,400 | 0.714 | 0.872 | 0.817 | 0.954 |

## Hard cases

40 hand-written lines from `text/data/hardcases.jsonl`, never trained on.

| head | n | accuracy |
| --- | ---: | ---: |
| intent | 40 | 0.650 |
| topic | 40 | 0.700 |
| addressed | 40 | 0.800 |
| sincerity | 40 | 0.850 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.173 | 82.5% |
| valence | 0.310 | 52.5% |
| urgency | 0.192 | 72.5% |

## Inference (pure numpy, one line at a time)

- mean 133.4 us, p99 361.7 us over 2,000 lines
- torch/numpy parity: max abs diff 2.38e-07 on 500 lines

## Files

| file | what |
| --- | --- |
| `model.pt` | torch state dict |
| `weights.npz` | float32 arrays, the order is in `model.json` |
| `model.json` | dims, buckets, hash, label order, tokenizer rules, metrics |
| `metrics.json` | all of the above plus the per-epoch history, machine readable |
| `report.md` | this file |
