# Dialogue classifier

Trained on `text/data/dialogue_partial.jsonl` (11,773 lines: 10,007 train, 1,766 test, stratified by source, seed 20250913).

- best epoch 5 of 9 run, test loss 3.0075
- 9.8s on 1 CPU thread(s)
- `sincerity` labelled on 3,074 of 11,773 rows; unlabelled rows are masked out of that head's loss and metrics
- data sha256 `38d58779f393019d...`

## Test metrics

| head | n | accuracy | macro-F1 |
| --- | ---: | ---: | ---: |
| intent | 1,766 | 0.629 | 0.414 |
| topic | 1,766 | 0.806 | 0.128 |
| addressed | 1,766 | 0.865 | 0.320 |
| sincerity | 440 | 0.934 | 0.322 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.084 | 94.7% |
| valence | 0.206 | 68.7% |
| urgency | 0.093 | 95.1% |

## Most confused intent pairs

| true | predicted | count |
| --- | --- | ---: |
| QUESTION | SMALLTALK | 75 |
| SMALLTALK | QUESTION | 52 |
| PRAISE | SMALLTALK | 29 |
| SMALLTALK | PRAISE | 27 |
| SMALLTALK | REQUEST | 27 |
| COMMAND | SMALLTALK | 24 |

## Accuracy by source (test set)

| source | n | intent | topic | addressed | sincerity |
| --- | ---: | ---: | ---: | ---: | ---: |
| cornell_movie | 411 | 0.477 | 0.886 | 0.927 | 0.927 |
| dailydialog | 410 | 0.629 | 0.668 | 0.961 | 0.967 |
| empatheticdialogues | 410 | 0.695 | 0.829 | 0.902 | 0.982 |
| minecraft_chat | 535 | 0.695 | 0.834 | 0.714 | 0.879 |

## Hard cases

40 hand-written lines from `text/data/hardcases.jsonl`, never trained on.

| head | n | accuracy |
| --- | ---: | ---: |
| intent | 40 | 0.250 |
| topic | 40 | 0.475 |
| addressed | 40 | 0.725 |
| sincerity | 40 | 0.850 |

| float | MAE | within 0.25 |
| --- | ---: | ---: |
| aggression | 0.224 | 62.5% |
| valence | 0.377 | 50.0% |
| urgency | 0.271 | 57.5% |

## Inference (pure numpy, one line at a time)

- mean 116.8 us, p99 297.3 us over 2,000 lines
- torch/numpy parity: max abs diff 2.98e-07 on 500 lines

## Files

| file | what |
| --- | --- |
| `model.pt` | torch state dict |
| `weights.npz` | float32 arrays, the order is in `model.json` |
| `model.json` | dims, buckets, hash, label order, tokenizer rules, metrics |
| `metrics.json` | all of the above plus the per-epoch history, machine readable |
| `report.md` | this file |
