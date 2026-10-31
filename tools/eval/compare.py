#!/usr/bin/env python3
"""EV2/EV7 对比报告：同机同模型下 Hoshino 与 Mika 各跑一遍题集后，读两边评测器的输出，写一份中文对比报告。

两边的评测器输出同一种格式（Hoshino 的 EvalReport / EvalRunTest 照抄 Mika）：
  <运行目录>/scores.jsonl   每题一行：id、passed、outcome、judged、wallMs、modelCalls、inputTokens、cachedTokens、outputTokens、metrics{firstFeedbackMs…}
  <运行目录>/summary.md     含「模型:」一行与「## 跳过」一节
  <运行目录>/run.json       （只有 Hoshino 有）运行元数据：模型、设备、能力、结局判定方式、跳过原因

指标（与 m2.9 出门标准、mika-gap.md §5 的口径一致）：
  完成率   = 通过题数 / 实跑题数（Mika 的「办成率」）；主比较只在两边都实跑了的「共同题」上算，免得跳过的题拉偏
  首个反馈 = metrics.firstFeedbackMs 的中位数 / P90（只在有这一项的题上取）
  模型往返 = modelCalls 中位数 / 平均
  Token    = 每题平均输入 / 输出；缓存命中率 = Σcached / Σinput
  耗时     = wallMs 中位数 / P90

只读文件、只写报告；不联网。用法：
  python3 tools/eval/compare.py --hoshino <目录> --mika <目录> [--tasks eval/tasks] [--bench <基准 JSON> …] [--out <目录>]
"""
from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SYSTEM_ACTION_CAPS = {"alarm", "calendar"}


# ---------------- 读取 ----------------

def read_scores(run_dir: Path) -> list[dict]:
    f = run_dir / "scores.jsonl"
    if not f.is_file():
        raise SystemExit(f"缺少 {f}")
    rows = []
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as e:
            raise SystemExit(f"{f}:{n} 不是 JSON：{e}")
    # 同一题跑了多次（续跑）时取最后一次
    return list({r["id"]: r for r in rows}.values())


