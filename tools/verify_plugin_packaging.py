"""Verify classic IDE241 release layout against current compiled production owners."""

from collections import Counter
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile

OWNERS = {"analysis-model", "analysis-core", "editor-ui", "analysis-runtime", "plugin"}
FORBIDDEN = (
    "com/intellij/", "kotlin/", "kotlinx/coroutines/", "org/jetbrains/kotlin/",
    "org/junit/", "junit/", "org/assertj/", "com/tngtech/archunit/",
    "org/jetbrains/driver/", "com/intellij/driver/",
    "com/sijunyang/bracketpairguides/testing/",
)


def verify(manifest):
    outputs = manifest["ownerOutputs"]
    if set(outputs) != OWNERS:
        raise ValueError(f"Packaging owner list differs from the production modules: {sorted(outputs)}")
    expected = {}
    for owner, roots in outputs.items():
        classes = set()
        for root in map(Path, roots):
            if root.is_dir():
                classes.update(file.relative_to(root).as_posix() for file in root.rglob("*.class"))
        if owner != "plugin" and not classes:
            raise ValueError(f"No current compiled classes for production owner {owner}")
        expected[owner] = classes
    all_expected = set().union(*expected.values())
    if sum(map(len, expected.values())) != len(all_expected):
        raise ValueError("Compiled production classes have duplicate owners")
    owned_jars = {}
    jar_contents = {}
    counts = Counter()
    resources = set()
    descriptors = []
    with ZipFile(manifest["archive"]) as release:
        for entry in release.namelist():
            if not entry.endswith(".jar"):
                continue
            path = PurePosixPath(entry)
            if len(path.parts) != 3 or path.parts[1] != "lib":
                raise ValueError(f"Release jar is outside ordinary lib/ (classic IDE241 cannot load nested modules): {entry}")
            matches = [owner for owner in OWNERS if path.name.startswith(owner + "-") or path.name == owner + ".jar"]
            if len(matches) != 1:
                raise ValueError(f"Unexpected bundled library jar: {entry}")
            owner = matches[0]
            if owner in owned_jars:
                raise ValueError(f"More than one release jar for owner {owner}")
            owned_jars[owner] = entry
            with ZipFile(BytesIO(release.read(entry))) as jar:
                names = jar.namelist()
                class_names = [name for name in names if name.endswith(".class")]
                leaked = [name for name in class_names if name.startswith(FORBIDDEN)]
                if leaked:
                    raise ValueError(f"Forbidden IDE/runtime/test/Driver classes in release: {leaked[:5]}")
                resources.update(names)
                counts.update(class_names)
                jar_contents[owner] = set(class_names)
                if "META-INF/plugin.xml" in names:
                    descriptors.append((owner, jar.read("META-INF/plugin.xml")))
    if set(owned_jars) != OWNERS:
        raise ValueError(f"Missing production owner jars: {sorted(OWNERS - set(owned_jars))}")
    duplicate = [name for name, count in counts.items() if count != 1]
    if duplicate:
        raise ValueError(f"Release classes appear more than once: {duplicate[:5]}")
    for owner, current_classes in expected.items():
        missing = current_classes - jar_contents[owner]
        foreign = jar_contents[owner] - current_classes
        if missing or foreign:
            raise ValueError(f"Owner jar {owner} differs from current compiled classes; missing={sorted(missing)[:5]}, foreign/test={sorted(foreign)[:5]}")
    if len(descriptors) != 1 or descriptors[0][0] != "plugin":
        raise ValueError("Release must contain exactly one descriptor in the plugin owner jar")
    descriptor = ET.fromstring(descriptors[0][1])
    if descriptor.find("content") is not None:
        raise ValueError("Classic release unexpectedly declares modular plugin content")
    for element in descriptor.iter():
        for attribute in ("implementation", "serviceImplementation", "serviceInterface", "instance"):
            target = element.get(attribute, "")
            if target.startswith("com.sijunyang.bracketpairguides.") and target.replace(".", "/") + ".class" not in all_expected:
                raise ValueError(f"Descriptor references a missing production class: {target}")
    version = descriptor.find("idea-version")
    if version is None or version.get("since-build") != "241":
        raise ValueError("Release descriptor must preserve minimum IDE build 241")
    required = {"META-INF/pluginIcon.svg", "META-INF/pluginIcon_dark.svg"}
    required.update("META-INF/licenses/" + file.relative_to(manifest["licensesDirectory"]).as_posix()
                    for file in Path(manifest["licensesDirectory"]).rglob("*") if file.is_file())
    missing_resources = required - resources
    if missing_resources:
        raise ValueError(f"Missing release icons/licenses: {sorted(missing_resources)}")
    return {"archive": manifest["archive"], "classicIdeMinimum": "241", "ownerJars": owned_jars,
            "classCounts": {owner: len(classes) for owner, classes in expected.items()},
            "ownedClassesExactlyOnce": True, "iconsAndLicenses": sorted(required)}


def main():
    manifest = json.loads(Path(sys.argv[1]).read_text())
    evidence = verify(manifest)
    destination = Path(manifest["evidenceFile"])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(evidence, indent=2) + "\n")
    print(f"Plugin packaging passed: all five ordinary lib owner jars, compiled classes exactly once; evidence: {destination}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Plugin packaging verification failed: {error}", file=sys.stderr)
        sys.exit(1)
