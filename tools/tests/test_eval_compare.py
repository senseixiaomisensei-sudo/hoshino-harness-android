"""Unit tests for tools/eval/compare.py and dual-run.py (EV2/EV7). Run: python3 -m unittest discover -s tools/tests"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / "eval" / file)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


compare = load("eval_compare", "compare.py")
dual = load("eval_dual_run", "dual-run.py")


def row(tid, passed, wall, calls, ff=None, inp=1000, cached=0, out=100, outcome="succeeded", judged=None):
    r = {"id": tid, "passed": passed, "falseSuccess": outcome == "succeeded" and not passed, "outcome": outcome, "judged": judged,
         "wallMs": wall, "modelCalls": calls, "inputTokens": inp, "cachedTokens": cached, "outputTokens": out, "failureClass": None if passed else "unknown"}
    if ff is not None:
        r["metrics"] = {"firstFeedbackMs": ff}
    return r


def write_run(d: Path, rows, model, skipped=(), meta=None):
    d.mkdir(parents=True)
    (d / "scores.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    sk = "\n".join(f"- {i}:{why}" for i, why in skipped) or "无。"
    (d / "summary.md").write_text(f"# 评测汇总 x\n\n模型:{model};开关:none;实跑 {len(rows)} 题\n\n## 指标\n\n| a | b |\n\n## 跳过\n\n{sk}\n\n## 离开题目范围的 App\n\n无。\n", encoding="utf-8")
    if meta:
        (d / "run.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.tasks = compare.read_tasks(ROOT / "eval" / "tasks")

    def tearDown(self):
        self.tmp.cleanup()

    def test_reads_the_real_task_tree(self):
        self.assertEqual(self.tasks["calculator-multiply"]["dir"], "read/system")
        self.assertIn("calendar", self.tasks["calendar-event-tonight"]["caps"])
        self.assertEqual(self.tasks["web-python-latest"]["dir"], "web")
        self.assertGreaterEqual(len(self.tasks), 128)

    def test_common_set_metrics_and_gates(self):
        h = [row("calculator-multiply", True, 10_000, 3, ff=900, inp=2000, cached=1000), row("weather-moon-temperature", False, 20_000, 5, ff=1100),
             row("alarm-create-label", True, 30_000, 7, ff=1500), row("web-python-latest", True, 5_000, 2, ff=700)]
        m = [row("calculator-multiply", True, 12_000, 4, ff=1200), row("weather-moon-temperature", True, 22_000, 6, ff=1400),
             row("alarm-create-label", False, 40_000, 9, ff=2000)]
        c = compare.compare(h, m, self.tasks)
        self.assertEqual(c["common"], 3)
        self.assertEqual(c["onlyHoshino"], ["web-python-latest"])
        self.assertAlmostEqual(c["hoshino"]["completionRate"], 2 / 3)
        self.assertAlmostEqual(c["mika"]["completionRate"], 2 / 3)
        self.assertEqual(c["hoshino"]["medianFirstFeedbackMs"], 1100)
        self.assertEqual(c["mika"]["p90FirstFeedbackMs"], 2000)
        self.assertEqual(c["hoshino"]["medianModelCalls"], 5)
        self.assertAlmostEqual(c["hoshino"]["cacheHitRate"], 1000 / 4000)
        self.assertEqual(c["hoshinoAll"]["ran"], 4)
        gates = {g["name"]: g for g in c["gates"]}
        rs = next(g for n, g in gates.items() if n.startswith("read/system"))
        # calculator-multiply 在 read/system；weather-moon-temperature 在 impossible；alarm-create-label 在 write（系统动作类）
        self.assertEqual((rs["tasks"], rs["hoshino"], rs["mika"], rs["status"]), (1, 1.0, 1.0, "达成"))
        sa = next(g for n, g in gates.items() if n.startswith("系统动作类"))
        self.assertEqual((sa["tasks"], sa["hoshino"], sa["mika"], sa["status"]), (1, 1.0, 0.0, "达成"))
        self.assertEqual(set(c["byDir"]), {"read/system", "impossible", "write"})

    def test_read_system_gate_fails_when_more_than_ten_points_behind(self):
        h = [row("calculator-multiply", True, 1, 1), row("calculator-divide", False, 1, 1)]
        m = [row("calculator-multiply", True, 1, 1), row("calculator-divide", True, 1, 1)]
        g = compare.compare(h, m, self.tasks)["gates"][0]
        self.assertEqual((g["tasks"], g["hoshino"], g["mika"], g["status"]), (2, 0.5, 1.0, "未达成"))
        sys_action = compare.compare([row("alarm-create-label", True, 1, 1)], [row("alarm-create-label", True, 1, 1)], self.tasks)["gates"][2]
        self.assertEqual(sys_action["status"], "达成")
        self.assertEqual(compare.compare([], [], self.tasks)["gates"][1]["status"], "无数据")

    def test_end_to_end_report_and_model_mismatch(self):
        hd, md = self.root / "h", self.root / "m"
        write_run(hd, [row("calculator-multiply", True, 9_000, 3, ff=800), row("web-python-latest", True, 5_000, 2, ff=600)], "deepseek/deepseek-chat",
                  meta={"model": "deepseek/deepseek-chat", "judging": "文字判定", "capabilities": ["sandbox", "web"],
                        "skipped": [{"id": "alarm-workday", "reason": "缺能力:phone(手机操作,m2.8)"}]})
        write_run(md, [row("calculator-multiply", True, 11_000, 4, ff=1000), row("web-python-latest", False, 7_000, 3, ff=900)], "deepseek-chat",
                  skipped=[("send-wechat", "不可逆类题默认不跑(要跑须传 evalAllowIrreversible=true)")])
        out = self.root / "out"
        self.assertEqual(compare.run(["--hoshino", str(hd), "--mika", str(md), "--out", str(out), "--bench", str(ROOT / "docs" / "m0-metrics.json")]), 0)
        text = (out / "compare.md").read_text(encoding="utf-8")
        self.assertIn("| 完成率 | 2/2（100.0%） | 1/2（50.0%） | +50.0 pp |", text)
        self.assertIn("缺能力:phone(手机操作,m2.8)（1）：alarm-workday", text)
        self.assertIn("send-wechat", text)
        self.assertIn("Hoshino 结局判定：文字判定", text)
        self.assertIn("iconToFirstTokenMedianMs", text)
        data = json.loads((out / "compare.json").read_text(encoding="utf-8"))
        self.assertEqual(data["common"], 2)
        # 模型不同：默认拒绝出报告
        md2 = self.root / "m2"
        write_run(md2, [row("calculator-multiply", True, 1, 1)], "qwen-max")
        self.assertEqual(compare.run(["--hoshino", str(hd), "--mika", str(md2), "--out", str(self.root / "o2")]), 2)
        self.assertEqual(compare.run(["--hoshino", str(hd), "--mika", str(md2), "--out", str(self.root / "o2"), "--allow-model-mismatch"]), 0)
        self.assertIn("模型不一致", (self.root / "o2" / "compare.md").read_text(encoding="utf-8"))

    def test_bench_only_reports_numbers_present_in_the_file(self):
        b = compare.read_bench(ROOT / "docs" / "m0-metrics.json")
        self.assertGreaterEqual(b["coldStartSamples"], 5)
        self.assertIn("node-native", b["streamingPssKbMedian"])
        p = self.root / "m2.json"
        p.write_text(json.dumps({"version": "0.2.0-m2", "coldStartToInputMs": None, "kernelReadyMs": None}), encoding="utf-8")
        self.assertEqual(compare.read_bench(p), {"source": "m2.json", "version": "0.2.0-m2"})

    def test_p90_nearest_rank_matches_kotlin(self):
        self.assertEqual(compare.p90([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]), 9)
        self.assertEqual(compare.p90([5]), 5)
        self.assertIsNone(compare.p90([]))


class DualRunTest(unittest.TestCase):
    def args(self, *extra):
        import argparse
        ns = argparse.Namespace(split="tune", channel=None, on_takeover="cancel", price_in=None, price_out=None, price_cached=None,
                                hoshino_provider="deepseek", hoshino_model="deepseek-chat", effort=None, mika_flags="none",
                                set=["read/system", "web", "write"], first="hoshino", run="m29")
        for k, v in extra:
            setattr(ns, k, v)
        return ns

    def test_batches_alternate_which_side_goes_first(self):
        steps = [(s["name"], name, es) for s, name, es in dual.plan(self.args())]
        self.assertEqual([s for s, _, _ in steps], ["hoshino", "mika", "mika", "hoshino", "hoshino", "mika"])
        self.assertEqual(steps[0][1], "m29-hoshino-1")
        self.assertEqual(steps[3], ("hoshino", "m29-hoshino-2", "web"))

    def test_instrument_arguments_per_side(self):
        a = self.args()
        h = dual.instrument_args(dual.HOSHINO, "m29-hoshino-1", "web", a)
        m = dual.instrument_args(dual.MIKA, "m29-mika-1", "web", a)
        self.assertEqual(h[-1], "dev.hoshino.app.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertIn("app.hoshino.harness.eval.EvalRunTest", h)
        for k, v in [("evalRun", "m29-hoshino-1"), ("evalSet", "web"), ("evalSplit", "tune"), ("evalProvider", "deepseek"), ("evalModel", "deepseek-chat")]:
            i = h.index(k)
            self.assertEqual((h[i - 1], h[i + 1]), ("-e", v))
        self.assertNotIn("evalProvider", m)
        self.assertIn("evalFlags", m)
        self.assertEqual(m[-1], "com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner")

    def test_merge_batches(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            write_run(t / "a", [row("calculator-multiply", True, 1, 1)], "x/m", meta={"model": "x/m", "skipped": [{"id": "a1", "reason": "r"}]})
            write_run(t / "b", [row("web-python-latest", True, 1, 1)], "x/m", skipped=[("b1", "r2")], meta={"model": "x/m", "skipped": [{"id": "b1", "reason": "r2"}]})
            dual.merge_scores([t / "a", t / "b"], t / "all")
            self.assertEqual(len(compare.read_scores(t / "all")), 2)
            self.assertEqual({s["id"] for s in json.loads((t / "all" / "run.json").read_text())["skipped"]}, {"a1", "b1"})
            self.assertEqual(compare.read_summary(t / "all")["model"], "x/m")


if __name__ == "__main__":
    unittest.main()
