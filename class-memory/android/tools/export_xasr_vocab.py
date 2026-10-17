#!/usr/bin/env python3
"""Export SentencePiece piece/score records without installing dependencies.

Reads the public ModelProto wire format, not ONNX data. This is an asset
conversion utility, not a SentencePiece tokenizer. Schema reference:
https://github.com/google/sentencepiece/blob/master/src/sentencepiece_model.proto
Equivalent output fields to sherpa-onnx v1.13.7 scripts/export_bpe_vocab.py.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct


def varint(data, position):
    value = 0
    for shift in range(0, 70, 7):
        if position >= len(data):
            raise ValueError("Truncated protobuf varint")
        byte = data[position]
        position += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, position
    raise ValueError("Oversized protobuf varint")


def fields(data):
    position = 0
    while position < len(data):
        tag, position = varint(data, position)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("Invalid protobuf field")
        if wire == 0:
            value, position = varint(data, position)
        else:
            if wire == 2:
                size, position = varint(data, position)
            elif wire in (1, 5):
                size = 8 if wire == 1 else 4
            else:
                raise ValueError(f"Unsupported wire type {wire}")
            end = position + size
            if end > len(data):
                raise ValueError("Truncated protobuf field")
            value, position = data[position:end], end
        yield number, wire, value


def export(model, tokens, output):
    data = model.read_bytes()
    pieces = []
    model_type = 1
    for number, wire, value in fields(data):
        if number == 2 and wire == 2:
            model_type = next((v for n, w, v in fields(value) if n == 3 and w == 0), 1)
        if number != 1 or wire != 2:
            continue
        record = {(n, w): v for n, w, v in fields(value)}
        piece = record[(1, 2)].decode("utf-8")
        score = struct.unpack("<f", record.get((2, 5), b"\0" * 4))[0]
        kind = record.get((3, 0), 1)
        if not piece or any(c.isspace() for c in piece) or not math.isfinite(score):
            raise ValueError("Invalid piece/score")
        pieces.append((piece, score, kind))
    # sherpa's bpe.vocab API consumes piece/score records from both unigram
    # and BPE SentencePiece models; the X-ASR archive is unigram (type 1).
    if model_type not in (1, 2):
        raise ValueError(f"Unsupported SentencePiece model type {model_type}")
    if not pieces or len({p for p, _, _ in pieces}) != len(pieces):
        raise ValueError("Empty or duplicate vocabulary")
    symbols = {line.rsplit(maxsplit=1)[0] for line in tokens.read_text().splitlines() if line.strip()}
    missing = [p for p, _, kind in pieces if kind == 1 and p not in symbols]
    if missing:
        raise ValueError(f"Normal pieces missing from recognizer tokens: {missing[:8]}")
    if output.exists():
        raise ValueError("Output exists; refusing to overwrite")
    payload = "".join(f"{piece}\t{score}\n" for piece, score, _ in pieces).encode("utf-8")
    output.write_bytes(payload)
    print(json.dumps({
        "pieces": len(pieces), "normalPieces": sum(kind == 1 for _, _, kind in pieces),
        "modelType": model_type, "normalPiecesMissingFromTokens": len(missing),
        "modelSha256": hashlib.sha256(data).hexdigest(),
        "vocabSha256": hashlib.sha256(payload).hexdigest(), "vocabBytes": len(payload),
    }, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--tokens", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.model, args.tokens, args.output)
