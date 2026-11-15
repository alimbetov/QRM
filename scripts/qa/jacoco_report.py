#!/usr/bin/env python3
"""Extract measured Java line/branch coverage from Maven JaCoCo XML.

JaCoCo HTML alone cannot establish a numeric gate or reveal package hotspots
in CI logs; this script emits a reproducible JSON/Markdown baseline and can
apply explicit thresholds once the initial baseline is reviewed.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def counters(node):
    return {
        element.attrib["type"]: {
            "covered": int(element.attrib["covered"]),
            "missed": int(element.attrib["missed"]),
        }
        for element in node.findall("counter")
    }


def percentage(metric):
    total = metric["covered"] + metric["missed"]
    return round(100 * metric["covered"] / total, 2) if total else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", default="backend/target/site/jacoco/jacoco.xml")
    parser.add_argument("--json", default="backend/target/qa/jacoco-summary.json")
    parser.add_argument("--min-line", type=float, default=None)
    parser.add_argument("--min-branch", type=float, default=None)
    args = parser.parse_args()

    source = Path(args.xml)
    if not source.is_file():
        print(f"QA-02: JaCoCo XML missing: {source}", file=sys.stderr)
        return 2

    root = ET.parse(source).getroot()
    overall = counters(root)
    critical_prefixes = (
        "kz/zakaz/catalog", "kz/zakaz/deal",
        "kz/zakaz/inventory", "kz/zakaz/order",
        "kz/zakaz/publicqr", "kz/zakaz/security",
        "kz/zakaz/intelligence", "kz/zakaz/media",
    )
    packages = []
    for pkg in root.findall("package"):
        name = pkg.attrib["name"]
        if not name.startswith(critical_prefixes):
            continue
        c = counters(pkg)
        packages.append({
            "name": name,
            "line": {"percent": percentage(c["LINE"]), **c["LINE"]},
            "branch": {"percent": percentage(c["BRANCH"]), **c["BRANCH"]},
        })
    packages.sort(key=lambda p: (
        p["branch"]["percent"] is None,
        p["branch"]["percent"] or 0,
        p["name"],
    ))
    result = {
        "source": str(source),
        "total": {
            metric.lower(): {"percent": percentage(values), **values}
            for metric, values in overall.items()
        },
        "critical_packages": packages,
    }
    output = Path(args.json)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def percent_text(metric):
        n = metric["percent"]
        return "N/A" if n is None else f"{n:.2f}%"

    total_line = result["total"]["line"]
    total_branch = result["total"]["branch"]
    md = [
        "### QA-02 — actual JaCoCo baseline",
        "",
        f"- **Overall line coverage:** {percent_text(total_line)} "
        f"({total_line['covered']} covered / {total_line['missed']} missed)",
        f"- **Overall branch coverage:** {percent_text(total_branch)} "
        f"({total_branch['covered']} covered / {total_branch['missed']} missed)",
        "",
        "| Critical package | Line % | Branch % | Missed branches |",
        "| --- | ---: | ---: | ---: |",
    ]
    for p in packages:
        md.append(f"| `{p['name']}` | {percent_text(p['line'])} | "
                  f"{percent_text(p['branch'])} | {p['branch']['missed']} |")
    report = "\n".join(md) + "\n"
    print(report, flush=True)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as target:
            target.write(report)

    if args.min_line is not None and (
        total_line["percent"] is None or total_line["percent"] < args.min_line
    ):
        print(f"QA-02 gate FAILED: line < {args.min_line}%", file=sys.stderr)
        return 1
    if args.min_branch is not None and (
        total_branch["percent"] is None or total_branch["percent"] < args.min_branch
    ):
        print(f"QA-02 gate FAILED: branch < {args.min_branch}%", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
