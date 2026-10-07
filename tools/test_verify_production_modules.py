"""Exercise contamination rejection independently of the current clean build."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import shutil
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
                "javaSourcepathSpecified": True, "javaSourcepath": [],
                "javaBootstrapClasspath": [], "javaAnnotationProcessorPath": [],
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

    def test_source_only_archives_are_rejected_on_both_compiler_classpaths(self):
        for suffix in (".java",):
            jar = self.root / f"source-only{suffix}.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("foreign/HiddenCore" + suffix, "package foreign; public class HiddenCore {}")
            for kind in ("javaClasspath", "kotlinClasspath"):
                with self.subTest(suffix=suffix, kind=kind):
                    modules = copy.deepcopy(self.modules)
                    modules["editor-ui"][kind] = [str(jar)]
                    with self.assertRaisesRegex(ValueError, "implicit compiler source roots"):
                        verification.audit(modules)

    def test_sdk_preview_and_kotlin_resource_archives_remain_accepted(self):
        jar = self.root / "embedded-resources.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("codeVisionProviders/inheritors/preview.java", "private class Scope {}")
            archive.writestr("templates/Fixture.kt", "class Fixture")
            archive.writestr("templates/setup.kts", "println(1)")
        self.modules["editor-ui"]["javaClasspath"] = [str(jar)]
        self.modules["editor-ui"]["kotlinClasspath"] = [str(jar)]
        verification.audit(self.modules)

    def test_source_and_class_companions_require_explicit_empty_sourcepath(self):
        jar = self.root / "embedded-source-and-class.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("foreign/Library.java", "package foreign; public class Library {}")
            archive.writestr("foreign/Library.class", b"compiled library inventory")
        self.modules["editor-ui"]["javaClasspath"] = [str(jar)]
        self.modules["editor-ui"]["kotlinClasspath"] = [str(jar)]
        verification.audit(self.modules)
        self.modules["editor-ui"]["javaSourcepathSpecified"] = False
        with self.assertRaisesRegex(ValueError, "explicit empty sourcepath"):
            verification.audit(self.modules)
        self.modules["editor-ui"]["javaSourcepathSpecified"] = True
        self.modules["editor-ui"]["javaSourcepath"] = [str(jar)]
        with self.assertRaisesRegex(ValueError, "explicit empty sourcepath"):
            verification.audit(self.modules)

    def test_typed_java_compiler_paths_cannot_bypass_argument_checks(self):
        for field in ("javaSourcepath", "javaBootstrapClasspath", "javaAnnotationProcessorPath"):
            with self.subTest(field=field):
                modules = copy.deepcopy(self.modules)
                modules["editor-ui"][field] = [str(self.root / "foreign.jar")]
                with self.assertRaisesRegex(ValueError, "explicit empty sourcepath"):
                    verification.audit(modules)
        self.modules["editor-ui"]["javaSourcepathSpecified"] = False
        with self.assertRaisesRegex(ValueError, "explicit empty sourcepath"):
            verification.audit(self.modules)

    @unittest.skipUnless(shutil.which("javac"), "JDK required for implicit archive-source compilation control")
    def test_javac_can_compile_a_hidden_implementation_from_a_source_only_jar(self):
        jar = self.root / "hidden-source.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("foreign/HiddenCore.java", "package foreign; public class HiddenCore {}")
        source = self.root / "Probe.java"
        source.write_text("class Probe { foreign.HiddenCore value; }")
        output = self.root / "javac-control"
        output.mkdir()
        result = subprocess.run([shutil.which("javac"), "--release", "17", "-proc:none",
                                 "-classpath", str(jar), "-d", str(output), str(source)],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((output / "foreign/HiddenCore.class").is_file())
        # This is the production compiler policy: explicit empty sourcepath.
        blocked = subprocess.run([shutil.which("javac"), "--release", "17", "-proc:none",
                                  "-sourcepath", "", "-classpath", str(jar), "-d", str(output), str(source)],
                                 capture_output=True, text=True, timeout=30)
        self.assertNotEqual(blocked.returncode, 0)
        self.assertIn("does not exist", blocked.stderr)
        self.modules["editor-ui"]["javaClasspath"] = [str(jar)]
        with self.assertRaisesRegex(ValueError, "implicit compiler source roots"):
            verification.audit(self.modules)

    def test_missing_or_renamed_owner_target_fails_before_negative_probes(self):
        manifest = {"modules": self.modules}
        with patch("verify_production_modules.compile_probe") as compiler:
            with self.assertRaisesRegex(ValueError, "missing from its production owner"):
                verification.owner_controls(manifest, self.root)
            compiler.assert_not_called()

    def test_owner_controls_cover_all_symbols_including_companions(self):
        manifest = {"modules": self.modules}
        expected = []
        for owner, targets in (("analysis-core", verification.CORE_TYPES), ("analysis-runtime", verification.RUNTIME_TYPES)):
            for target in targets:
                bytecode = target.replace(".Companion", "$Companion").replace(".", "/") + ".class"
                output = Path(self.modules[owner]["outputs"][0]) / bytecode
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(b"owner inventory probe")
                expected.append((owner, target))
        with patch("verify_production_modules.compile_probe", return_value={}) as compiler:
            verification.owner_controls(manifest, self.root)
        self.assertEqual([(call.args[1], call.args[3]) for call in compiler.call_args_list], expected)
        self.assertTrue(all(call.args[2] == "java" and call.args[4] is True and call.kwargs["owner_control"]
                            for call in compiler.call_args_list))
        manifest["modules"] = {name: module for name, module in self.modules.items()
                               if name in ("analysis-model", "analysis-core")}
        with patch("verify_production_modules.compile_probe", return_value={}) as compiler:
            verification.owner_controls(manifest, self.root)
        self.assertEqual(compiler.call_count, len(verification.CORE_TYPES))

    def test_owner_control_includes_only_its_own_outputs_and_propagates_failure(self):
        manifest = {"modules": self.modules, "java": "java", "javac": "javac", "compilerClasspath": []}
        self.modules["analysis-core"]["javaClasspath"] = ["model.jar"]
        with patch("verify_production_modules.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "cannot find symbol", "")) as compiler:
            with self.assertRaisesRegex(ValueError, "Positive analysis-core/java"):
                verification.compile_probe(manifest, "analysis-core", "java", verification.CORE,
                                           True, self.root, owner_control=True)
        command = compiler.call_args.args[0]
        self.assertEqual(command[command.index("-classpath") + 1],
                         verification.os.pathsep.join(["model.jar"] + self.modules["analysis-core"]["outputs"]))

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
