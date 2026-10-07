#!/usr/bin/env python3
"""Final release revalidation; plan by default, execute with --execute.

Requires the complete 13-target current frozen matrix and the full current plugin fixture suite.

No automatic matrix refresh. No shared pre-existing IDE/cache removal. The final
archive must already exist; hashes are checked before/after every Gradle process.
Do not run another Gradle/IDE consumer while using --cleanup-created-ides.
"""
import argparse
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

LEVELS = ["COMPATIBILITY_PROBLEMS", "DEPRECATED_API_USAGES", "EXPERIMENTAL_API_USAGES",
          "INTERNAL_API_USAGES", "OVERRIDE_ONLY_API_USAGES", "NON_EXTENDABLE_API_USAGES",
          "MISSING_DEPENDENCIES", "INVALID_PLUGIN"]
BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
CACHE = Path.home() / '.gradle/caches'


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def product(ide):
    for name in ('product-info.json', 'Resources/product-info.json', 'Contents/Resources/product-info.json'):
        path = ide / name
        if path.is_file():
            return path, json.loads(path.read_text())
    raise ValueError('No product-info.json: ' + str(ide))


def validate(target, ide):
    path, info = product(ide)
    code, version = target.split('-', 1)
    actual = info['buildNumber'] if re.fullmatch(r'\d{3}\..*', version) else info['version']
    if info['productCode'] != code or actual != version:
        raise ValueError('IDE identity mismatch: ' + target + ' versus ' + str(info))
    return {'path': str(ide), 'productCode': info['productCode'], 'version': info['version'],
            'buildNumber': info['buildNumber'], 'productInfo': str(path), 'productInfoSha256': sha(path)}


def transform_roots():
    return {p.resolve() for p in CACHE.glob('*/transforms/*') if p.is_dir()}


def cached_ides():
    found = []
    for transform in transform_roots():
        for ide in (transform / 'transformed').glob('*'):
            if not ide.is_dir():
                continue
            try:
                _, info = product(ide)
                found.append((ide.resolve(), info))
            except (ValueError, OSError, json.JSONDecodeError):
                pass
    # Prefer current wrapper cache, without constructing or modifying any cache entries.
    return sorted(found, key=lambda entry: ('/9.8.0/' not in str(entry[0]), str(entry[0])))


def existing_path(target, inventory):
    for path, _ in inventory:
        try:
            validate(target, path)
            return path
        except ValueError:
            pass
    return None


def installers(target):
    code, version = target.split('-', 1)
    groups = ['idea/idea'] if code == 'IU' else ['com.jetbrains.intellij.idea/ideaIC', 'idea/ideaIC']
    files = set()
    for group in groups:
        for p in (CACHE / 'modules-2/files-2.1' / group / version).glob('*/*'):
            if p.is_file() and p.name.endswith(('.dmg', '.zip', '.tar.gz')):
                files.add(p.resolve())
    return files


def free_record(minimum):
    free = shutil.disk_usage(ROOT).free
    return {'path': str(ROOT), 'freeBytes': free, 'minimumBytes': minimum, 'admitted': free >= minimum}


def junit_summary(directory):
    files = sorted(directory.glob('TEST-*.xml'))
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in files:
        root = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(root.get(key, '0'))
    return {'directory': str(directory), 'xmlFiles': len(files), **totals}


def junit_cases(directory):
    cases = []
    for path in sorted(directory.glob('TEST-*.xml')):
        for case in ET.parse(path).getroot().iter('testcase'):
            cases.append((case.get('classname', ''), case.get('name', '')))
    if len(cases) != len(set(cases)):
        raise ValueError('Duplicate fixture testcase identities: ' + str(directory))
    return sorted(cases)


def command_run(command, log):
    with log.open('w') as stream:
        completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    return completed.returncode


