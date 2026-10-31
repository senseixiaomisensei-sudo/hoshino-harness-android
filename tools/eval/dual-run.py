#!/usr/bin/env python3
"""EV2 双跑：同一台手机、同一个模型，先后驱动 Hoshino 与 Mika 各自的评测器（两边都是 androidTest 里的 EvalRunTest），
把结果拉回电脑，再用 compare.py 写对比报告。

前提（脚本会检查能查的，查不了的写在报告里）：
  1. 手机上装好 Hoshino（dev.hoshino.app）与它的测试包，Mika（com.wangzan101.mika）与它的测试包；
  2. 两边都配好了同一个模型：Hoshino 在设置里配供应商（或用 --hoshino-provider/--hoshino-model 指定），
     Mika 先用它的 LiveRunTest#configureProvider 配主模型。跑完比对两边汇总里的「模型:」，不一致默认不出报告；
  3. 不可逆类题两边默认都不跑；写类题会在手机上造带标记的数据（汇总末尾列出，人工清理）。

按题集分批：每一批先后跑两边（批与批之间交替先后，减少时段差异），批的列表用 --set 重复给（不给就整集一批）。

例：
  python3 tools/eval/dual-run.py --serial R5CX… --run m29a --set read/system --set web --split tune \\
      --hoshino-provider deepseek --hoshino-model deepseek-chat --out ~/hh-eval
  python3 tools/eval/dual-run.py --run m29a --dry-run     # 只打印要执行的命令
"""
from __future__ import annotations

import argparse
import shlex
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
HOSHINO = {"name": "hoshino", "package": "dev.hoshino.app", "runner": "dev.hoshino.app.test/androidx.test.runner.AndroidJUnitRunner",
           "cls": "app.hoshino.harness.eval.EvalRunTest"}
MIKA = {"name": "mika", "package": "com.wangzan101.mika", "runner": "com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner",
        "cls": "com.wangzan101.mika.eval.EvalRunTest"}
RUN_NAME = r"^[A-Za-z0-9_-]+$"


def instrument_args(side: dict, run: str, evalset: str | None, a) -> list[str]:
    """一边一批的 am instrument 参数。两边共有的参数名相同（Hoshino 的评测器照 Mika 的写）。"""
    extras = [("evalRun", run)]
    if evalset:
        extras.append(("evalSet", evalset))
    if a.split:
        extras.append(("evalSplit", a.split))
    if a.channel:
        extras.append(("evalChannel", a.channel))
    if a.on_takeover:
        extras.append(("evalOnTakeover", a.on_takeover))
    if a.price_in is not None:
        extras += [("evalPriceIn", str(a.price_in)), ("evalPriceOut", str(a.price_out)), ("evalPriceCached", str(a.price_cached if a.price_cached is not None else a.price_in))]
    if side["name"] == "hoshino":
        if a.hoshino_provider and a.hoshino_model:
            extras += [("evalProvider", a.hoshino_provider), ("evalModel", a.hoshino_model)]
        if a.effort:
            extras.append(("evalEffort", a.effort))
    else:
        # Mika 的提速开关默认 none；评测时都不学技能、不自动记忆（两边公平）
        extras.append(("evalFlags", a.mika_flags))
    cmd = ["shell", "am", "instrument", "-w", "-r", "-e", "class", side["cls"]]
    for k, v in extras:
        cmd += ["-e", k, v]
    return cmd + [side["runner"]]


def batch_name(run: str, side: str, evalset: str | None, i: int) -> str:
    return f"{run}-{side}" + (f"-{i + 1}" if evalset else "")


def plan(a) -> list[tuple[dict, str, str | None]]:
    """（哪一边、运行名、题集）的执行顺序：批与批之间交替先后。"""
    sets = a.set or [None]
    steps = []
    for i, s in enumerate(sets):
        order = [HOSHINO, MIKA] if (i % 2 == 0) == (a.first == "hoshino") else [MIKA, HOSHINO]
        for side in order:
            steps.append((side, batch_name(a.run, side["name"], s, i), s))
    return steps


def adb(a, args: list[str], check=True, capture=True) -> str:
    cmd = ["adb"] + (["-s", a.serial] if a.serial else []) + args
    if a.dry_run:
        print("$ " + " ".join(shlex.quote(x) for x in cmd))
        return ""
    p = subprocess.run(cmd, capture_output=capture, text=True)
    if check and p.returncode != 0:
        raise SystemExit(f"命令失败：{' '.join(cmd)}\n{p.stderr}")
    return p.stdout


def preflight(a):
    inst = adb(a, ["shell", "pm", "list", "instrumentation"])
    if a.dry_run:
        return
    for side in (HOSHINO, MIKA):
        if side["runner"].split("/")[0] not in inst:
            raise SystemExit(f"手机上没有 {side['name']} 的测试包（{side['runner']}）；先装 app 与 androidTest APK")


