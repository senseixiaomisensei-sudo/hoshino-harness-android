"""Unit tests for tools/license-gate.py (PL1). Run: python3 -m unittest discover -s tools/tests"""
import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("license_gate", Path(__file__).resolve().parents[1] / "license-gate.py")
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
POLICY = gate.load_policy()

TREE = """
arm64FastRuntimeClasspath - Runtime classpath of '/arm64Fast'.
+--- project :core-bridge
|    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2
|    |    \\--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 -> 1.10.2 (*)
|    +--- com.squareup.okhttp3:okhttp:4.12.0
|    |    \\--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21 -> 2.2.20 (c)
+--- androidx.compose:compose-bom:2025.04.01
|    \\--- androidx.compose.material3:material3:1.3.2 (c)
+--- com.google.mlkit:text-recognition:16.0.1
|    \\--- com.google.android.datatransport:transport-runtime:3.1.8
\\--- androidx.core:core-ktx:1.16.0 -> 1.17.0
"""


class GradleTreeTest(unittest.TestCase):
    def test_parses_resolved_coordinates_and_skips_projects_and_constraints(self):
        coords = gate.parse_gradle_tree(TREE)
        self.assertIn(("com.squareup.okhttp3", "okhttp", "4.12.0"), coords)
        self.assertIn(("androidx.core", "core-ktx", "1.17.0"), coords)  # upgraded version wins
        self.assertNotIn(("androidx.compose.material3", "material3", "1.3.2"), coords)  # constraint
        self.assertFalse(any(c[1] == "core-bridge" for c in coords))

    def test_denied_groups_fail_without_any_cache(self):
        problems = gate.check_gradle(gate.parse_gradle_tree(TREE), POLICY, None)
        joined = "\n".join(problems)
        self.assertIn("com.google.mlkit:text-recognition:16.0.1", joined)
        self.assertIn("com.google.android.datatransport:transport-runtime", joined)
        self.assertEqual(2, len(problems))

    def test_pom_license_follows_parent_through_cache(self):
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d)
            def pom(g, a, v, body):
                p = cache / g / a / v / "abc123" / f"{a}-{v}.pom"
                p.parent.mkdir(parents=True)
                p.write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0">{body}</project>')
            pom("org.example", "parent", "1", "<licenses><license><name>The Apache Software License, Version 2.0</name></license></licenses>")
            pom("org.example", "child", "2", "<parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version></parent>")
            pom("org.closed", "sdk", "3", "<licenses><license><name>Proprietary</name></license></licenses>")
            pom("org.mystery", "lib", "4", "<licenses><license><name>Weird Custom Terms</name></license></licenses>")
            pom("org.none", "lib", "5", "")
            coords = {("org.example", "child", "2"), ("org.closed", "sdk", "3"), ("org.mystery", "lib", "4"), ("org.none", "lib", "5"), ("org.gone", "lib", "6")}
            problems = gate.check_gradle(coords, POLICY, cache)
            joined = "\n".join(problems)
            self.assertNotIn("org.example:child", joined)
            self.assertIn("org.closed:sdk:3: 许可证不允许", joined)
            self.assertIn("org.mystery:lib:4: 不认识的许可证", joined)
            self.assertIn("org.none:lib:5: POM 里没有许可证信息", joined)
            self.assertIn("org.gone:lib:6: Gradle 缓存里找不到 POM", joined)


class LicenseClassTest(unittest.TestCase):
    def test_classification(self):
        c = lambda t: gate.classify_license(t, POLICY)
        self.assertEqual("allowed", c("Apache License, Version 2.0"))
        self.assertEqual("allowed", c("MIT"))
        self.assertEqual("allowed", c("GNU Lesser General Public License v3"))
        self.assertEqual("allowed", c("Eclipse Public License - v 2.0"))
        self.assertEqual("denied", c("GPL-3.0"))
        self.assertEqual("allowed", c("GNU General Public License, version 2, with the Classpath Exception"))
        self.assertEqual("denied", c("Android Software Development Kit License"))
        self.assertEqual("denied", c("ML Kit Terms of Service"))
        self.assertEqual("unknown", c("Something Else"))
        self.assertEqual("unknown", c(""))

    def test_spdx_expressions(self):
        s = lambda t: gate.classify_spdx(t, POLICY)
        self.assertEqual("allowed", s("(MIT OR CC0-1.0)"))
        self.assertEqual("allowed", s("Apache-2.0 AND LGPL-3.0-or-later AND MIT"))
        self.assertEqual("denied", s("MIT AND AGPL-3.0"))
        self.assertEqual("allowed", s("GPL-3.0 OR MIT"))
        self.assertEqual("denied", s("UNLICENSED"))
        self.assertEqual("denied", s("SEE LICENSE IN LICENSE.md"))


class ApkTest(unittest.TestCase):
    def _apk(self, dex: bytes, manifest: bytes) -> Path:
        d = Path(tempfile.mkdtemp())
        p = d / "app.apk"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("classes.dex", dex)
            z.writestr("classes2.dex", b"dex\n035\x00Lapp/hoshino/harness/MainActivity;")
            z.writestr("AndroidManifest.xml", manifest)
        return p

    def test_clean_apk_passes(self):
        self.assertEqual([], gate.check_apk(self._apk(b"dex\n035\x00Landroidx/core/app/X;", "dev.hoshino.app".encode("utf-16-le")), POLICY))

    def test_denied_classes_and_manifest_components_fail(self):
        apk = self._apk(b"dex\n035\x00Lcom/google/firebase/FirebaseApp;Lcom/google/mlkit/vision/text/T;",
                        "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery".encode("utf-16-le"))
        problems = "\n".join(gate.check_apk(apk, POLICY))
        self.assertIn("Lcom/google/firebase/", problems)
        self.assertIn("Lcom/google/mlkit/", problems)
        self.assertIn("com.google.android.datatransport", problems)


class NpmTest(unittest.TestCase):
    def test_lockfile(self):
        lock = {"packages": {"": {}, "node_modules/a": {"version": "1", "license": "MIT"},
                             "node_modules/b": {"version": "2", "license": "UNLICENSED"},
                             "node_modules/c": {"version": "3"},
                             "node_modules/@firebase/app": {"version": "4", "license": "Apache-2.0"},
                             "node_modules/x/node_modules/d": {"version": "5", "license": {"type": "ISC"}}}}
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "package-lock.json"
            p.write_text(json.dumps(lock))
            problems = "\n".join(gate.check_npm(p, POLICY))
        self.assertIn("b@2: 许可证不允许", problems)
        self.assertIn("c@3: 许可证未知", problems)
        self.assertIn("@firebase/app: 禁止的 npm 包", problems)
        self.assertNotIn("a@1", problems)
        self.assertNotIn("d@5", problems)

    def test_repository_lockfile_passes(self):
        lock = Path(__file__).resolve().parents[2] / "node-host" / "package-lock.json"
        self.assertEqual([], gate.check_npm(lock, POLICY))


class CliTest(unittest.TestCase):
    def test_empty_report_is_a_failure(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "deps.txt"
            p.write_text("No dependencies\n")
            self.assertEqual(1, gate.main(["deps", str(p)]))


if __name__ == "__main__":
    unittest.main()
