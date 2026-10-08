#!/usr/bin/env python3
"""Fresh release verification only. Prepare matrix/testcases explicitly; plan by default.

Execution uses official minimum/current SDK tasks and official verifyPlugin, serially.
No measurement, historical fixture/archive acceptance, broad cache eviction or upload.
"""
import argparse
import fcntl
import sys
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
ROOT = BASE.parents[3]
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
        return {'eligibleCreatedArtifacts': eligible, 'removed': [], 'preservedForConsumers': [{'matchedArtifact': entry['path']} for entry in eligible if any(entry['path'] in line for line in consumers)]}
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


PROJECTS = ['analysis-core', 'analysis-model', 'analysis-runtime', 'benchmarks', 'editor-ui', 'plugin']
SCHEMA = 'issue97-deep-redesign-verifier-v1'


def source_manifest():
    files = set()
    for name in ['settings.gradle.kts', 'build.gradle.kts', 'gradle.properties',
                 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties',
                 'build-logic/settings.gradle.kts', 'build-logic/build.gradle.kts',
                 'plugin/sdk-measurement.gradle']:
        path = ROOT / name
        if path.is_file(): files.add(path)
    for module in PROJECTS:
        files.add(ROOT / module / 'build.gradle.kts')
        for source in ['src/main', 'src/test']:
            files.update(p for p in (ROOT / module / source).rglob('*') if p.is_file())
    files.update(p for p in (ROOT / 'build-logic/src/main').rglob('*') if p.is_file())
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted(files)}


def sdk_evidence(directory, task, expected_count):
    summary = junit_summary(directory)
    cases = junit_cases(directory)
    if summary['tests'] != expected_count or len(cases) != expected_count or any(summary[k] for k in ('failures', 'errors', 'skipped')):
        raise ValueError(f'{task}: require exactly {expected_count} passing, unskipped, unique cases: {summary}')
    identity_file = directory / 'TEST-com.sijunyang.bracketpairguides.plugin.RuntimeIdentityIdeContractTest.xml'
    identity = ET.parse(identity_file).getroot().findtext('system-out', '')
    expected = {'minimumSdkTests': ('IC-241.19416.15', '2024.1.7'),
                'currentSdkTests': ('IU-263.6259.32', '263.6259.32')}[task]
    matches = re.findall(r'^ACTUAL_IDE=([^;\n]+);EXPECTED=([^\n]+)', identity, re.M)
    jvms = re.findall(r'^ACTUAL_JAVA=([^;\n]+);([^\n]+)', identity, re.M)
    if matches != [expected] or len(jvms) != 1:
        raise ValueError(f'{task}: actual IDE/JBR identity evidence missing or unexpected')
    home, version = jvms[0]
    java = Path(home) / 'bin/java'
    if not java.is_file() or not any(part == 'jbr' for part in Path(home).parts):
        raise ValueError(f'{task}: recorded JVM is not an existing bundled JBR')
    return {'task': task, 'summary': summary, 'cases': cases,
            'actualIde': matches[0][0], 'expectedVersion': matches[0][1],
            'javaHome': home, 'javaRuntimeVersion': version, 'javaExecutableSha256': sha(java),
            'xmlSha256': {p.name: sha(p) for p in sorted(directory.glob('TEST-*.xml'))}}