def read_summary(run_dir: Path) -> dict:
    f = run_dir / "summary.md"
    text = f.read_text(encoding="utf-8") if f.is_file() else ""
    model = re.search(r"^模型[:：]([^;；\n]+)", text, re.M)
    skipped = {}
    sec = re.search(r"^## 跳过\s*\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    if sec:
        for m in re.finditer(r"^- ([^:：\n]+)[:：](.*)$", sec.group(1), re.M):
            skipped[m.group(1).strip()] = m.group(2).strip()
    judging = re.search(r"^结局判定[:：](.*)$", text, re.M)
    return {"model": model.group(1).strip() if model else None, "skipped": skipped, "judging": judging.group(1).strip() if judging else None}


def read_meta(run_dir: Path) -> dict:
    f = run_dir / "run.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.is_file() else {}


def read_tasks(tasks_dir: Path) -> dict[str, dict]:
    """id → {dir, category, partition, caps}；dir 是相对 tasks/ 的目录（read/system、write、web……）。"""
    out = {}
    if not tasks_dir.is_dir():
        return out
    for f in sorted(tasks_dir.rglob("*.json")):
        t = json.loads(f.read_text(encoding="utf-8"))
        caps = set()
        for s in t.get("apps", []) + t.get("requires", []):
            m = re.match(r"^\{app:([a-z]+)\}$", s) or re.match(r"^app:([a-z]+)$", s)
            if m:
                caps.add(m.group(1))
        out[t["id"]] = {"dir": f.parent.relative_to(tasks_dir).as_posix(), "category": t.get("category"), "partition": t.get("partition"), "caps": sorted(caps)}
    return out


# ---------------- 统计 ----------------

def median(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def p90(xs):
    """最近秩法：排序后第 ceil(0.9n) 个（与 EvalReport.p90 / RunStats.p90 相同）。"""
    xs = sorted(x for x in xs if x is not None)
    return xs[math.ceil(0.9 * len(xs)) - 1] if xs else None


def first_feedback(row: dict):
    m = row.get("metrics") or {}
    v = m.get("firstFeedbackMs")
    return v if isinstance(v, (int, float)) else None


def summarize(rows: list[dict]) -> dict:
    n = len(rows)
    passed = sum(1 for r in rows if r.get("passed"))
    inp = sum(r.get("inputTokens") or 0 for r in rows)
    cached = sum(r.get("cachedTokens") or 0 for r in rows)
    out = sum(r.get("outputTokens") or 0 for r in rows)
    calls = [r.get("modelCalls") for r in rows if r.get("modelCalls") is not None]
    return {
        "ran": n,
        "passed": passed,
        "completionRate": passed / n if n else None,
        "medianFirstFeedbackMs": median([first_feedback(r) for r in rows]),
        "p90FirstFeedbackMs": p90([first_feedback(r) for r in rows]),
        "firstFeedbackSamples": sum(1 for r in rows if first_feedback(r) is not None),
        "medianModelCalls": median(calls),
        "avgModelCalls": (sum(calls) / len(calls)) if calls else None,
        "avgInputTokens": inp / n if n else None,
        "avgOutputTokens": out / n if n else None,
        "inputTokens": inp,
        "cachedTokens": cached,
        "outputTokens": out,
        "cacheHitRate": cached / inp if inp else None,
        "medianWallMs": median([r.get("wallMs") for r in rows]),
        "p90WallMs": p90([r.get("wallMs") for r in rows]),
        "falseSuccess": sum(1 for r in rows if r.get("falseSuccess")),
    }


def compare(hoshino: list[dict], mika: list[dict], tasks: dict[str, dict]) -> dict:
    h = {r["id"]: r for r in hoshino}
    m = {r["id"]: r for r in mika}
    common = sorted(set(h) & set(m))
    groups: dict[str, list[str]] = {}
    for tid in common:
        info = tasks.get(tid, {})
        groups.setdefault(info.get("dir") or "(未知目录)", []).append(tid)
    by_dir = {d: {"tasks": len(ids), "hoshino": summarize([h[i] for i in ids]), "mika": summarize([m[i] for i in ids])} for d, ids in sorted(groups.items())}

    def gate(name, ids, margin_pp):
        if not ids:
            return {"name": name, "status": "无数据", "tasks": 0}
        hs, ms = summarize([h[i] for i in ids]), summarize([m[i] for i in ids])
        need = ms["completionRate"] - margin_pp / 100
        ok = hs["completionRate"] + 1e-9 >= need
        return {"name": name, "status": "达成" if ok else "未达成", "tasks": len(ids), "hoshino": hs["completionRate"], "mika": ms["completionRate"], "needAtLeast": max(0.0, need)}

    read_system = [i for i in common if tasks.get(i, {}).get("dir") == "read/system"]
    write = [i for i in common if tasks.get(i, {}).get("dir") == "write"]
    system_actions = [i for i in write if set(tasks.get(i, {}).get("caps", [])) & SYSTEM_ACTION_CAPS]
    per_task = []
    for tid in sorted(set(h) | set(m)):
        per_task.append({"id": tid, "dir": tasks.get(tid, {}).get("dir"), "hoshino": slim(h.get(tid)), "mika": slim(m.get(tid))})
    return {
        "common": len(common),
        "onlyHoshino": sorted(set(h) - set(m)),
        "onlyMika": sorted(set(m) - set(h)),
        "hoshino": summarize([h[i] for i in common]),
        "mika": summarize([m[i] for i in common]),
        "hoshinoAll": summarize(hoshino),
        "mikaAll": summarize(mika),
        "byDir": by_dir,
        "gates": [
            gate("read/system 完成率 ≥ Mika − 10 个百分点", read_system, 10),
            gate("write 完成率 ≥ Mika − 10 个百分点", write, 10),
            gate("系统动作类（闹钟/日程）完成率 ≥ Mika", system_actions, 0),
        ],
        "perTask": per_task,
    }


def slim(r: dict | None):
    if r is None:
        return None
    return {"passed": bool(r.get("passed")), "outcome": r.get("outcome"), "judged": r.get("judged"), "failureClass": r.get("failureClass"),
            "wallMs": r.get("wallMs"), "modelCalls": r.get("modelCalls"), "firstFeedbackMs": first_feedback(r),
            "inputTokens": r.get("inputTokens"), "cachedTokens": r.get("cachedTokens"), "outputTokens": r.get("outputTokens")}


# ---------------- 基准（EV7） ----------------

def read_bench(path: Path) -> dict:
    """benchmark-driver 的 m0-metrics.json 形状（startup.samples、各进程 PSS）或任意已有的 *-metrics.json；只取真有的数，没有的不编。"""
    d = json.loads(path.read_text(encoding="utf-8"))
    out = {"source": path.name}
    st = d.get("startup") or {}
    samples = st.get("samples") or []
    if samples:
        out["coldStartSamples"] = len(samples)
        out["iconToReadyMedianMs"] = median([s.get("iconToReadyMs") for s in samples])
        out["iconToFirstTokenMedianMs"] = median([s.get("iconToFirstTokenMs") for s in samples])
        for phase in ("idle", "streaming"):
            kinds: dict[str, list] = {}
            for s in samples:
                for p in s.get(phase) or []:
                    kinds.setdefault(p.get("kind"), []).append(p.get("pssKb"))
            if kinds:
                out[f"{phase}PssKbMedian"] = {k: median(v) for k, v in sorted(kinds.items())}
    for k in ("coldStartToInputMs", "kernelReadyMs", "idleMemory", "runningMemory", "apkBytes", "measurementAbi", "measurementApi", "measurementIsEmulator", "recordedAt", "version"):
        if k in d and d[k] is not None:
            out[k] = d[k]
    return out


# ---------------- 报告 ----------------

def pct(x):
    return "—" if x is None else f"{x * 100:.1f}%"


def secs(ms):
    return "—" if ms is None else f"{ms / 1000:.2f}"


def num(x, digits=1):
    return "—" if x is None else (f"{x:.{digits}f}".rstrip("0").rstrip(".") if isinstance(x, float) else str(x))


def delta_pp(a, b):
    return "—" if a is None or b is None else f"{(a - b) * 100:+.1f} pp"


def markdown(c: dict, meta: dict) -> str:
    L = []
    L.append(f"# Hoshino vs Mika 对比 {meta.get('title', '')}".rstrip())
    L.append("")
    L.append(f"- Hoshino 运行：`{meta['hoshinoDir']}`，模型 {meta.get('hoshinoModel') or '—'}")
    L.append(f"- Mika 运行：`{meta['mikaDir']}`，模型 {meta.get('mikaModel') or '—'}")
    if meta.get("device"):
        L.append(f"- 设备：{meta['device']}")
    if meta.get("modelMismatch"):
        L.append(f"- **模型不一致：{meta['modelMismatch']}。同机同模型的前提不成立，下面的差异不能当作超越证据。**")
    if meta.get("hoshinoJudging"):
        L.append(f"- Hoshino 结局判定：{meta['hoshinoJudging']}")
    L.append("- Mika 结局判定：Mika 自带的 OutcomeJudge（模型判定）。两边判法不同，「办成」的口径以核对项为主，判定只影响如实说做不成的题。")
    if meta.get("capabilities") is not None:
        L.append(f"- Hoshino 本机能力：{'、'.join(meta['capabilities']) or '无'}")
    L.append("")
    L.append(f"## 共同题（两边都实跑了的 {c['common']} 题）")
    L.append("")
    h, m = c["hoshino"], c["mika"]
    L.append("| 指标 | Hoshino | Mika | 差 |")
    L.append("|---|---|---|---|")
    L.append(f"| 完成率 | {h['passed']}/{h['ran']}（{pct(h['completionRate'])}） | {m['passed']}/{m['ran']}（{pct(m['completionRate'])}） | {delta_pp(h['completionRate'], m['completionRate'])} |")
    L.append(f"| 首个反馈中位数(秒) | {secs(h['medianFirstFeedbackMs'])} | {secs(m['medianFirstFeedbackMs'])} | {diff_s(h['medianFirstFeedbackMs'], m['medianFirstFeedbackMs'])} |")
    L.append(f"| 首个反馈 P90(秒) | {secs(h['p90FirstFeedbackMs'])} | {secs(m['p90FirstFeedbackMs'])} | {diff_s(h['p90FirstFeedbackMs'], m['p90FirstFeedbackMs'])} |")
    L.append(f"| 模型往返中位数 | {num(h['medianModelCalls'])} | {num(m['medianModelCalls'])} | {diff_n(h['medianModelCalls'], m['medianModelCalls'])} |")
    L.append(f"| 模型往返平均 | {num(h['avgModelCalls'])} | {num(m['avgModelCalls'])} | {diff_n(h['avgModelCalls'], m['avgModelCalls'])} |")
    L.append(f"| 每题平均输入 Token | {num(h['avgInputTokens'], 0)} | {num(m['avgInputTokens'], 0)} | {diff_n(h['avgInputTokens'], m['avgInputTokens'], 0)} |")
    L.append(f"| 每题平均输出 Token | {num(h['avgOutputTokens'], 0)} | {num(m['avgOutputTokens'], 0)} | {diff_n(h['avgOutputTokens'], m['avgOutputTokens'], 0)} |")
    L.append(f"| 缓存命中率(cached/input) | {pct(h['cacheHitRate'])} | {pct(m['cacheHitRate'])} | {delta_pp(h['cacheHitRate'], m['cacheHitRate'])} |")
    L.append(f"| 耗时中位数(秒) | {secs(h['medianWallMs'])} | {secs(m['medianWallMs'])} | {diff_s(h['medianWallMs'], m['medianWallMs'])} |")
    L.append(f"| 耗时 P90(秒) | {secs(h['p90WallMs'])} | {secs(m['p90WallMs'])} | {diff_s(h['p90WallMs'], m['p90WallMs'])} |")
    L.append(f"| 假成功(报成功但没过) | {h['falseSuccess']} | {m['falseSuccess']} | |")
    L.append("")
    L.append("首个反馈只在有运行小结的题上取（样本数 Hoshino %d、Mika %d）。差 = Hoshino − Mika；时间与往返越小越好。" % (h["firstFeedbackSamples"], m["firstFeedbackSamples"]))
    L.append("")
    L.append("## 出门标准（m2.9）")
    L.append("")
    L.append("| 门槛 | 题数 | Hoshino | Mika | 结论 |")
    L.append("|---|---|---|---|---|")
    for g in c["gates"]:
        L.append(f"| {g['name']} | {g['tasks']} | {pct(g.get('hoshino'))} | {pct(g.get('mika'))} | {g['status']} |")
    L.append("")
    L.append("## 按目录")
    L.append("")
    L.append("| 目录 | 题数 | Hoshino 完成率 | Mika 完成率 | 首个反馈中位(秒) H / M | 往返中位 H / M |")
    L.append("|---|---|---|---|---|---|")
    for d, g in c["byDir"].items():
        L.append(f"| {d} | {g['tasks']} | {pct(g['hoshino']['completionRate'])} | {pct(g['mika']['completionRate'])} | "
                 f"{secs(g['hoshino']['medianFirstFeedbackMs'])} / {secs(g['mika']['medianFirstFeedbackMs'])} | {num(g['hoshino']['medianModelCalls'])} / {num(g['mika']['medianModelCalls'])} |")
    L.append("")
    L.append("## 各自全部实跑的题（含对方跳过的）")
    L.append("")
    for name, s in (("Hoshino", c["hoshinoAll"]), ("Mika", c["mikaAll"])):
        L.append(f"- {name}：实跑 {s['ran']}，完成率 {pct(s['completionRate'])}，首个反馈中位 {secs(s['medianFirstFeedbackMs'])} 秒，往返中位 {num(s['medianModelCalls'])}，缓存命中 {pct(s['cacheHitRate'])}")
    L.append("")
    L.append("## 跳过")
    L.append("")
    for name, sk in (("Hoshino", meta.get("hoshinoSkipped") or {}), ("Mika", meta.get("mikaSkipped") or {})):
        L.append(f"### {name}（{len(sk)} 题）")
        L.append("")
        if not sk:
            L.append("无。")
        else:
            reasons: dict[str, list[str]] = {}
            for tid, why in sk.items():
                reasons.setdefault(why, []).append(tid)
            for why, ids in sorted(reasons.items(), key=lambda kv: -len(kv[1])):
                L.append(f"- {why}（{len(ids)}）：{'、'.join(sorted(ids))}")
        L.append("")
    if meta.get("bench"):
        L.append("## 基准（EV7：冷启动、首个 token、内存）")
        L.append("")
        L.append("来自 benchmark-driver 等基准文件，只列文件里真有的数；Mika 没有同类基准，只列 Hoshino。")
        L.append("")
        for b in meta["bench"]:
            L.append(f"- `{b['source']}`")
            for k, v in b.items():
                if k != "source":
                    L.append(f"  - {k}：{json.dumps(v, ensure_ascii=False)}")
        L.append("")
    L.append("## 每题")
    L.append("")
    L.append("| id | 目录 | Hoshino | Mika | 秒数 H / M | 往返 H / M | 首个反馈(秒) H / M |")
    L.append("|---|---|---|---|---|---|---|")
    for r in c["perTask"]:
        hh, mm = r["hoshino"], r["mika"]
        L.append(f"| {r['id']} | {r['dir'] or ''} | {cell(hh)} | {cell(mm)} | {secs(hh and hh['wallMs'])} / {secs(mm and mm['wallMs'])} | "
                 f"{num(hh and hh['modelCalls'])} / {num(mm and mm['modelCalls'])} | {secs(hh and hh['firstFeedbackMs'])} / {secs(mm and mm['firstFeedbackMs'])} |")
    L.append("")
    L.append("结果文件（scores.jsonl 含回答全文）不提交进仓库；引用这份报告时不放回答全文、截图、设备序列号和本机路径。")
    return "\n".join(L) + "\n"


def cell(r):
    if r is None:
        return "（没跑）"
    j = f"(判:{r['judged']})" if r.get("judged") else ""
    return ("过" if r["passed"] else "没过") + f" · {r['outcome']}{j}" + (f" · {r['failureClass']}" if r.get("failureClass") else "")


def diff_s(a, b):
    return "—" if a is None or b is None else f"{(a - b) / 1000:+.2f}"


def diff_n(a, b, digits=1):
    return "—" if a is None or b is None else f"{a - b:+.{digits}f}"


def model_id(s: str | None) -> str | None:
    """「provider/model」或「model」→ 模型 id（比较时不管服务商的名字）。"""
    if not s:
        return None
    return s.strip().split("/")[-1].strip().lower()


def run(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hoshino", required=True, type=Path)
    ap.add_argument("--mika", required=True, type=Path)
    ap.add_argument("--tasks", type=Path, default=ROOT / "eval" / "tasks")
    ap.add_argument("--bench", type=Path, action="append", default=[])
    ap.add_argument("--out", type=Path)
    ap.add_argument("--title", default="")
    ap.add_argument("--device", default="")
    ap.add_argument("--allow-model-mismatch", action="store_true", help="两边模型不同也出报告（报告里醒目标注）")
    a = ap.parse_args(argv)
    hs, ms = read_scores(a.hoshino), read_scores(a.mika)
    hsum, msum, hmeta = read_summary(a.hoshino), read_summary(a.mika), read_meta(a.hoshino)
    hmodel = hmeta.get("model") or hsum["model"]
    mmodel = msum["model"]
    mismatch = None
    if model_id(hmodel) and model_id(mmodel) and model_id(hmodel) != model_id(mmodel):
        mismatch = f"Hoshino {hmodel} ≠ Mika {mmodel}"
        if not a.allow_model_mismatch:
            print(f"模型不一致：{mismatch}（加 --allow-model-mismatch 仍出报告）", file=sys.stderr)
            return 2
    hskipped = {s["id"]: s["reason"] for s in hmeta.get("skipped", [])} or hsum["skipped"]
    c = compare(hs, ms, read_tasks(a.tasks))
    meta = {
        "title": a.title, "hoshinoDir": a.hoshino.name, "mikaDir": a.mika.name, "hoshinoModel": hmodel, "mikaModel": mmodel,
        "modelMismatch": mismatch, "hoshinoJudging": hmeta.get("judging") or hsum["judging"], "capabilities": hmeta.get("capabilities"),
        "device": a.device or hmeta.get("device"), "hoshinoSkipped": hskipped, "mikaSkipped": msum["skipped"],
        "bench": [read_bench(p) for p in a.bench],
    }
    out = a.out or a.hoshino.parent
    out.mkdir(parents=True, exist_ok=True)
    (out / "compare.md").write_text(markdown(c, meta), encoding="utf-8")
    (out / "compare.json").write_text(json.dumps({"meta": meta, **c}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"写好 {out / 'compare.md'}：共同题 {c['common']}，Hoshino {pct(c['hoshino']['completionRate'])} vs Mika {pct(c['mika']['completionRate'])}")
    return 0


if __name__ == "__main__":
    sys.exit(run())
