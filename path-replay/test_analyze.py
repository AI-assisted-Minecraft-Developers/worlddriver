import subprocess, sys, pathlib

HERE = pathlib.Path(__file__).parent


def run(*args):
    return subprocess.run(
        [sys.executable, str(HERE / "analyze.py"), *args],
        capture_output=True,
        text=True,
    )


def test_flags_suffocation_and_hazard():
    r = run(str(HERE / "sample-archive.json"), "--all")
    assert r.returncode == 0, r.stderr
    out = r.stdout
    assert "suffocate" in out
    assert "lava" in out
    assert "jump" in out.lower()


def test_table_columns_and_flags():
    r = run(str(HERE / "sample-archive.json"), "--all")
    out = r.stdout
    low = out.lower()
    for col in ("step", "pose", "fit", "underfoot", "fall", "jump", "break", "place"):
        assert col in low, col
    up = out.upper()
    assert "SUFFOCATE" in up   # node[0]
    assert "HAZARD" in up or "LAVA" in up   # node[1] lava
    assert "JUMP" in up        # node[2] jump not feasible flagged


def test_marks_planned_block_ops():
    out = run(str(HERE / "sample-archive.json"), "--all").stdout.upper()
    assert "BREAK" in out and "PLACE" in out


def test_replay_deviation(tmp_path):
    import json, pathlib
    # minimal replay-run with one tick deviation 4.0 (> default threshold 1.5)
    plan = json.loads((HERE / "sample-archive.json").read_text())
    run_arch = dict(plan); run_arch["kind"] = "replay"; run_arch["planRef"] = "sample-archive.json"
    run_arch["trajectory"] = [{"tick": 0, "x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "step": 1, "move": "walk",
                               "onGround": True, "inWater": False, "pose": "STANDING", "aabbOverlap": False, "deviation": 4.0}]
    f = tmp_path / "replay-run.json"; f.write_text(json.dumps(run_arch))
    out = run(str(HERE / "sample-archive.json"), "--all", "--replay", str(f)).stdout.upper()
    assert "4.0" in out and "DRIFT" in out
