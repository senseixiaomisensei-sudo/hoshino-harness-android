#!/usr/bin/env python3
"""Turn Mika (0.1.13, https://github.com/wangzan101/mika) run records into the `mika` section of phone-bench JSON.

Inputs (any mix, files or directories, searched recursively):
  * events.jsonl  - Mika run records (`files/runs/<id>/events.jsonl`). Debug builds of Mika mirror them to
                    /sdcard/Android/data/com.wangzan101.mika/files/runs/ (RunStore.mirrorToExternal). Each
                    `tool_result` event has durationMs (tool time incl. waiting for the UI and pacing pauses) and
                    waitMs (the UI-wait / pacing part). Steps of a phone_run batch are logged one by one with
                    source="batch" - those are the per-step samples comparable to our do_steps steps.
  * run-metrics export (JSON array from Mika's 运行统计 -> share): run-level totals only; gives a per-step
    mean (actMs+uiWaitMs over phone steps), never P50/P90.
Nothing here guesses a number: what the inputs do not contain stays null.

Usage: python3 tools/phone-bench/mika_baseline.py PATH [PATH...] [--version 0.1.13] [--build debug|perf|release]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from benchstats import summarize  # noqa: E402

LAUNCH_TOOLS = {"phone_launch_app", "launch", "open_app"}
# Tools that do not act on the screen (reads, lists, waiting for the user): not a "step".
NON_STEPS = {"phone_run", "phone_list_apps", "phone_query", "phone_extract", "phone_find", "phone_collect", "observe", "phone_show_alarms"}


def _files(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, names in os.walk(p):
                for n in sorted(names):
                    if n == "events.jsonl" or n.endswith(".json"):
                        yield os.path.join(root, n)
        elif os.path.isfile(p):
            yield p


def parse(paths):
    batch, model_steps, launches, waits = [], [], [], []
    runs, metric_runs = 0, []
    for f in _files(paths):
        if f.endswith("events.jsonl"):
            runs += 1
            with open(f, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        e = json.loads(line)
                    except ValueError:
                        continue
                    if e.get("type") != "tool_result":
                        continue
                    tool = str(e.get("tool", ""))
                    ms = int(e.get("durationMs", 0) or 0)
                    if tool in LAUNCH_TOOLS:
                        launches.append(ms)
                    if tool in NON_STEPS:
                        continue
                    if e.get("source") == "batch":
                        batch.append(ms)
                        waits.append(int(e.get("waitMs", 0) or 0))
                    elif tool.startswith("phone_") or e.get("source") == "model":
                        model_steps.append(ms)
        elif f.endswith(".json"):
            try:
                data = json.load(open(f, encoding="utf-8"))
            except ValueError:
                continue
            if isinstance(data, list) and data and isinstance(data[0], dict) and "actMs" in data[0]:
                metric_runs.extend(data)
    out = {
        "source": "events.jsonl" if runs else ("run-metrics export" if metric_runs else None),
        "runs": runs,
        "batch_step_ms": summarize(batch),
        "batch_step_wait_ms": summarize(waits),
        "model_step_ms": summarize(model_steps),
        "open_app_ms": summarize(launches),
        "display_create_ms": None,
        "preview_fps": None,
        "notes": [
            "durationMs includes Mika's pacing pauses (default 标准 pace) and the wait for the UI; it excludes waiting for the user.",
            "display_create_ms / preview_fps are not recorded by Mika 0.1.13; left null rather than estimated.",
        ],
    }
    if metric_runs:
        calls = sum(int(r.get("actCalls", 0) or 0) for r in metric_runs)
        act = sum(int(r.get("actMs", 0) or 0) for r in metric_runs)
        ui_wait = sum(int(r.get("uiWaitMs", 0) or 0) for r in metric_runs)
        # RunMetrics: actMs already contains the in-tool UI wait; the after-step waits of a batch are in uiWaitMs/otherMs only.
        out["run_metrics"] = {"runs": len(metric_runs), "act_calls": calls, "mean_act_ms": round(act / calls, 1) if calls else None,
                              "ui_wait_ms_total": ui_wait, "note": "means only; P50/P90 need events.jsonl"}
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--version", default="0.1.13")
    ap.add_argument("--build", default=None, help="which Mika build was measured (debug builds are slower in UI)")
    a = ap.parse_args(argv)
    res = parse(a.paths)
    res["version"] = a.version
    res["build"] = a.build
    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
