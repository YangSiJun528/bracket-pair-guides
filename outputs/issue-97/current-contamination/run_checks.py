"""Run real Gradle contamination checks serially; invoke only on an idle build host.

Each case uses a temporary init script, never modifies project sources/build files,
and must fail with the audit's explicit reason. A clean verification always runs
last. Commands, return codes, init scripts and complete logs remain in the run dir.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile


CASES = [
    ("direct-core-dependency", "editor-ui compile dependency leak", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    ui.dependencies.add('implementation', ui.dependencies.project(path: ':analysis-core'))
}
"""),
    ("transitive-core-dependency", "editor-ui compile dependency leak", """
gradle.settingsEvaluated { settings ->
    settings.include(':contaminationBridge')
    settings.project(':contaminationBridge').projectDir = new File(BRIDGE_DIRECTORY)
}
gradle.beforeProject { owner ->
    if (owner.path == ':contaminationBridge') {
        owner.apply plugin: 'java-library'
        owner.java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        owner.dependencies.add('api', owner.dependencies.project(path: ':analysis-core'))
    }
}
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    ui.dependencies.add('implementation', ui.dependencies.project(path: ':contaminationBridge'))
}
"""),
    ("shared-compiler-classpath", "contains forbidden bytecode", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    def foreign = core.sourceSets.main.output.classesDirs
    ui.tasks.named('compileKotlin').configure { libraries.from(foreign) }
    ui.tasks.named('compileJava').configure { classpath = classpath.plus(foreign) }
}
"""),
    ("shared-production-output", "Shared outputs", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.sourceSets.main.output.classesDirs.from(core.sourceSets.main.output.classesDirs)
}
"""),
    ("production-friend-flag", "production compiler contains friends", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    def friends = core.sourceSets.main.output.classesDirs.files.collect { it.absolutePath }.join(',')
    ui.tasks.named('compileKotlin').configure {
        compilerOptions.freeCompilerArgs.add('-Xfriend-paths=' + friends)
    }
}
"""),
    ("java-module-path-core", "production compiler contains friends, compiler plugins or visibility overrides", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def coreJar = gradle.rootProject.project(':analysis-core').tasks.named('jar')
    ui.tasks.named('compileJava').configure {
        dependsOn(coreJar)
        options.compilerArgs.addAll(['--module-path', coreJar.get().archiveFile.get().asFile.absolutePath,
            '--add-modules', 'ALL-MODULE-PATH'])
    }
}
"""),
    ("kotlin-java-source-root-core", "production compiler contains friends, compiler plugins or visibility overrides", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.tasks.named('compileKotlin').configure {
        compilerOptions.freeCompilerArgs.add('-Xjava-source-roots=' + core.file('src/main/java').absolutePath)
    }
}
"""),
    ("java-default-source-path-core", "implicit compiler source roots", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.tasks.named('compileJava').configure { classpath = classpath.plus(core.files('src/main/java')) }
}
"""),
    ("shared-production-source", "outside its owner", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.sourceSets.main.java.srcDir(core.file('src/main/java'))
}
"""),
    ("java-source-only-archive", "implicit compiler source roots", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    ui.tasks.named('compileJava').configure { classpath = classpath.plus(ui.files(SOURCE_ARCHIVE)) }
}
"""),
    ("typed-java-sourcepath", "explicit empty sourcepath", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.tasks.named('compileJava').configure { options.sourcepath = core.files('src/main/java') }
}
"""),
    ("typed-java-bootstrap-classpath", "explicit empty sourcepath", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.tasks.named('compileJava').configure { options.bootstrapClasspath = core.sourceSets.main.output.classesDirs }
}
"""),
    ("typed-java-processor-path", "explicit empty sourcepath", """
gradle.projectsEvaluated {
    def ui = gradle.rootProject.project(':editor-ui')
    def core = gradle.rootProject.project(':analysis-core')
    ui.tasks.named('compileJava').configure { options.annotationProcessorPath = core.sourceSets.main.output.classesDirs }
}
"""),
]


def groovy_string(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def execute(repository, output, name, init_script=None, expected_reason=None):
    command = [str(repository / "gradlew"), "--no-daemon", "--no-configuration-cache", "--console=plain"]
    if name == "clean-final":
        command.append(":editor-ui:clean")
    command.append("verifyProductionModules")
    if init_script:
        command += ["--init-script", str(init_script)]
    started = datetime.now(timezone.utc).isoformat()
    before = time.monotonic()
    log = output / f"{name}.log"
    print(f"Running {name}; log: {log}", flush=True)
    timed_out = False
    with log.open("w") as stream:
        try:
            result = subprocess.run(command, cwd=repository, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
        except subprocess.TimeoutExpired:
            timed_out = True
            result = subprocess.CompletedProcess(command, -124)
            stream.write("\nContamination runner timeout after 1200 seconds.\n")
    contents = log.read_text()
    passed = result.returncode == 0 if expected_reason is None else result.returncode != 0 and expected_reason in contents
    record = {
        "name": name, "command": command, "startedUtc": started,
        "elapsedSeconds": time.monotonic() - before, "exitCode": result.returncode, "timedOut": timed_out,
        "expectedAuditReason": expected_reason, "passed": passed, "log": str(log),
    }
    (output / f"{name}.json").write_text(json.dumps(record, indent=2) + "\n")
    print(f"{'PASS' if passed else 'FAIL'} {name}: exit {result.returncode}", flush=True)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--execute", action="store_true", help="Execute serial Gradle mutations; default prepares a plan only")
    args = parser.parse_args()
    repository = args.repository.resolve()
    output = (args.output or Path(__file__).resolve().parent / ("run-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    # Evidence is persistent under this runner's output, including real jar input.
    bridge = output / "bridge"
    bridge.mkdir()
    (bridge / "build.gradle").write_text("// Temporary contamination bridge configured by init script.\n")
    source_archive = output / "source-only.jar"
    with zipfile.ZipFile(source_archive, "w") as archive:
        archive.writestr("foreign/HiddenCore.java", "package foreign; public class HiddenCore {}\n")
    prepared = []
    for name, reason, body in CASES:
        script = output / f"{name}.init.gradle"
        script.write_text("import org.gradle.jvm.toolchain.JavaLanguageVersion\n" +
                          body.replace("BRIDGE_DIRECTORY", groovy_string(bridge)).replace("SOURCE_ARCHIVE", groovy_string(source_archive)))
        prepared.append({"name": name, "reason": reason, "initScript": str(script)})
    plan = {"repository": str(repository), "cases": prepared, "executed": False,
            "mutationScope": "init scripts only; no production source/build edits",
            "measurementPolicy": "run only on an idle build host; main executor owns all shared outputs",
            "cleanFinal": ":editor-ui:clean verifyProductionModules; removes injected Java outputs before clean graph audit"}
    (output / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    if not args.execute:
        print(f"Prepared {len(prepared)} real Gradle contamination cases; none executed. Plan: {output / 'plan.json'}")
        return 0
    records = []
    try:
        for case in prepared:
            records.append(execute(repository, output, case["name"], Path(case["initScript"]), case["reason"]))
    finally:
        # Injections can compile classes into UI outputs. Remove them through
        # the ordinary clean task, then independently audit clean real inputs.
        records.append(execute(repository, output, "clean-final"))
        report = {"repository": str(repository), "cases": records,
                  "executed": True, "passed": len(records) == len(prepared) + 1 and all(record["passed"] for record in records)}
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Evidence: {output / 'results.json'}", flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