def merge_scores(dirs: list[Path], dest: Path):
    """一边的几批合成一个目录：scores.jsonl 拼起来，跳过的与模型行留第一批的汇总格式。"""
    dest.mkdir(parents=True, exist_ok=True)
    lines, skipped, model, judging, meta = [], [], None, None, None
    import json
    import re
    for d in dirs:
        f = d / "scores.jsonl"
        if f.is_file():
            lines += [l for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]
        s = (d / "summary.md").read_text(encoding="utf-8") if (d / "summary.md").is_file() else ""
        model = model or (re.search(r"^模型[:：]([^;；\n]+)", s, re.M) or [None, None])[1]
        judging = judging or (re.search(r"^结局判定[:：](.*)$", s, re.M) or [None, None])[1]
        sec = re.search(r"^## 跳过\s*\n(.*?)(?=^## |\Z)", s, re.M | re.S)
        if sec:
            skipped += [l for l in sec.group(1).splitlines() if l.startswith("- ")]
        if (d / "run.json").is_file():
            m = json.loads((d / "run.json").read_text(encoding="utf-8"))
            if meta is None:
                meta = m
            else:
                meta["skipped"] = meta.get("skipped", []) + m.get("skipped", [])
    (dest / "scores.jsonl").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    summary = [f"# 合并 {dest.name}", "", f"模型:{model or '—'};", ""]
    if judging:
        summary += [f"结局判定:{judging}", ""]
    summary += ["## 跳过", ""] + (skipped or ["无。"]) + [""]
    (dest / "summary.md").write_text("\n".join(summary), encoding="utf-8")
    if meta is not None:
        (dest / "run.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    ap.add_argument("--run", required=True)
    ap.add_argument("--set", action="append", help="题集（目录或 id 前缀），可重复；每个一批")
    ap.add_argument("--split", choices=["tune", "holdout"])
    ap.add_argument("--channel", choices=["web", "app"])
    ap.add_argument("--on-takeover", choices=["cancel", "wait"], default="cancel")
    ap.add_argument("--price-in", type=float)
    ap.add_argument("--price-out", type=float)
    ap.add_argument("--price-cached", type=float)
    ap.add_argument("--hoshino-provider")
    ap.add_argument("--hoshino-model")
    ap.add_argument("--effort", choices=["off", "low", "medium", "high", "max"])
    ap.add_argument("--mika-flags", default="none")
    ap.add_argument("--first", choices=["hoshino", "mika"], default="hoshino")
    ap.add_argument("--out", type=Path, default=Path("eval-out"))
    ap.add_argument("--bench", type=Path, action="append", default=[], help="并进报告的基准文件（EV7）")
    ap.add_argument("--allow-model-mismatch", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    import re
    if not re.match(RUN_NAME, a.run):
        raise SystemExit("--run 只能是字母数字、- 和 _")
    if (a.price_in is None) != (a.price_out is None):
        raise SystemExit("--price-in 与 --price-out 要一起给")
    preflight(a)
    pulled: dict[str, list[Path]] = {"hoshino": [], "mika": []}
    for side, name, evalset in plan(a):
        print(f"== {side['name']} {name} {evalset or '(全集)'}", flush=True)
        out = adb(a, instrument_args(side, name, evalset, a), check=False)
        if not a.dry_run and ("FAILURES!!!" in out or "INSTRUMENTATION_FAILED" in out or "Process crashed" in out):
            print(out[-4000:], file=sys.stderr)
            raise SystemExit(f"{side['name']} 评测器没有跑完（{name}）")
        local = a.out / side["name"] / name
        local.parent.mkdir(parents=True, exist_ok=True)
        adb(a, ["pull", f"/sdcard/Android/data/{side['package']}/files/eval/{name}", str(local.parent)])
        pulled[side["name"]].append(local)
    if a.dry_run:
        return 0
    merged = {}
    for side in ("hoshino", "mika"):
        merged[side] = a.out / side / f"{a.run}-{side}-all"
        merge_scores(pulled[side], merged[side])
    device = adb(a, ["shell", "getprop", "ro.product.model"]).strip()
    cmd = [sys.executable, str(HERE / "compare.py"), "--hoshino", str(merged["hoshino"]), "--mika", str(merged["mika"]), "--out", str(a.out),
           "--title", a.run, "--device", device]
    for b in a.bench:
        cmd += ["--bench", str(b)]
    if a.allow_model_mismatch:
        cmd.append("--allow-model-mismatch")
    return subprocess.call(cmd)


if __name__ == "__main__":
    sys.exit(main())