def retire_owned(target, ide, before_roots, before_installers, identity):
    # Only exact new roots reached through the resolved selected IDE are eligible.
    eligible = []
    transform = ide.parent.parent
    if (ide.parent.name == 'transformed' and transform.parent.name == 'transforms' and
            transform in transform_roots() and transform not in before_roots and
            transform.is_relative_to(CACHE.resolve())):
        eligible.append({'kind': 'transform', 'path': str(transform), 'productInfoSha256': identity['productInfoSha256']})
    for path in sorted(installers(target) - before_installers):
        eligible.append({'kind': 'installer', 'path': str(path), 'sizeBytes': path.stat().st_size,
                         'mtimeNs': path.stat().st_mtime_ns})
    # Check live consumers before removing any acquisition artifacts. Prior IDE/task
    # consumers using these paths must finish; if observed, preserve everything.
    processes = subprocess.run(['ps', '-axo', 'command='], capture_output=True, text=True, check=True).stdout
    consumers = [line for line in processes.splitlines() if any(e['path'] in line for e in eligible)]
    if consumers:
        return {'eligibleCreatedArtifacts': eligible, 'removed': [], 'preservedForConsumers': consumers}
    removed = []
    for entry in eligible:
        path = Path(entry['path'])
        if entry['kind'] == 'transform':
            # Recheck selected IDE identity/content before exact-root deletion.
            if validate(target, ide)['productInfoSha256'] != entry['productInfoSha256']:
                raise ValueError('Product identity changed before cleanup: ' + str(ide))
            shutil.rmtree(path)
        else:
            stat = path.stat()
            if stat.st_size != entry['sizeBytes'] or stat.st_mtime_ns != entry['mtimeNs']:
                raise ValueError('Installer changed before cleanup: ' + str(path))
            path.unlink()
        removed.append(entry)
    return {'eligibleCreatedArtifacts': eligible, 'removed': removed, 'preservedForConsumers': []}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix', type=Path, default=BASE / 'current-matrix.json')
    parser.add_argument('--archive', type=Path, default=ROOT / 'plugin/build/distributions/bracket-pair-guides-0.0.6.zip')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--targets', nargs='+', help='Must specify every frozen target in original order; default is the full 13-target matrix')
    parser.add_argument('--fixture-baseline-dir', type=Path, default=ROOT / 'outputs/issue-97/current-check/plugin',
                        help='Passing current plugin fixture XML preserved from this worktree check run')
    parser.add_argument('--expected-fixture-tests', type=int, default=411)
    parser.add_argument('--runtime-fixtures-target', default='IU-263.6259.32', help='One selected target requiring the full current baseline fixture suite before strict verification')
    parser.add_argument('--cleanup-created-ides', action='store_true',
                        help='Remove only exact artifacts absent before this target acquisition; never remove existing cache entries')
    parser.add_argument('--minimum-free-gib', type=int, default=9)
    args = parser.parse_args()
    if not args.matrix.is_file():
        parser.error('Freeze current-matrix.json first using recordSerialVerifierMatrix; this runner never substitutes historical metadata')
    matrix = json.loads(args.matrix.read_text())
    if matrix.get('failureLevels') != LEVELS:
        parser.error('Frozen matrix must contain all eight strict failure levels in configured order')
    targets = matrix.get('targets', [])
    if not targets or len(set(targets)) != len(targets):
        parser.error('Frozen matrix must contain nonempty unique targets')
    if any(not re.fullmatch(r'(IC|IU)-[0-9][A-Za-z0-9.]*', target) for target in targets):
        parser.error('Only declared IC/IU version/build targets are supported')
    selected = targets if args.targets is None else args.targets
    if not selected or len(set(selected)) != len(selected) or any(target not in targets for target in selected):
        parser.error('--targets must be a nonempty unique subset of the frozen targets')
    if len(targets) != 13 or selected != targets:
        parser.error('Final release revalidation requires every frozen target in original order (13 targets)')
    if not args.runtime_fixtures_target:
        parser.error('Final release revalidation requires the selected runtime fixture target')
    if args.runtime_fixtures_target and args.runtime_fixtures_target not in selected:
        parser.error('--runtime-fixtures-target must be included in selected frozen targets')
    baseline_dir = args.fixture_baseline_dir.resolve()
    if not baseline_dir.is_dir():
        parser.error('Preserve passing current-check plugin fixture XML before preparing verifier plan')
    baseline_fixture = junit_summary(baseline_dir) if args.runtime_fixtures_target else None
    if baseline_fixture and (baseline_fixture['tests'] <= 0 or any(baseline_fixture[key] for key in ('failures', 'errors', 'skipped'))):
        parser.error('Current default fixture XML must contain a nonempty passing suite without skips')
    if args.expected_fixture_tests <= 0 or baseline_fixture['tests'] != args.expected_fixture_tests:
        parser.error('Current fixture baseline must contain exactly --expected-fixture-tests passing tests')
    baseline_cases = junit_cases(baseline_dir)
    if len(baseline_cases) != baseline_fixture['tests']:
        parser.error('Fixture XML totals and testcase identities disagree')
    inventory = cached_ides()
    paths = {target: existing_path(target, inventory) for target in selected}
    plan = {'matrix': str(args.matrix.resolve()), 'frozenTargets': targets, 'selectedTargets': selected,
            'pendingUnselected': [target for target in targets if target not in selected], 'failureLevels': LEVELS,
            'cachedPaths': {t: str(p) if p else None for t, p in paths.items()},
            'archive': str(args.archive.resolve()), 'cleanupCreatedOnly': args.cleanup_created_ides,
            'minimumFreeBytes': args.minimum_free_gib * 1024 ** 3,
            'runtimeFixturesTarget': args.runtime_fixtures_target, 'fixtureBaseline': baseline_fixture,
            'fixtureBaselineCases': baseline_cases, 'finalRevalidation': True,
            'acquisitionMode': 'existing verified Gradle shared-cache API; exact new-target artifact inventory, no broad eviction',
            'concurrencyRequirement': 'exclusive Gradle/IDE acquisition ownership while cleanup option is enabled'}
    print(json.dumps(plan, indent=2), flush=True)
    if not args.execute:
        return 0
    if not args.archive.is_file():
        parser.error('Build final plugin archive separately before execution')
    archive = args.archive.resolve()
    if archive != (ROOT / 'plugin/build/distributions/bracket-pair-guides-0.0.6.zip').resolve():
        parser.error('Only the current default buildPlugin archive is supported; Gradle task input must equal the hashed archive')
    archive_sha = sha(archive)
    run = BASE / 'runs' / datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    run.mkdir(parents=True, exist_ok=False)
    inputs = run / 'inputs'
    inputs.mkdir()
    shutil.copy2(Path(__file__), inputs / Path(__file__).name)
    for name in ['serial-verifier.init.gradle', 'runtime-fixtures.init.gradle']:
        shutil.copy2(BASE / name, inputs / name)
    if baseline_fixture:
        baseline_copy = inputs / 'default-fixture-results'
        baseline_copy.mkdir()
        for path in baseline_dir.glob('TEST-*.xml'):
            shutil.copy2(path, baseline_copy / path.name)
        save(inputs / 'fixture-baseline-summary.json', baseline_fixture)
    frozen = inputs / 'current-matrix.json'
    shutil.copy2(args.matrix, frozen)
    shutil.copy2(archive, inputs / archive.name)
    save(run / 'plan.json', plan)
    save(run / 'input-hashes.json', {str(p.relative_to(inputs)): sha(p) for p in inputs.rglob('*') if p.is_file()})
    initial_roots = transform_roots()
    save(run / 'pre-existing-transform-roots.json', sorted(str(p) for p in initial_roots))
    records = []
    for target in selected:
        record = {'target': target, 'strictPass': False, 'failureLevels': LEVELS}
        records.append(record)
        record['diskBefore'] = free_record(plan['minimumFreeBytes'])
        record['archiveShaBefore'] = sha(archive)
        if not record['diskBefore']['admitted'] or record['archiveShaBefore'] != archive_sha:
            record['status'] = 'stopped-before-target-low-disk-or-changed-archive'
            save(run / (target + '.json'), record)
            break
        before_roots = transform_roots()
        before_installers = installers(target)
        record['preExistingTargetInstallers'] = sorted(str(p) for p in before_installers)
        ide = existing_path(target, cached_ides())
        created = ide is None
        try:
            if created:
                code, version = target.split('-', 1)
                command = ['./gradlew', '--no-daemon', '--max-workers=2', '--no-configuration-cache', '--init-script', str(inputs / 'runtime-fixtures.init.gradle'),
                           ':plugin:resolveRuntimeIde', '-PruntimeIdeType=' + code, '-PruntimeIdeVersion=' + version, '--info']
                record['resolveCommand'] = command
                record['resolveExit'] = command_run(command, run / (target + '-resolve.log'))
                if record['resolveExit'] != 0:
                    raise ValueError('Selected IDE acquisition failed; see preserved resolve log')
                text = (run / (target + '-resolve.log')).read_text()
                matches = re.findall(r'Resolved runtime IDE platformPath: (.+)', text)
                if len(matches) != 1:
                    raise ValueError('Expected one resolved runtime platformPath')
                ide = Path(matches[0].strip()).resolve()
            record['identity'] = validate(target, ide)
            record['acquiredThisTarget'] = created
            record['createdArtifactInventory'] = {
                'selectedTransformRoot': str(ide.parent.parent) if ide.parent.name == 'transformed' and ide.parent.parent not in before_roots else None,
                'selectedTargetInstallers': sorted(str(p) for p in installers(target) - before_installers),
                'scope': 'exact selected-target artifacts absent immediately before this acquisition; no pre-existing entries eligible',
            }
            if sha(archive) != archive_sha:
                raise ValueError('Archive changed during acquisition')
            if target == args.runtime_fixtures_target:
                code, version = target.split('-', 1)
                fixture_root = run / 'runtime-fixtures' / target
                xml_root = fixture_root / 'junit-xml'
                fixture_root.mkdir(parents=True)
                command = ['./gradlew', '--no-daemon', '--max-workers=2', '--no-configuration-cache',
                           '--init-script', str(inputs / 'runtime-fixtures.init.gradle'), ':plugin:runtimeFixtures',
                           '-PruntimeIdeType=' + code, '-PruntimeIdeVersion=' + version,
                           '-PruntimeFixtureReportPath=' + str(fixture_root), '--info']
                record['fixtureCommand'] = command
                record['fixturePass'] = False
                try:
                    record['fixtureExit'] = command_run(command, fixture_root / 'runtimeFixtures.log')
                    record['fixtureXml'] = junit_summary(xml_root)
                    record['fixtureCasesMatchBaseline'] = junit_cases(xml_root) == baseline_cases
                    text = (fixture_root / 'runtimeFixtures.log').read_text()
                    fixture_ides = re.findall(r'Resolved fixture IDE: (.+)', text)
                    fixture_jvms = re.findall(r'Fixture JVM: (.+)', text)
                    record['fixturePlatformPaths'] = fixture_ides
                    record['fixtureJvmPaths'] = fixture_jvms
                    actual_ide = Path(fixture_ides[0].strip()).resolve() if len(fixture_ides) == 1 else None
                    actual_jvm = Path(fixture_jvms[0].strip()).resolve() if len(fixture_jvms) == 1 else None
                    expected_jvm = (ide / 'jbr/Contents/Home/bin/java').resolve()
                    record['fixturePass'] = (record['fixtureExit'] == 0 and actual_ide == ide and
                                             actual_jvm == expected_jvm and actual_jvm.is_file() and
                                             record['fixtureCasesMatchBaseline'] and
                                             record['fixtureXml']['tests'] == baseline_fixture['tests'] and
                                             record['fixtureXml']['xmlFiles'] == baseline_fixture['xmlFiles'] and
                                             all(record['fixtureXml'][key] == 0 for key in ('failures', 'errors', 'skipped')) and
                                             sha(archive) == archive_sha)
                except (OSError, ValueError, ET.ParseError) as error:
                    record['fixtureError'] = str(error)
                save(fixture_root / 'summary.json', {
                    key: value for key, value in record.items() if key.startswith('fixture') or key == 'identity'
                })
                # Strict verifier still runs after a fixture failure; cleanup and
                # all-selected success require both independent outcomes.
            reports = run / 'reports' / target
            command = ['./gradlew', '--no-daemon', '--max-workers=2', '--offline', '--no-configuration-cache', '--init-script', str(inputs / 'serial-verifier.init.gradle'),
                       ':plugin:verifyPlugin', '-PverifierTarget=' + target, '-PverifierIdePath=' + str(ide),
                       '-PverifierMatrixPath=' + str(frozen), '-PverifierReportPath=' + str(reports), '--info']
            record['verifyCommand'] = command
            record['verifyExit'] = command_run(command, run / (target + '-verify.log'))
            record['archiveShaAfter'] = sha(archive)
            verdicts = list(reports.rglob('verification-verdict.txt'))
            record['verdicts'] = [{'path': str(p), 'text': p.read_text().strip()} for p in verdicts]
            record['strictPass'] = (record['verifyExit'] == 0 and len(verdicts) == 1 and
                                    verdicts[0].read_text().strip() == 'Compatible' and
                                    record['archiveShaBefore'] == record['archiveShaAfter'] == archive_sha)
            record['status'] = 'finished'
            save(run / (target + '.json'), record)  # Evidence preserved before retirement.
            if created and args.cleanup_created_ides and record['strictPass'] and record.get('fixturePass', True):
                record['retirement'] = retire_owned(target, ide, before_roots, before_installers, record['identity'])
            elif created and args.cleanup_created_ides:
                record['cleanupPreservedReason'] = 'Fixture or strict-verifier failure; task-owned acquisition artifacts retained'
        except (ValueError, OSError, json.JSONDecodeError) as error:
            record['status'] = 'failed'
            record['error'] = str(error)
            record['unclassifiedNewTransformRootsRetained'] = sorted(str(p) for p in transform_roots() - before_roots)
            record['newTargetInstallersRetained'] = sorted(str(p) for p in installers(target) - before_installers)
            # Failure artifacts are retained; no uncertain cleanup or broad deletion.
        record['diskAfter'] = free_record(plan['minimumFreeBytes'])
        save(run / (target + '.json'), record)
        print(json.dumps(record), flush=True)
        if not archive.is_file() or sha(archive) != archive_sha:
            break
    strict_passed = [r['target'] for r in records if r['strictPass']]
    passed = [r['target'] for r in records if r['strictPass'] and r.get('fixturePass', True)]
    summary = {'frozenTargets': targets, 'selectedTargets': selected,
               'pendingUnselected': [t for t in targets if t not in selected],
               'strictPassedThisArchive': strict_passed, 'selectedAllRequiredPassed': passed,
               'runtimeFixturesTarget': args.runtime_fixtures_target,
               'runtimeFixturePassed': next((r.get('fixturePass') for r in records if r['target'] == args.runtime_fixtures_target), None),
               'pendingVerificationTargets': [t for t in targets if t not in strict_passed],
               'pendingSelected': [t for t in selected if t not in passed],
               'pendingTargets': [t for t in targets if t not in passed],
               'allSelectedPassed': passed == selected,
               'allTargetsPassed': len(passed) == len(targets) and set(passed) == set(targets), 'archiveSha256': archive_sha, 'run': str(run)}
    save(run / 'summary.json', summary)
    print(json.dumps(summary, indent=2), flush=True)
    return 0 if summary['allSelectedPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
