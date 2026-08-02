#!/usr/bin/env python3
"""worlddriver's gate entry point for StageWright's t2 orchestrator.

The orchestrators moved to the StageWright repo when it was split out; this shim keeps the
documented `python scripts/stagewright/t2.py ...` commands working unchanged.

It also PINS the consumer project root to this repo. The orchestrators default that to the
current directory, which is right for them and wrong here: a gate that silently drives
whichever build you happened to be standing in is the failure mode --project-root exists to
prevent. Every other flag passes straight through.

Set STAGEWRIGHT_HOME to point at a StageWright checkout elsewhere than ../stagewright.
"""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
HOME = os.environ.get("STAGEWRIGHT_HOME") or os.path.join(os.path.dirname(REPO), "stagewright")
TARGET = os.path.join(HOME, "scripts", "t2.py")

if not os.path.isfile(TARGET):
    sys.exit("StageWright orchestrator not found: " + TARGET
             + " -- expected a StageWright checkout beside this repo, or set STAGEWRIGHT_HOME.")

os.environ.setdefault("STAGEWRIGHT_PROJECT_ROOT", REPO)
sys.argv[0] = TARGET
runpy.run_path(TARGET, run_name="__main__")
