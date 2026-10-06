"""Audit production ownership and compile probes with exported real compiler inputs."""

from functools import cache
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ALLOWED = {
    "analysis-model": set(),
    "analysis-core": {"analysis-model"},
    "editor-ui": {"analysis-model"},
    "analysis-runtime": {"analysis-model", "analysis-core", "editor-ui"},
    "plugin": {"analysis-model", "editor-ui", "analysis-runtime"},
}
MODEL = "com.sijunyang.bracketpairguides.analysis.AnalysisCoverage"
CORE = "com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine"
RUNTIME = "com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis"
IDE = "com.intellij.openapi.editor.Editor"
CORE_TYPES = [
    CORE,
    "com.sijunyang.bracketpairguides.analysis.snapshot.SnapshotCalculation",
    "com.sijunyang.bracketpairguides.analysis.guide.GuideRepairCalculation",
    "com.sijunyang.bracketpairguides.analysis.snapshot.BracketIndexes",
    "com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex",
    "com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex.Companion",
    "com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex",
    "com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex.Companion",
]
RUNTIME_TYPES = [
    RUNTIME,
    "com.sijunyang.bracketpairguides.analysis.intellij.BracketTokenCapture",
    "com.sijunyang.bracketpairguides.editor.highlighting.EditorAnalysisExecution",
]


@cache
def classes(path):
    path = Path(path)
    if path.is_dir():
        return {p.relative_to(path).as_posix() for p in path.rglob("*.class")}
    if path.is_file() and zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return {name for name in archive.namelist() if name.endswith(".class")}
    return set()


# Production free arguments are intentionally limited to diagnostics. Any new
# option must be reviewed here before it can introduce a source/module path,
# compiler plugin, argfile, output directory, or another visibility mechanism.
# Compiler language/JVM settings already use Gradle's typed compiler options.
SAFE_KOTLIN_ARGUMENTS = {"-nowarn", "-Werror", "-verbose", "-Xreport-perf"}
SAFE_JAVA_ARGUMENTS = {"-nowarn", "-Werror", "-verbose", "-parameters", "-g", "-g:none"}


def unsafe_compiler_arguments(module):
    unsafe = [argument for argument in module["compilerArguments"] if argument not in SAFE_KOTLIN_ARGUMENTS]
    for argument in module["javaCompilerArguments"]:
        if argument in SAFE_JAVA_ARGUMENTS:
            continue
        if argument.startswith("-Xlint:"):
            continue
        if argument.startswith("-g:") and set(argument[3:].split(",")) <= {"source", "lines", "vars"}:
            continue
        unsafe.append(argument)
    return unsafe


def audit(modules):
    roots = {}
    owned_classes = {}
    for name, module in modules.items():
        source_root = (Path(module["projectDirectory"]) / "src/main").resolve()
        for source in map(Path, module["sourceFiles"]):
            if source_root not in source.resolve().parents:
                raise ValueError(f"{name} compiler consumes a production source outside its owner: {source}")
        unsafe = unsafe_compiler_arguments(module)
        if module["friendPaths"] or module["compilerPlugins"] or unsafe:
            raise ValueError(f"{name} production compiler contains friends, compiler plugins or visibility overrides: {unsafe}")
        unexpected = set(module["projects"]) - ALLOWED[name] - {name}
        if unexpected:
            raise ValueError(f"{name} compile dependency leak: {sorted(unexpected)}")
        for kind in ("sources", "outputs"):
            for root in map(Path, module[kind]):
                resolved = root.resolve()
                for previous, previous_name in roots.get(kind, []):
                    if previous_name != name and (resolved == previous or resolved in previous.parents or previous in resolved.parents):
                        raise ValueError(f"Shared {kind}: {name} and {previous_name}: {resolved}")
                roots.setdefault(kind, []).append((resolved, name))
        for output in module["outputs"]:
            for bytecode in classes(output):
                previous = owned_classes.setdefault(bytecode, name)
                if previous != name:
                    raise ValueError(f"Duplicate production class {bytecode}: {previous}, {name}")
    for name, module in modules.items():
        forbidden = {class_name for class_name, owner in owned_classes.items() if owner not in ALLOWED[name] | {name}}
        for kind in ("javaClasspath", "kotlinClasspath"):
            for path in module[kind]:
                directory = Path(path)
                # javac defaults its source path to the classpath when no source
                # path is specified. Reject source-bearing directory entries so
                # foreign .java declarations cannot hide behind an empty class inventory.
                if directory.is_dir() and any(file.suffix in {".java", ".kt", ".kts"} for file in directory.rglob("*") if file.is_file()):
                    raise ValueError(f"{name} {kind} contains implicit compiler source roots: {path}")
                contents = classes(path)
                leaked = contents & forbidden
                if leaked:
                    raise ValueError(f"{name} {kind} contains forbidden bytecode from {path}: {sorted(leaked)[:5]}")
                if name in ("analysis-model", "analysis-core") and any(c.startswith("com/intellij/") for c in contents):
                    raise ValueError(f"{name} resolves IntelliJ SDK bytecode: {path}")


