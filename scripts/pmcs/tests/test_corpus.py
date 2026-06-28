import json
import os
import tempfile
from scripts.pmcs.corpus import load_corpus


def test_load_seed_corpus():
    entries = load_corpus("config/agent_driver/replays/corpus.json")
    archives = {e.archive for e in entries}
    assert "replay-0004.json" in archives
    e4 = next(e for e in entries if e.archive == "replay-0004.json")
    assert e4.cmp == "le" and e4.arrive_x == -520
    assert e4.failure_class == "water-corridor"


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
