import json
import os
import tempfile
from scripts.pmcs.corpus import load_corpus


def test_load_seed_corpus():
    entries = load_corpus("config/worlddriver/replays/corpus.json")
    archives = {e.archive for e in entries}
    # At least 8 varied archives, covering failure classes such as steep/water/dry/long
    assert len(entries) >= 8
    assert "corpus-water-757.json" in archives
    classes = {e.failure_class for e in entries}
    assert "steep-diagUp" in classes and "water-corridor" in classes
    # eastbound uses ge, westbound uses le
    e_water = next(e for e in entries if e.archive == "corpus-water-757.json")
    assert e_water.cmp == "ge" and e_water.arrive_x == -525
    e_long = next(e for e in entries if e.archive == "corpus-long-540.json")
    assert e_long.cmp == "le"


def test_cmp_validation():
    bad = {"silky_ticks": 120, "tolerance": 0.1,
           "entries": [{"archive": "x.json", "arrive_x": 0, "cmp": "WRONG",
                        "failure_class": "f", "region": "r"}]}
    p = os.path.join(tempfile.mkdtemp(), "bad.json")
    open(p, "w").write(json.dumps(bad))
    try:
        load_corpus(p)
        assert False, "should reject cmp not in ge/le"
    except ValueError:
        pass