def sdk_pair(minimum, current, count):
    result = {name: sdk_evidence(path, name, count) for name, path in
              [('minimumSdkTests', minimum), ('currentSdkTests', current)]}
    if result['minimumSdkTests']['cases'] != result['currentSdkTests']['cases']:
        raise ValueError('Actual minimum/current SDK testcase identities differ')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix', type=Path, default=BASE / 'current-matrix.json')
    parser.add_argument('--testcases', type=Path, default=BASE / 'expected-testcases.json')
    parser.add_argument('--archive', type=Path, default=ROOT / 'plugin/build/distributions/bracket-pair-guides-0.0.6.zip')
    parser.add_argument('--freeze-testcases', action='store_true')
    parser.add_argument('--minimum-results', type=Path, default=ROOT / 'plugin/build/test-results/minimumSdkTests')
    parser.add_argument('--current-results', type=Path, default=ROOT / 'plugin/build/test-results/currentSdkTests')
    parser.add_argument('--expected-sdk-tests', type=int, default=29)
    parser.add_argument('--expected-target-count', type=int, default=13,
                        help='Review a changed fresh recommendation matrix before explicitly accepting its full count')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--cleanup-created-ides', action='store_true')
    parser.add_argument('--minimum-free-gib', type=int, default=9)
    args = parser.parse_args()
    try:
        if not args.matrix.is_file(): raise ValueError('Record fresh metadata using recordSerialVerifierMatrix first')
        matrix = json.loads(args.matrix.read_text())
        if matrix.get('schema') != SCHEMA or matrix.get('failureLevels') != LEVELS or matrix.get('gradleProjects') != PROJECTS:
            raise ValueError('Require fresh redesign matrix with all physical projects and eight strict levels')
        targets = matrix.get('targets', [])
        if (not targets or len(targets) != len(set(targets)) or
                any(not re.fullmatch(r'(IC|IU)-[0-9][A-Za-z0-9.]*', t) for t in targets)):
            raise ValueError('Matrix must contain nonempty unique declared IC/IU targets')
        if len(targets) != args.expected_target_count:
            raise ValueError(f'Fresh matrix has {len(targets)} targets, not reviewed count {args.expected_target_count}; review all targets, then set --expected-target-count. No target is dropped.')
        archive = args.archive.resolve()
        expected_archive = (ROOT / 'plugin/build/distributions/bracket-pair-guides-0.0.6.zip').resolve()
        if archive != expected_archive or not archive.is_file():
            raise ValueError('Build the current default buildPlugin archive separately before freezing inputs')
        archive_sha = sha(archive)
        sources = source_manifest()
        if matrix.get('sourceSha256') != sources or matrix.get('archiveSha256') != archive_sha:
            raise ValueError('Current source/archive differs from fresh matrix; no historical archive/source substitution')
        if args.freeze_testcases:
            if args.execute or args.testcases.exists(): raise ValueError('Freeze once to a new testcase file; execution is separate')
            evidence = sdk_pair(args.minimum_results.resolve(), args.current_results.resolve(), args.expected_sdk_tests)
            receipt = {'schema': SCHEMA, 'matrixSha256': sha(args.matrix), 'archiveSha256': archive_sha,
                       'sourceSha256': sources, 'expectedSdkTests': args.expected_sdk_tests,
                       'cases': evidence['minimumSdkTests']['cases'], 'sdkEvidence': evidence,
                       'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}
            args.testcases.parent.mkdir(parents=True, exist_ok=True)
            evidence_dir = args.testcases.with_suffix('')
            evidence_dir.mkdir(exist_ok=False)
            for task, original in [('minimumSdkTests', args.minimum_results), ('currentSdkTests', args.current_results)]:
                destination = evidence_dir / task; destination.mkdir()
                for xml in original.glob('TEST-*.xml'): shutil.copy2(xml, destination / xml.name)
            receipt['initialEvidenceDirectory'] = str(evidence_dir.resolve())
            save(args.testcases, receipt)
            print(json.dumps({'frozenTestcases': str(args.testcases), 'count': args.expected_sdk_tests,
                              'minimumCurrentSameCases': True, 'archiveSha256': archive_sha}, indent=2))
            return 0
        if not args.testcases.is_file(): raise ValueError('Freeze current passing minimum/current SDK case identities first using --freeze-testcases')
        testcase_record = json.loads(args.testcases.read_text())
        if (testcase_record.get('schema') != SCHEMA or testcase_record.get('matrixSha256') != sha(args.matrix) or
                testcase_record.get('archiveSha256') != archive_sha or testcase_record.get('sourceSha256') != sources or
                testcase_record.get('expectedSdkTests') != args.expected_sdk_tests):
            raise ValueError('Testcase receipt must belong to this fresh matrix/source/archive/count')
        expected_cases = testcase_record['cases']
        if len(expected_cases) != args.expected_sdk_tests:
            raise ValueError('Frozen testcase receipt is incomplete')
        inventory = cached_ides()
        plan = {'schema': SCHEMA, 'matrix': str(args.matrix.resolve()), 'matrixSha256': sha(args.matrix),
                'frozenTargets': targets, 'failureLevels': LEVELS, 'archive': str(archive), 'archiveSha256': archive_sha,
                'sourceSha256': sources, 'matrixGitHead': matrix.get('gitHead'),
                'currentGitHead': subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True, capture_output=True, check=True).stdout.strip(),
                'testcases': str(args.testcases.resolve()), 'expectedSdkTests': args.expected_sdk_tests,
                'cachedPaths': {t: str(p) if (p := existing_path(t, inventory)) else None for t in targets},
                'sdkTasks': [':plugin:minimumSdkTests', ':plugin:currentSdkTests'],
                'cleanupCreatedOnly': args.cleanup_created_ides, 'minimumFreeBytes': args.minimum_free_gib * 1024 ** 3,
                'concurrencyRequirement': 'one serial Gradle/IDE acquisition owner; no concurrent consumer during created-only cleanup'}
        print(json.dumps(plan, indent=2), flush=True)
        if not args.execute: return 0
        lock_file = (ROOT / 'outputs/issue-97/redesign/verification.lock').open('a+')
        try: fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise ValueError('Another redesign verification runner owns verification.lock')
        run = BASE / 'runs' / datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        inputs = run / 'inputs'; inputs.mkdir(parents=True, exist_ok=False)
        for source in [Path(__file__), BASE / 'serial-verifier.init.gradle', BASE / 'ide-acquisition.init.gradle', args.matrix, args.testcases, archive]:
            shutil.copy2(source, inputs / source.name)
        original_evidence = Path(testcase_record['initialEvidenceDirectory'])
        for task, initial in testcase_record['sdkEvidence'].items():
            destination = inputs / 'initial-sdk-evidence' / task; destination.mkdir(parents=True)
            for name, digest in initial['xmlSha256'].items():
                xml = original_evidence / task / name
                if not xml.is_file() or sha(xml) != digest: raise ValueError('Frozen initial SDK XML changed/missing')
                shutil.copy2(xml, destination / name)
        save(run / 'plan.json', plan)
        save(run / 'input-hashes.json', {str(p.relative_to(inputs)): sha(p) for p in inputs.rglob('*') if p.is_file()})
        save(run / 'pre-existing-transform-roots.json', sorted(str(p) for p in transform_roots()))
        def unchanged():
            return archive.is_file() and sha(archive) == archive_sha and source_manifest() == sources
        flags = ['./gradlew', '--no-daemon', '--no-parallel', '--max-workers=2', '--no-configuration-cache']
        sdk_records = {}
        for task in ['minimumSdkTests', 'currentSdkTests']:
            if not unchanged(): raise ValueError('Source/archive changed before official SDK task')
            target_dir = run / 'sdk-contracts' / task; target_dir.mkdir(parents=True)
            command = flags + [':plugin:' + task, '--info']
            code = command_run(command, target_dir / 'task.log')
            xml_dir = ROOT / 'plugin/build/test-results' / task
            for xml in xml_dir.glob('TEST-*.xml'): shutil.copy2(xml, target_dir / xml.name)
            evidence = sdk_evidence(target_dir, task, args.expected_sdk_tests)
            evidence['command'] = command; evidence['exit'] = code
            evidence['casesMatchFrozen'] = [list(case) for case in evidence['cases']] == expected_cases
            evidence['archiveSha256After'] = sha(archive)
            evidence['passed'] = code == 0 and evidence['casesMatchFrozen'] and unchanged()
            sdk_records[task] = evidence; save(target_dir / 'summary.json', evidence)
            if not evidence['passed']: raise ValueError(f'Current official {task} failed; evidence retained, no verifier PASS')
        if sdk_records['minimumSdkTests']['cases'] != sdk_records['currentSdkTests']['cases']:
            raise ValueError('Fresh official minimum/current testcase identities differ')
        records = []
        for target in targets:
            record = {'target': target, 'strictPass': False, 'failureLevels': LEVELS}
            records.append(record)
            record['diskBefore'] = free_record(plan['minimumFreeBytes'])
            record['archiveShaBefore'] = sha(archive)
            before_roots, before_installers = transform_roots(), installers(target)
            ide = existing_path(target, cached_ides()); created = ide is None
            try:
                if not unchanged() or not record['diskBefore']['admitted']:
                    raise ValueError('Source/archive changed or insufficient free disk before target')
                if created:
                    code, version = target.split('-', 1)
                    command = flags + ['--init-script', str(inputs / 'ide-acquisition.init.gradle'),
                               ':plugin:resolveCompatibilityIde', '-PcompatibilityIdeType=' + code,
                               '-PcompatibilityIdeVersion=' + version, '--info']
                    record['resolveCommand'] = command
                    record['resolveExit'] = command_run(command, run / (target + '-resolve.log'))
                    if record['resolveExit']: raise ValueError('Selected IDE acquisition failed')
                    matches = re.findall(r'Resolved compatibility IDE platformPath: (.+)', (run / (target + '-resolve.log')).read_text())
                    if len(matches) != 1: raise ValueError('Expected one selected platformPath')
                    ide = Path(matches[0].strip()).resolve()
                record['identity'] = validate(target, ide)
                record['acquiredThisTarget'] = created
                record['createdArtifactInventory'] = {
                    'selectedTransformRoot': str(ide.parent.parent) if ide.parent.name == 'transformed' and ide.parent.parent not in before_roots else None,
                    'selectedTargetInstallers': sorted(str(p) for p in installers(target) - before_installers)}
                if not unchanged(): raise ValueError('Source/archive changed during acquisition')
                reports = run / 'reports' / target
                command = flags + ['--offline', '--init-script', str(inputs / 'serial-verifier.init.gradle'),
                           ':plugin:verifyPlugin', '-PverifierTarget=' + target, '-PverifierIdePath=' + str(ide),
                           '-PverifierMatrixPath=' + str(inputs / args.matrix.name), '-PverifierReportPath=' + str(reports), '--info']
                record['verifyCommand'] = command
                record['verifyExit'] = command_run(command, run / (target + '-verify.log'))
                record['archiveShaAfter'] = sha(archive)
                verdicts = list(reports.rglob('verification-verdict.txt'))
                record['verdicts'] = [{'path': str(p), 'text': p.read_text().strip()} for p in verdicts]
                record['strictPass'] = record['verifyExit'] == 0 and len(verdicts) == 1 and verdicts[0].read_text().strip() == 'Compatible' and unchanged()
                record['status'] = 'finished'
                save(run / (target + '.json'), record)
                if created and args.cleanup_created_ides and record['strictPass']:
                    record['retirement'] = retire_owned(target, ide, before_roots, before_installers, record['identity'])
                elif created and args.cleanup_created_ides:
                    record['cleanupPreservedReason'] = 'Failed verification; acquisition artifacts preserved'
            except (ValueError, OSError, json.JSONDecodeError) as error:
                record['status'] = 'failed'; record['error'] = str(error)
                record['unclassifiedNewTransformRootsRetained'] = sorted(str(p) for p in transform_roots() - before_roots)
                record['newTargetInstallersRetained'] = sorted(str(p) for p in installers(target) - before_installers)
            record['diskAfter'] = free_record(plan['minimumFreeBytes'])
            save(run / (target + '.json'), record); print(json.dumps(record), flush=True)
            if not unchanged(): break
        passed = [r['target'] for r in records if r['strictPass']]
        summary = {'schema': SCHEMA, 'frozenTargets': targets, 'strictPassedThisArchive': passed,
                   'pendingTargets': [t for t in targets if t not in passed], 'allTargetsPassed': passed == targets,
                   'sdkTasksPassed': all(r['passed'] for r in sdk_records.values()),
                   'minimumCurrentSameCases': sdk_records['minimumSdkTests']['cases'] == sdk_records['currentSdkTests']['cases'],
                   'expectedSdkTests': args.expected_sdk_tests, 'archiveSha256': archive_sha, 'run': str(run)}
        save(run / 'summary.json', summary); print(json.dumps(summary, indent=2), flush=True)
        return 0 if summary['allTargetsPassed'] and summary['sdkTasksPassed'] else 1
    except (ValueError, OSError, json.JSONDecodeError, ET.ParseError) as error:
        if 'run' in locals(): save(run / 'failure.json', {'error': str(error), 'passed': False})
        parser.error(str(error))


if __name__ == '__main__':
    raise SystemExit(main())