def compile_probe(manifest, name, language, target, expected, root):
    module = manifest["modules"][name]
    directory = root / f"{name}-{language}-{target.replace('.', '-')}"
    directory.mkdir()
    output = directory / "classes"
    output.mkdir()
    classpath = os.pathsep.join(module[f"{language}Classpath"])
    if language == "java":
        source = directory / "Probe.java"
        source.write_text(f"final class Probe {{ {target} value; }}\n")
        command = [manifest["javac"], "--release", "17", "-proc:none", "-classpath", classpath, "-d", str(output), str(source)]
    else:
        source = directory / "Probe.kt"
        source.write_text(f"class Probe {{ lateinit var value: {target}{'<' + 'Any, Any' + '>' if target == CORE else ''} }}\n")
        command = [manifest["java"], "-cp", os.pathsep.join(manifest["compilerClasspath"]),
                   "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-language-version", "1.9", "-api-version", "1.9",
                   "-jvm-target", "17", "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", str(output), str(source)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    diagnostics = result.stdout + result.stderr
    if expected:
        if result.returncode:
            raise ValueError(f"Positive {name}/{language}/{target} probe failed:\n{diagnostics}")
    elif result.returncode == 0:
        raise ValueError(f"Forbidden {name}/{language}/{target} probe compiled successfully")
    elif any(message in diagnostics.lower() for message in ("cannot access", "is internal in", "is not public in", "not exported")):
        raise ValueError(f"Negative {name}/{language}/{target} exposed an inaccessible implementation instead of proving absence:\n{diagnostics}")
    elif language == "kotlin" and "unresolved reference" not in diagnostics.lower():
        raise ValueError(f"Negative {name}/{language}/{target} failed for an unexpected reason:\n{diagnostics}")
    elif language == "java" and not any(message in diagnostics for message in ("does not exist", "cannot find symbol")):
        raise ValueError(f"Negative {name}/{language}/{target} failed for an unexpected reason:\n{diagnostics}")
    print(f"PASS {name}/{language}: {target} {'visible' if expected else 'unavailable'}")
    return {"module": name, "language": language, "target": target, "expectedVisible": expected, "diagnostics": diagnostics}


def main():
    manifest_path = Path(sys.argv[1])
    manifest = json.loads(manifest_path.read_text())
    audit(manifest["modules"])
    probes = []
    with tempfile.TemporaryDirectory(prefix="module-compile-probes-") as temporary:
        root = Path(temporary)
        for name in manifest["modules"]:
            negatives = [IDE] if name in ("analysis-core", "analysis-model") else CORE_TYPES + RUNTIME_TYPES if name == "editor-ui" else CORE_TYPES[:4] if name == "plugin" else []
            for language in ("java", "kotlin"):
                probes.append(compile_probe(manifest, name, language, "kotlin.Unit" if name == "analysis-model" else MODEL, True, root))
                for target in negatives:
                    probes.append(compile_probe(manifest, name, language, target, False, root))
    report = manifest_path.with_name("results.json")
    report.write_text(json.dumps({"ownershipAudit": "passed", "probes": probes}, indent=2) + "\n")
    print(f"Production module verification passed; evidence: {report}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Production module verification failed: {error}", file=sys.stderr)
        sys.exit(1)
