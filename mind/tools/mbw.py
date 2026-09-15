"""Write the two frozen models out as ``.mbw`` weight files, the format the combat mod reads.

    python -m tools.mbw                     # both, into ../shared/models/*/
    python -m tools.mbw --only decisions

``tools/freeze.py`` calls this at the end of each half, so a retrain regenerates the ``.mbw``
beside the ``.npz`` and the two cannot drift. Run on its own it converts whatever is already
frozen in ``shared/models/``, which needs neither ``runs/`` nor ``text/models/`` and so works in
a checkout that has only what git carries.

The format
----------

``.mbw`` is the combat mod's own weight file (``combat/mod/common/.../brain/nn/WeightFile.java``).
Version 4 appends one word, **kind**, to the header, and nothing before it moves, so every file
ever published still reads byte for byte as it did:

    offset  size  content                                       all little endian
    0       4     magic 'M','B','W','1'
    4       4     u32 format version, 4 here
    8       4     u32 schema id
    12      4     u32 shape hash, CRC32 of the shape words this kind uses
    16      24    u32 shape words 0..5
    40      16    u32 shape words 6..9
    56      4     f32 observation clip; 1.0 where there is no normaliser
    60      4     u32 training iteration
    64      4     u32 parameter count
    68      4     u32 kind          version 4 and later; 0 for every earlier file
    72      N*4   f32 parameters, in the segment order below

    kind 0  the recurrent actor the combat mod trains; versions 1 to 3 are this kind
    kind 1  a plain MLP scorer                      -- the decisions model
    kind 2  an embedding-bag classifier             -- the interpreter

**kind 1**, shape words ``inDim, h1, h2, outDim`` (the other six are zero)::

    fc1W[h1 x inDim]  fc1B[h1]  fc2W[h2 x h1]  fc2B[h2]  outW[outDim x h2]  outB[outDim]

**kind 2**, shape words ``buckets, dim, side, hidden`` then the head widths, zero terminated::

    emb[buckets x dim]
    fc1W[hidden x (dim + side)]  fc1B[hidden]
    headW[width x hidden]  headB[width]        for each head, in order

Matrices are row major ``[out][in]``, which is how torch stores them and how ``Topology`` reads
them, so neither side transposes anything. The arrays go in in the order each model's
``README.md`` lists them, which is already the order the pass applies them in.

The schema id
-------------

The mod's schema id is a u32; a frozen model's is the sha256 of its ``layout.json``. The first
four bytes of that sha256, read big endian, are the u32 the header carries -- so the id in the
file is a prefix of the id in ``MANIFEST.json`` and a reader can print one and find the other.
A layout that changes changes the id, and the Java side refuses the file rather than reading the
wrong columns.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import zlib

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MAGIC = b"MBW1"
VERSION = 4

KIND_ACTOR = 0
KIND_SCORER = 1
KIND_CLASSIFIER = 2

#: How many shape words the header carries. Ten, whatever the kind means by them.
SHAPE_WORDS = 10

#: There is no normaliser on either of these models, so the clip word says nothing; it is written
#: as 1.0 because the reader refuses a clip that is not positive and that refusal is worth keeping
#: for the kind that does have one.
NO_CLIP = 1.0


def layout_schema_id(layout_path: str) -> tuple[int, str]:
    """``(the u32 the header carries, the full sha256)`` of a model's ``layout.json``."""
    digest = hashlib.sha256(open(layout_path, "rb").read()).hexdigest()
    return int(digest[:8], 16), digest


def pack(schema_id: int, kind: int, words: list, used: int, params: np.ndarray,
         iteration: int = 0) -> bytes:
    """One weight file's bytes: the header, then the parameters as float32.

    ``used`` is how many of the shape words this kind means anything by, and so how many the
    hash covers -- the same discipline as ``Topology#hash``, which hashes six words for a network
    that attends nothing and ten for one that does.
    """
    if len(words) != SHAPE_WORDS:
        raise ValueError("a header carries %d shape words, not %d" % (SHAPE_WORDS, len(words)))

    flat = np.ascontiguousarray(params, dtype=np.float32)
    if not np.isfinite(flat).all():
        raise ValueError("the parameters hold a non-finite value; the reader refuses those")

    shape = struct.pack("<%dI" % SHAPE_WORDS, *[w & 0xFFFFFFFF for w in words])
    digest = zlib.crc32(struct.pack("<%dI" % used, *[w & 0xFFFFFFFF for w in words[:used]]))

    header = (MAGIC
              + struct.pack("<I", VERSION)
              + struct.pack("<I", schema_id & 0xFFFFFFFF)
              + struct.pack("<I", digest & 0xFFFFFFFF)
              + shape
              + struct.pack("<f", NO_CLIP)
              + struct.pack("<I", iteration)
              + struct.pack("<I", int(flat.size))
              + struct.pack("<I", kind))

    assert len(header) == 72, len(header)
    return header + flat.tobytes()


# ---------------------------------------------------------------------------
# the two models
# ---------------------------------------------------------------------------

#: ``imitator.npz``'s array order, which is already the order the pass applies them in.
DECISIONS_ARRAYS = ["fc1.weight", "fc1.bias", "fc2.weight", "fc2.bias", "out.weight", "out.bias"]

