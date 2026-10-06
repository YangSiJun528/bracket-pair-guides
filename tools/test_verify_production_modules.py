"""Exercise contamination rejection independently of the current clean build."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import zipfile

import verify_production_modules as verification


class ModuleAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.modules = {}
        for name in verification.ALLOWED:
            project = self.root / name
            source = project / "src/main/kotlin"
            output = project / "build/classes/kotlin/main"
            source.mkdir(parents=True)
            output.mkdir(parents=True)
            self.modules[name] = {
                "projectDirectory": str(project), "sourceFiles": [],
                "sources": [str(source)], "outputs": [str(output)],
                "projects": [], "javaClasspath": [], "kotlinClasspath": [],
                "compilerArguments": [], "javaCompilerArguments": [],
                "friendPaths": [], "compilerPlugins": [],
            }

    def test_clean_dag_is_accepted(self):
        for name, allowed in verification.ALLOWED.items():
            self.modules[name]["projects"] = sorted(allowed)
        verification.audit(self.modules)

    def test_transitive_project_leak_is_rejected(self):
        self.modules["plugin"]["projects"] = ["analysis-runtime", "analysis-core"]
        with self.assertRaisesRegex(ValueError, "compile dependency leak"):
            verification.audit(self.modules)

    def test_forbidden_bytecode_in_external_jar_is_rejected(self):
        core_class = "com/sijunyang/bracketpairguides/analysis/pairing/core/PairingMachine.class"
        output = Path(self.modules["analysis-core"]["outputs"][0]) / core_class
        output.parent.mkdir(parents=True)
        output.write_bytes(b"bytecode inventory probe")
        jar = self.root / "misleading-name.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(core_class, b"bytecode inventory probe")
        self.modules["editor-ui"]["kotlinClasspath"] = [str(jar)]
        with self.assertRaisesRegex(ValueError, "forbidden bytecode"):
            verification.audit(self.modules)

    def test_shared_and_ancestor_roots_are_rejected(self):
        for kind in ("sources", "outputs"):
            for ancestor in (False, True):
                with self.subTest(kind=kind, ancestor=ancestor):
                    modules = copy.deepcopy(self.modules)
                    root = Path(modules["analysis-core"][kind][0])
                    modules["editor-ui"][kind] = [str(root.parent if ancestor else root)]
                    with self.assertRaisesRegex(ValueError, f"Shared {kind}"):
                        verification.audit(modules)

    def test_injected_compiler_source_is_rejected(self):
        self.modules["editor-ui"]["sourceFiles"] = [str(self.root / "analysis-core/src/main/kotlin/Injected.kt")]
        with self.assertRaisesRegex(ValueError, "outside its owner"):
            verification.audit(self.modules)

    def test_friend_flags_effective_paths_and_compiler_plugins_are_rejected(self):
        cases = {
            "compilerArguments": ["-Xfriend-paths=/tmp/core"],
            "friendPaths": ["/tmp/core"],
            "compilerPlugins": ["/tmp/compiler-plugin.jar"],
            "javaCompilerArguments": ["--class-path", "/tmp/core.jar"],
        }
        for field, value in cases.items():
            with self.subTest(field=field):
                modules = copy.deepcopy(self.modules)
                modules["editor-ui"][field] = value
                with self.assertRaisesRegex(ValueError, "production compiler"):
                    verification.audit(modules)

    def test_alternative_visibility_options_aliases_argfiles_and_unknown_flags_are_rejected(self):
        options = {
            "javaCompilerArguments": ["--module-path", "-p", "--patch-module=core=/tmp/classes", "--upgrade-module-path", "--module-source-path", "-sourcepath", "--source-path", "-Xbootclasspath/a:/tmp/classes", "-bootclasspath", "-processorpath", "--processor-module-path", "--system", "@compiler.args", "--unknown-future-path-option"],
            "compilerArguments": ["-Xjava-source-roots=/tmp/core", "-Xmodule-path=/tmp/core.jar", "-Xjavac-arguments=--module-path,/tmp/core", "-Xbuild-file=/tmp/build.xml", "-Xcommon-sources=/tmp/foreign.kt", "-jdk-home", "-cp", "-classpath", "-P", "@compiler.args", "-Xunknown-future-option"],
        }
        for field, arguments in options.items():
            for argument in arguments:
                with self.subTest(field=field, argument=argument):
                    modules = copy.deepcopy(self.modules)
                    modules["editor-ui"][field] = [argument]
                    with self.assertRaisesRegex(ValueError, "visibility overrides"):
                        verification.audit(modules)

    def test_known_diagnostic_only_arguments_are_accepted(self):
        self.modules["editor-ui"]["compilerArguments"] = ["-nowarn", "-Werror", "-verbose"]
        self.modules["editor-ui"]["javaCompilerArguments"] = ["-g:source,lines", "-Xlint:all", "-parameters"]
        verification.audit(self.modules)

    def test_classpath_default_source_roots_are_rejected(self):
        source = self.root / "foreign-source"
        source.mkdir()
        (source / "HiddenCore.java").write_text("class HiddenCore {}")
        self.modules["editor-ui"]["javaClasspath"] = [str(source)]
        with self.assertRaisesRegex(ValueError, "implicit compiler source roots"):
            verification.audit(self.modules)

    def test_mixed_internal_access_and_unresolved_diagnostics_do_not_pass(self):
        manifest = {"modules": self.modules, "java": "java", "javac": "javac", "compilerClasspath": []}
        diagnostics = "Cannot access BracketAnalysis: it is internal in its module\nunresolved reference: unrelated"
        with patch("verify_production_modules.subprocess.run", return_value=subprocess.CompletedProcess([], 1, diagnostics, "")):
            with self.assertRaisesRegex(ValueError, "exposed an inaccessible implementation"):
                verification.compile_probe(manifest, "editor-ui", "kotlin", verification.RUNTIME, False, self.root)

    def test_companion_probes_use_distinct_directories(self):
        manifest = {"modules": self.modules, "java": "java", "javac": "javac", "compilerClasspath": []}
        targets = [target for target in verification.CORE_TYPES if target.endswith(".Companion")]
        for language in ("java", "kotlin"):
            diagnostics = "unresolved reference: Companion" if language == "kotlin" else "package does not exist"
            with patch("verify_production_modules.subprocess.run", return_value=subprocess.CompletedProcess([], 1, diagnostics, "")):
                for target in targets:
                    verification.compile_probe(manifest, "editor-ui", language, target, False, self.root)
        self.assertEqual(len(list(self.root.glob("editor-ui-*Companion"))), 4)

    def test_sdk_bytecode_is_rejected_in_pure_modules(self):
        jar = self.root / "sdk.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("com/intellij/openapi/editor/Editor.class", b"sdk inventory probe")
        self.modules["analysis-core"]["javaClasspath"] = [str(jar)]
        with self.assertRaisesRegex(ValueError, "IntelliJ SDK"):
            verification.audit(self.modules)

    def test_duplicate_class_ownership_is_rejected(self):
        for name in ("analysis-core", "editor-ui"):
            (Path(self.modules[name]["outputs"][0]) / "Duplicate.class").write_bytes(b"inventory probe")
        with self.assertRaisesRegex(ValueError, "Duplicate production class"):
            verification.audit(self.modules)


if __name__ == "__main__":
    unittest.main()
