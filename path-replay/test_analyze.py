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
