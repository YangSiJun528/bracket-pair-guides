"""Read existing Driver/Qodana evidence; never launch or alter either application.

Print JSON to stdout. A pass requires recorded execution, exact scenario coverage,
and source/baseline identity, not merely an artifact directory. Qodana scope here
means the verified complete production source snapshot supplied to the linter;
it is not a claim that SARIF enumerates every analyzed file.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ENVIRONMENT = "ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1"
SCENARIOS = {"horizontal-only", "vertical-only", "pair-border-only", "pair-background-only",
             "all-components", "bracket-colorization-off", "plugin-disabled",
             "native-visuals-unmanaged", "native-highlight-suppressed", "default-palette", "custom-palette"}
OWNERS = ("analysis-model", "analysis-core", "editor-ui", "analysis-runtime", "plugin")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def baseline_hashes(repository):
    root = repository / "plugin/src/visualTest/resources/baselines" / ENVIRONMENT
    files = {path.stem: sha(path) for path in root.glob("*.png")}
    require(set(files) == SCENARIOS, "Baseline scenario coverage differs from the exact eleven scenarios")
    return files


def driver(args):
    run = args.run.resolve()
    code = int((run / "exit-code.txt").read_text().strip())
    require(code == 0, f"Driver container exit {code}")
    totals = Counter()
    testcases = []
    xmls = list((run / "junit-results").glob("TEST-*.xml"))
    require(xmls, "Driver JUnit XML is missing")
    for path in xmls:
        root = ET.parse(path).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(root.get(key, 0))
        testcases.extend((case.get("classname"), case.get("name")) for case in root.iter("testcase"))
    require(totals == Counter(tests=13, failures=0, errors=0, skipped=0), f"Unexpected Driver totals: {dict(totals)}")
    require(any(owner == "com.sijunyang.bracketpairguides.visual.BracketGuideVisualTest" and name.removesuffix("()") == "coreVisualScenariosMatchExactBaselinesInOneIdeSession" for owner, name in testcases),
            "The production Driver screenshot scenario test did not run")
    artifacts = run / "visual-test-artifacts"
    captures = {path.name.removesuffix("-actual.png"): sha(path) for path in artifacts.glob("*-actual.png")}
    require(set(captures) == SCENARIOS, "Driver actual image scenario coverage differs from eleven")
    current = baseline_hashes(args.repository)
    before = json.loads(args.baseline_before.read_text())
    require(current == before, "Committed baseline hashes changed since the pre-run snapshot")
    require(captures == current, "Actual PNG bytes differ from baselines; inspect exact pixel assertions and images")
    geometry = json.loads((artifacts / "ui-geometry.json").read_text())
    require(geometry["environment"] == ENVIRONMENT and geometry["theme"] == "Darcula", "Driver rendering environment differs")
    require(geometry["crop"] == {"x": 0, "y": 1, "width": 220, "height": 239}, "Driver image crop changed")
    require(geometry["testRuntime"].startswith("21."), "Driver test Java runtime is not21")
    paths = json.loads((artifacts / "starter-paths.json").read_text())
    require("IC-242.26775.15" in paths["testHome"], "Starter IDE identity differs from pinned242.26775.15")
    environment = (run / "environment.txt").read_text()
    require("Linux" in environment and "x86_64" in environment, "Driver host is not Linuxx86_64")
    require("BUILD SUCCESSFUL" in (run / "visual-test-gradle.log").read_text(), "Driver Gradle success marker missing")
    return {"kind": "driver", "passed": True, "directory": str(run), "exitCode": code, "tests": dict(totals),
            "actualScreenshots": len(captures), "baselineHashesUnchanged": True, "actualPngBytesMatch": True,
            "actualSha256": captures, "environment": ENVIRONMENT,
            "limit": "Byte equality is stronger than the unchanged decoded-pixel oracle; no baseline recording occurred."}


def production_hashes(repository):
    files = [repository / name for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "qodana.yml")]
    for owner in OWNERS:
        files.append(repository / owner / "build.gradle.kts")
        files.extend(path for path in (repository / owner / "src/main").rglob("*") if path.is_file())
    files.extend(path for path in (repository / "licenses").rglob("*") if path.is_file())
    return {path.relative_to(repository).as_posix(): sha(path) for path in files}


def qodana(args):
    code = int(args.exit_code_file.read_text().strip())
    require(code == 0, f"Actual Qodana Docker command exit {code}")
    sarif = args.results / "qodana.sarif.json"
    document = json.loads(sarif.read_text())
    runs = document.get("runs", [])
    require(runs, "Qodana SARIF has no analysis runs")
    results = []
    for run in runs:
        invocations = run.get("invocations", [])
        require(invocations and all(inv.get("executionSuccessful") is True and inv.get("exitCode") == 0 for inv in invocations),
                "Qodana SARIF does not record successful analysis invocation")
        properties = run.get("properties", {})
        require(properties.get("configProfile") == "recommended", "Qodana profile differs from recommended")
        require(properties.get("qodanaFailureConditions", {}).get("severityThresholds", {}).get("any") == 0,
                "Qodana zero-findings failure threshold is missing/weakened")
        results.extend(run.get("results", []))
        require(properties.get("qodanaNewResultSummary", {}).get("total") == 0, "Qodana reports new findings")
    require(not results, f"Qodana SARIF has {len(results)} findings, including suppressed or baseline results")
    log = args.log.read_text()
    for marker in ("The Project opening stage completed", "The Project configuration stage completed",
                   "The Project analysis stage completed", "Analysis results: 0 problem detected"):
        require(marker in log, f"Qodana completion marker missing: {marker}")
    current = production_hashes(args.repository)
    captured = production_hashes(args.source)
    require(current == captured, "Qodana source snapshot differs from current production inputs")
    return {"kind": "qodana", "passed": True, "directory": str(args.results.resolve()), "exitCode": code,
            "problems": len(results), "profile": "qodana.recommended", "failThreshold": 0,
            "sarif": str(sarif.resolve()), "sarifSha256": sha(sarif), "sourceSnapshot": str(args.source.resolve()),
            "verifiedProductionInputs": len(current), "sourceSha256": current, "analysisRuns": len(runs),
            "scopeLimit": "Verified supplied production files for all five modules; zero-result SARIF does not list inspected-file coverage."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[3])
    sub = parser.add_subparsers(dest="kind", required=True)
    sub.add_parser("snapshot-baselines")
    visual = sub.add_parser("driver")
    visual.add_argument("--run", type=Path, required=True)
    visual.add_argument("--baseline-before", type=Path, required=True)
    lint = sub.add_parser("qodana")
    for flag in ("results", "source", "log", "exit-code-file"):
        lint.add_argument("--" + flag, type=Path, required=True)
    args = parser.parse_args()
    args.repository = args.repository.resolve()
    try:
        result = baseline_hashes(args.repository) if args.kind == "snapshot-baselines" else driver(args) if args.kind == "driver" else qodana(args)
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        print(json.dumps({"kind": args.kind, "passed": False, "error": str(error)}, indent=2))
        return 1
    print(json.dumps(result, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
