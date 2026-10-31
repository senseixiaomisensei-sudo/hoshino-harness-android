#!/usr/bin/env python3
"""Run the WP-0 phone benchmark on a connected phone and write docs/verification/phone-bench-<date>.json.

  python3 tools/phone-bench/run_bench.py                         # Hoshino only (app + test APK already installed)
  python3 tools/phone-bench/run_bench.py --mika ~/mika-runs      # also merge a Mika baseline (see README.md)
  python3 tools/phone-bench/run_bench.py --merge-only bench.json --mika ~/mika-runs

Needs: adb, the phone unlocked with Hoshino's phone control set up (accessibility + built-in helper), and the
Hoshino app and its instrumentation APK (dev.hoshino.app.test) signed with the same key. The test is skipped
(no JSON written) when phone control is not ready; this script then exits 1 and prints the instrument output.
"""
import argparse
import datetime
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mika_baseline  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
APP = "dev.hoshino.app"
RUNNER = f"{APP}.test/androidx.test.runner.AndroidJUnitRunner"
TEST = "app.hoshino.harness.bench.PhoneBenchTest"
REMOTE = f"/sdcard/Android/data/{APP}/files/phone-bench/latest.json"


def adb(serial, *args, check=True, capture=True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def run_on_phone(a):
    if a.install:
        for apk in a.install:
            adb(a.serial, "install", "-r", "--no-streaming", apk)
    adb(a.serial, "shell", "rm", "-f", REMOTE, check=False)
    adb(a.serial, "shell", "svc", "power", "stayon", "true", check=False)
    extras = ["-e", "class", TEST, "-e", "phoneBench", "true", "-e", "rounds", str(a.rounds), "-e", "fixtureTrials", str(a.fixture_trials)]
    if a.settings_package:
        extras += ["-e", "settingsPackage", a.settings_package]
    if a.settings_targets:
        extras += ["-e", "settingsTargets", a.settings_targets]
    if a.flutter_package:
        extras += ["-e", "flutterPackage", a.flutter_package, "-e", "flutterTrials", str(a.flutter_trials)]
    print("running PhoneBenchTest on the phone (a few minutes; keep the phone unlocked) ...", flush=True)
    try:
        r = adb(a.serial, "shell", "am", "instrument", "-w", "-r", *extras, RUNNER, check=False)
    finally:
        adb(a.serial, "shell", "svc", "power", "stayon", "false", check=False)
    out = (r.stdout or "") + (r.stderr or "")
    with tempfile.TemporaryDirectory() as d:
        local = os.path.join(d, "latest.json")
        pulled = adb(a.serial, "pull", REMOTE, local, check=False)
        if pulled.returncode != 0 or not os.path.exists(local):
            print(out)
            sys.exit("no benchmark JSON on the phone: the test was skipped (phone control not ready / not unlocked) or failed early; see output above")
        with open(local, encoding="utf-8") as fh:
            report = json.load(fh)
    failed = "FAILURES!!!" in out or "INSTRUMENTATION_FAILED" in out
    if failed:
        print(out)
        report.setdefault("notes", []).append("PhoneBenchTest reported a failure; numbers are partial (see run output)")
    return report, failed


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", help="adb device serial")
    ap.add_argument("--install", nargs="*", help="APK files to install first (app, then test APK)")
    ap.add_argument("--rounds", type=int, default=3, help="rounds of the fixed 20-step Settings plan")
    ap.add_argument("--fixture-trials", type=int, default=50, help="taps on the delayed-render fixture")
    ap.add_argument("--settings-package", help="Settings package (default com.android.settings)")
    ap.add_argument("--settings-targets", help="comma list of 1-4 Settings entries to tap (default: picked from a safe allowlist)")
    ap.add_argument("--flutter-package", help="package of the Flutter fixture (tools/phone-bench/flutter-fixture), optional")
    ap.add_argument("--flutter-trials", type=int, default=20)
    ap.add_argument("--mika", nargs="*", default=[], help="Mika events.jsonl / runs dirs / run-metrics export to merge as the baseline")
    ap.add_argument("--mika-version", default="0.1.13")
    ap.add_argument("--mika-build", help="which Mika build produced the records (debug / perf / release)")
    ap.add_argument("--merge-only", help="skip the phone run; merge Mika into this existing bench JSON")
    ap.add_argument("--out-dir", default=os.path.join(ROOT, "docs", "verification"))
    a = ap.parse_args(argv)

    failed = False
    if a.merge_only:
        with open(a.merge_only, encoding="utf-8") as fh:
            report = json.load(fh)
    else:
        report, failed = run_on_phone(a)
    if a.mika:
        mika = mika_baseline.parse(a.mika)
        mika["version"] = a.mika_version
        mika["build"] = a.mika_build
        report["mika"] = mika
    date = report.get("date") or datetime.date.today().isoformat()
    os.makedirs(a.out_dir, exist_ok=True)
    path = os.path.join(a.out_dir, f"phone-bench-{date}.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    h = report.get("hoshino", {})
    s = h.get("settings_20", {}).get("per_step", {}).get("total", {})
    d = h.get("delayed_render", {})
    print(f"wrote {path}")
    print(f"  settings step total P50/P90: {s.get('p50')} / {s.get('p90')} ms (n={s.get('n')})")
    print(f"  delayed render: no_effect {d.get('effect_no_effect')}/{d.get('trials')}, late page missing {d.get('late_page_missing')}/{d.get('trials')}")
    print(f"  preview fps: {h.get('preview', {}).get('fps')}  display create P50: {h.get('display_create', {}).get('ms', {}).get('p50')} ms  open_app P50: {h.get('open_app', {}).get('total_ms', {}).get('p50')} ms")
    if report.get("mika"):
        m = report["mika"]
        print(f"  mika batch step P50/P90: {m['batch_step_ms']['p50']} / {m['batch_step_ms']['p90']} ms (n={m['batch_step_ms']['n']})")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
