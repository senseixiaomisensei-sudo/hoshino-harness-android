#!/usr/bin/env python3
"""PL1 dependency license gate (CI step). No network, no third-party Python packages.

Subcommands
  deps  <gradle-dependencies.txt>... [--gradle-cache DIR]
        Parse `./gradlew :app:dependencies --configuration <x>RuntimeClasspath` output. Fail when a denied
        Maven group (ML Kit, Play Services, Firebase, datatransport, ...) is resolved, or when the POM license
        (followed through parent POMs in the Gradle cache) is closed source / unknown.
  apk   <file.apk>...
        Second line of defence after the build: scan every classes*.dex for denied class prefixes and the
        binary AndroidManifest.xml for denied strings (Firebase/GMS/datatransport components).
  npm   <package-lock.json>
        Every package in the Node host lockfile (shipped inside the runtime rootfs) must carry an open-source
        license; denied package names fail.

Exit code 1 with a readable list on any violation. Policy lives in tools/license-policy.json.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

POLICY_PATH = Path(__file__).with_name("license-policy.json")
COORD = re.compile(r"(?:^|[\s\\|+`-])([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.+\-]+)(?:\s*->\s*([A-Za-z0-9_.+\-]+))?")
PROJECT = re.compile(r"project\s+:")


def load_policy(path: Path = POLICY_PATH) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------------------------------------------------------- licenses

def classify_license(text: str, policy: dict) -> str:
    """'allowed', 'denied' or 'unknown' for one license name/url/SPDX expression."""
    t = (text or "").strip().lower()
    if not t:
        return "unknown"
    if any(re.search(p, t) for p in policy["deniedLicensePatterns"]):
        return "denied"
    if any(re.search(p, t) for p in policy["allowedLicensePatterns"]):
        return "allowed"
    return "unknown"


def classify_spdx(expr: str, policy: dict) -> str:
    """SPDX expression: AND needs every part allowed; OR needs one part allowed."""
    e = (expr or "").strip()
    if not e:
        return "unknown"
    inner = e.strip("()")
    if re.search(r"\sOR\s", inner):
        parts = [classify_spdx(p, policy) for p in re.split(r"\s+OR\s+", inner)]
        return "allowed" if "allowed" in parts else ("denied" if all(p == "denied" for p in parts) else "unknown")
    if re.search(r"\sAND\s", inner):
        parts = [classify_spdx(p, policy) for p in re.split(r"\s+AND\s+", inner)]
        return "denied" if "denied" in parts else ("allowed" if all(p == "allowed" for p in parts) else "unknown")
    return classify_license(e, policy)


# ---------------------------------------------------------------- gradle

def parse_gradle_tree(text: str) -> set[tuple[str, str, str]]:
    """Resolved (group, artifact, version) from a Gradle dependencies report; `a:b:1 -> 2` keeps 2; constraints and projects skipped."""
    out: set[tuple[str, str, str]] = set()
    for line in text.splitlines():
        if PROJECT.search(line) or "(c)" in line or "(n)" in line:
            continue
        if not re.match(r"^[\s|\\+`-]*[+\\]---", line):
            continue
        m = COORD.search(line)
        if not m:
            continue
        group, artifact, version, upgraded = m.groups()
        version = (upgraded or version).rstrip(")").strip()
        if version.startswith("{") or not version:
            continue
        out.add((group, artifact, version))
    return out


def find_pom(cache: Path, group: str, artifact: str, version: str) -> Path | None:
    base = cache / group / artifact / version
    if not base.is_dir():
        return None
    poms = sorted(base.glob(f"*/{artifact}-{version}.pom"))
    return poms[0] if poms else None


def _ns(tag: str) -> str:
    return tag.split("}", 1)[1] if "}" in tag else tag


def pom_licenses(pom: Path, cache: Path | None, depth: int = 0) -> list[str]:
    """License names/urls from a POM, following <parent> through the Gradle cache (max 6 levels)."""
    try:
        root = ElementTree.fromstring(pom.read_bytes())
    except (ElementTree.ParseError, OSError):
        return []
    found: list[str] = []
    parent = None
    for child in root:
        name = _ns(child.tag)
        if name == "licenses":
            for lic in child:
                parts = {_ns(x.tag): (x.text or "").strip() for x in lic}
                text = " ".join(v for k, v in parts.items() if k in ("name", "url") and v)
                if text:
                    found.append(text)
        elif name == "parent":
            parent = {_ns(x.tag): (x.text or "").strip() for x in child}
    if not found and parent and cache and depth < 6:
        p = find_pom(cache, parent.get("groupId", ""), parent.get("artifactId", ""), parent.get("version", ""))
        if p:
            return pom_licenses(p, cache, depth + 1)
    return found


def check_gradle(coords: set[tuple[str, str, str]], policy: dict, cache: Path | None) -> list[str]:
    problems: list[str] = []
    denied_groups = policy["deniedMavenGroups"]
    allowed = policy.get("allowedCoordinates", {})
    for group, artifact, version in sorted(coords):
        coord = f"{group}:{artifact}:{version}"
        if any(group == g or group.startswith(g + ".") for g in denied_groups):
            problems.append(f"{coord}: 禁止的依赖组(闭源 / Google Play Services / Firebase / ML Kit / datatransport)")
            continue
        if cache is None:
            continue
        if f"{group}:{artifact}" in allowed:
            continue
        pom = find_pom(cache, group, artifact, version)
        if pom is None:
            problems.append(f"{coord}: Gradle 缓存里找不到 POM,无法确认许可证(先解析依赖,或登记到 allowedCoordinates)")
            continue
        licenses = pom_licenses(pom, cache)
        verdicts = [classify_license(x, policy) for x in licenses]
        if not licenses:
            problems.append(f"{coord}: POM 里没有许可证信息(确认开源后登记到 tools/license-policy.json 的 allowedCoordinates)")
        elif "denied" in verdicts:
            problems.append(f"{coord}: 许可证不允许:{'; '.join(licenses)}")
        elif "allowed" not in verdicts:
            problems.append(f"{coord}: 不认识的许可证:{'; '.join(licenses)}")
    return problems


# ---------------------------------------------------------------- apk

def check_apk(apk: Path, policy: dict) -> list[str]:
    problems: list[str] = []
    classes = [p.encode() for p in policy["deniedClassPrefixes"]]
    manifest_strings = policy["deniedManifestStrings"]
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        if not dexes:
            problems.append(f"{apk.name}: 找不到 classes.dex,不是有效的 APK")
        for name in dexes:
            data = z.read(name)
            for prefix in classes:
                if b"L" + prefix in data:
                    problems.append(f"{apk.name}/{name}: 含禁止的类 L{prefix.decode()}…")
        if "AndroidManifest.xml" in z.namelist():
            data = z.read("AndroidManifest.xml")
            for s in manifest_strings:
                if s.encode("utf-8") in data or s.encode("utf-16-le") in data:
                    problems.append(f"{apk.name}/AndroidManifest.xml: 合并后的清单里有 {s}(组件会自注册上报)")
        else:
            problems.append(f"{apk.name}: 缺 AndroidManifest.xml")
    return problems


# ---------------------------------------------------------------- npm

def check_npm(lock_path: Path, policy: dict) -> list[str]:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    problems: list[str] = []
    for key, meta in sorted(lock.get("packages", {}).items()):
        if not key:
            continue
        name = key.split("node_modules/")[-1]
        if any(name == d or (d.endswith("/") and name.startswith(d)) for d in policy["deniedNpmNames"]):
            problems.append(f"{name}: 禁止的 npm 包(遥测 / 闭源 SaaS SDK)")
            continue
        lic = meta.get("license")
        if isinstance(lic, dict):
            lic = lic.get("type")
        if isinstance(lic, list):
            lic = " OR ".join(str(x.get("type", x)) if isinstance(x, dict) else str(x) for x in lic)
        verdict = classify_spdx(str(lic or ""), policy)
        if verdict != "allowed":
            problems.append(f"{name}@{meta.get('version', '?')}: 许可证{'不允许' if verdict == 'denied' else '未知'}:{lic!r}")
    return problems


# ---------------------------------------------------------------- cli

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--policy", type=Path, default=POLICY_PATH)
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("deps")
    d.add_argument("reports", nargs="+", type=Path)
    d.add_argument("--gradle-cache", type=Path)
    d.add_argument("--min", type=int, default=10, help="fail when fewer coordinates are parsed (guards against an empty report)")
    a = sub.add_parser("apk")
    a.add_argument("apks", nargs="+", type=Path)
    n = sub.add_parser("npm")
    n.add_argument("lock", type=Path)
    args = ap.parse_args(argv)
    policy = load_policy(args.policy)

    if args.cmd == "deps":
        coords: set[tuple[str, str, str]] = set()
        for r in args.reports:
            coords |= parse_gradle_tree(r.read_text(encoding="utf-8", errors="replace"))
        problems = check_gradle(coords, policy, args.gradle_cache)
        if len(coords) < args.min:
            problems.append(f"只解析出 {len(coords)} 个依赖坐标,报告可能是空的或格式变了")
        summary = f"{len(coords)} Maven coordinates checked"
    elif args.cmd == "apk":
        problems = [p for apk in args.apks for p in check_apk(apk, policy)]
        summary = f"{len(args.apks)} APK(s) scanned"
    else:
        problems = check_npm(args.lock, policy)
        summary = f"{len(json.loads(args.lock.read_text(encoding='utf-8')).get('packages', {})) - 1} npm packages checked"

    if problems:
        print(f"License gate FAILED ({summary}):")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"License gate passed: {summary}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