#: ``weights.npz``'s array order, likewise.
INTERPRETER_ARRAYS = ["emb.weight", "fc1.weight", "fc1.bias",
                      "head_intent.weight", "head_intent.bias",
                      "head_topic.weight", "head_topic.bias",
                      "head_addressed.weight", "head_addressed.bias",
                      "head_sincerity.weight", "head_sincerity.bias",
                      "head_floats.weight", "head_floats.bias"]

#: The heads in file order, which is what the head widths in the header describe.
INTERPRETER_HEADS = ["intent", "topic", "addressed", "sincerity", "floats"]


def _concat(arrays: dict, names) -> np.ndarray:
    return np.concatenate([np.asarray(arrays[n], dtype=np.float32).reshape(-1) for n in names])


def write_decisions(model_dir: str) -> dict:
    """``decisions.mbw`` from the frozen ``imitator.npz``; returns what the manifest records."""
    with np.load(os.path.join(model_dir, "imitator.npz")) as npz:
        arrays = {name: npz[name] for name in DECISIONS_ARRAYS}

    h1, in_dim = arrays["fc1.weight"].shape
    h2, h1_again = arrays["fc2.weight"].shape
    out_dim, h2_again = arrays["out.weight"].shape

    if h1_again != h1 or h2_again != h2:
        raise ValueError("the three layers do not line up: %r" % [a.shape for a in arrays.values()])

    schema_id, digest = layout_schema_id(os.path.join(model_dir, "layout.json"))
    words = [in_dim, h1, h2, out_dim, 0, 0, 0, 0, 0, 0]

    return _write(os.path.join(model_dir, "decisions.mbw"),
                  pack(schema_id, KIND_SCORER, words, 4, _concat(arrays, DECISIONS_ARRAYS)),
                  digest, words[:4])


def write_interpreter(model_dir: str) -> dict:
    """``interpreter.mbw`` from the frozen ``weights.npz``; returns what the manifest records."""
    with np.load(os.path.join(model_dir, "weights.npz")) as npz:
        arrays = {name: npz[name] for name in INTERPRETER_ARRAYS}

    buckets, dim = arrays["emb.weight"].shape
    hidden, fc1_in = arrays["fc1.weight"].shape
    side = fc1_in - dim
    heads = [int(arrays["head_%s.weight" % head].shape[0]) for head in INTERPRETER_HEADS]

    if side <= 0 or buckets & (buckets - 1):
        raise ValueError("fc1 takes %d columns over a %d wide embedding, and %d buckets"
                         % (fc1_in, dim, buckets))
    if len(heads) > SHAPE_WORDS - 4:
        raise ValueError("a header has room for %d heads, not %d" % (SHAPE_WORDS - 4, len(heads)))

    schema_id, digest = layout_schema_id(os.path.join(model_dir, "layout.json"))
    words = [buckets, dim, side, hidden] + heads
    words += [0] * (SHAPE_WORDS - len(words))

    return _write(os.path.join(model_dir, "interpreter.mbw"),
                  pack(schema_id, KIND_CLASSIFIER, words, 4 + len(heads),
                       _concat(arrays, INTERPRETER_ARRAYS)),
                  digest, words[:4 + len(heads)])


def _write(path: str, data: bytes, layout_sha: str, words) -> dict:
    with open(path, "wb") as fh:
        fh.write(data)
    return {
        "file": os.path.basename(path),
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "format_version": VERSION,
        "schema_id": "%08x" % int(layout_sha[:8], 16),
        "shape_words": [int(w) for w in words],
    }


WRITERS = {"interpreter": write_interpreter, "decisions": write_decisions}


def patch_manifest(out_root: str, written: dict) -> bool:
    """Put what was just written into ``MANIFEST.json``'s ``weights.weight_file``, in place.

    ``tools/freeze.py`` writes the whole manifest itself and never needs this; converting on its
    own does, or the manifest would go on describing a ``.mbw`` that is no longer there. Written
    with the same formatting ``freeze`` uses, so the two cannot produce different bytes for the
    same facts.
    """
    path = os.path.join(out_root, "MANIFEST.json")
    if not os.path.exists(path):
        return False

    with open(path, encoding="utf-8") as fh:
        manifest = json.load(fh)

    for model in manifest.get("models", []):
        if model["name"] in written:
            model["weights"]["weight_file"] = written[model["name"]]

    data = (json.dumps(manifest, indent=2, sort_keys=False, ensure_ascii=False) + "\n").encode("utf-8")
    with open(path, "wb") as fh:
        fh.write(data)
    return True


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", default=None, help="default: <repository>/shared/models")
    ap.add_argument("--only", choices=tuple(WRITERS), default=None)
    ap.add_argument("--no-manifest", action="store_true",
                    help="leave MANIFEST.json alone (freeze rewrites it wholesale anyway)")
    args = ap.parse_args(argv)

    out_root = args.models or os.path.abspath(os.path.join(ROOT, os.pardir, "shared", "models"))
    written = {}

    for name, writer in WRITERS.items():
        if args.only and args.only != name:
            continue
        info = writer(os.path.join(out_root, name))
        written[name] = info
        print("%-12s %s: %s, %.1f KB, shape %s, sha256 %s"
              % (name, info["schema_id"], info["file"], info["bytes"] / 1024,
                 info["shape_words"], info["sha256"][:16]))

    if not args.no_manifest and patch_manifest(out_root, written):
        print("MANIFEST.json: %d weight file%s recorded" % (len(written), "" if len(written) == 1 else "s"))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
