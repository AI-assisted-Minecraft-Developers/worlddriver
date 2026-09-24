"""Loader for the journey replay-corpus manifest."""
import json
from dataclasses import dataclass


@dataclass
class CorpusEntry:
    archive: str
    arrive_x: int
    cmp: str
    failure_class: str
    region: str


def load_corpus(path: str):
    with open(path) as f:
        data = json.load(f)
    out = []
    for e in data["entries"]:
        if e["cmp"] not in ("ge", "le"):
            raise ValueError(f"cmp must be ge|le, got {e['cmp']!r} for {e['archive']}")
        out.append(CorpusEntry(e["archive"], int(e["arrive_x"]), e["cmp"],
                               e["failure_class"], e["region"]))
    return out
