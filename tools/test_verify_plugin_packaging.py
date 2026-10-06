"""Check release contamination, stale/missing classes and classic loader layout."""

from io import BytesIO
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

import verify_plugin_packaging as packaging


class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.contents = {}
        outputs = {}
        for owner in packaging.OWNERS:
            output = root / owner
            output.mkdir()
            outputs[owner] = [str(output)]
            classes = {} if owner == "plugin" else {f"product/{owner}.class": b"class inventory"}
            for name, data in classes.items():
                file = output / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(data)
            self.contents[owner] = classes
        self.contents["plugin"].update({
            "META-INF/plugin.xml": b'<idea-plugin><idea-version since-build="241"/></idea-plugin>',
            "META-INF/pluginIcon.svg": b"icon", "META-INF/pluginIcon_dark.svg": b"icon",
            "META-INF/licenses/attribution.txt": b"license",
        })
        licenses = root / "licenses"
        licenses.mkdir()
        (licenses / "attribution.txt").write_text("license")
        self.manifest = {"archive": str(root / "release.zip"), "ownerOutputs": outputs, "licensesDirectory": str(licenses)}

    def write_release(self, nested=None):
        with ZipFile(self.manifest["archive"], "w") as release:
            for owner, contents in self.contents.items():
                data = BytesIO()
                with ZipFile(data, "w") as jar:
                    for name, value in contents.items():
                        jar.writestr(name, value)
                folder = "lib/modules" if owner == nested else "lib"
                release.writestr(f"product/{folder}/{owner}-1.jar", data.getvalue())

    def test_current_class_union_and_zero_class_plugin_owner_pass(self):
        self.write_release()
        result = packaging.verify(self.manifest)
        self.assertTrue(result["ownedClassesExactlyOnce"])
        self.assertEqual(result["classCounts"]["plugin"], 0)

    def test_nested_module_jar_is_rejected_for_classic_loader(self):
        self.write_release(nested="editor-ui")
        with self.assertRaisesRegex(ValueError, "outside ordinary lib"):
            packaging.verify(self.manifest)

    def test_missing_owner_and_stale_class_are_rejected(self):
        for mode in ("owner", "class"):
            with self.subTest(mode=mode):
                previous = self.contents.pop("analysis-core") if mode == "owner" else self.contents["analysis-core"].pop("product/analysis-core.class")
                self.write_release()
                with self.assertRaisesRegex(ValueError, "Missing production owner|differs from current"):
                    packaging.verify(self.manifest)
                if mode == "owner":
                    self.contents["analysis-core"] = previous
                else:
                    self.contents["analysis-core"]["product/analysis-core.class"] = previous

    def test_duplicate_owned_class_is_rejected(self):
        self.contents["editor-ui"]["product/analysis-core.class"] = b"duplicate"
        self.write_release()
        with self.assertRaisesRegex(ValueError, "more than once"):
            packaging.verify(self.manifest)

    def test_runtime_test_and_driver_contamination_is_rejected(self):
        for prefix in packaging.FORBIDDEN:
            with self.subTest(prefix=prefix):
                name = prefix + "Probe.class"
                self.contents["plugin"][name] = b"contamination"
                self.write_release()
                with self.assertRaisesRegex(ValueError, "Forbidden"):
                    packaging.verify(self.manifest)
                del self.contents["plugin"][name]

    def test_unclassified_test_class_is_rejected_by_owner_inventory(self):
        self.contents["plugin"]["example/FixtureTest.class"] = b"test"
        self.write_release()
        with self.assertRaisesRegex(ValueError, "foreign/test"):
            packaging.verify(self.manifest)

    def test_missing_icons_or_licenses_are_rejected(self):
        del self.contents["plugin"]["META-INF/licenses/attribution.txt"]
        self.write_release()
        with self.assertRaisesRegex(ValueError, "icons/licenses"):
            packaging.verify(self.manifest)


if __name__ == "__main__":
    unittest.main()
