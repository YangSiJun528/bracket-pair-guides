from pathlib import Path
import datetime, hashlib, json, shutil, subprocess, sys, xml.etree.ElementTree as ET

candidate = Path(__file__).resolve().parents[3]
baseline = Path('/private/tmp/bpg-indent-baseline-qz897y1i/bracket-pair-guides')
root = Path(__file__).resolve().parent / 'performance'
root.mkdir(exist_ok=False)
revisions = {'A': 'c9be822bfc0d74e7785406d3b664fa72ade0714a', 'B': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=candidate, text=True).strip()}
assert not subprocess.check_output(['git', 'diff', '--name-only'], cwd=candidate, text=True).strip(), 'Freeze tracked source before measuring'
manifest = json.loads((root.parent / 'performance-harness-source-sha256.json').read_text())
for rel, expected in manifest.items():
    for tree in (baseline, candidate):
        assert hashlib.sha256((tree / rel).read_bytes()).hexdigest() == expected, str(tree / rel)
plan = json.loads((root.parent / 'performance-plan.json').read_text())
common = ['./gradlew', '--no-daemon', '--max-workers=1', '--no-configuration-cache', '--console=plain', '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.jvmargs=-Xmx4g']
results = []
for pair_index, order in enumerate(['AB', 'BA', 'AB', 'BA', 'AB', 'BA'], 1):
    for position, side in enumerate(order, 1):
        tree = baseline if side == 'A' else candidate
        out = root / f'pair-{pair_index:02d}-{position}-{side}'
        out.mkdir()
        cmd = common + ['-Dissue97.perf.workload=indentation', f'-Dissue97.perf.output={out / "sdk.jsonl"}', f'-Dissue97.perf.sourceRevision={revisions[side]}', '-Dissue97.perf.host=com.sijunyang.bracketpairguides.comparison.CandidateComparisonHost', '-Dissue97.perf.warmup=5', '-Dissue97.perf.repeat=30', '-Dissue97.perf.cancelTrials=1', '-Dissue97.perf.expectedIdeBuild=IC-241.19416.15', ':plugin:sdkPerformance', '--tests', 'com.sijunyang.bracketpairguides.comparison.SdkComparisonMeasurementTest.testSdkComparison']
        started = datetime.datetime.now(datetime.timezone.utc).isoformat()
        print(f'START pair={pair_index} position={position} side={side} {started}', flush=True)
        (out / 'command.json').write_text(json.dumps({'cwd': str(tree), 'argv': cmd, 'revision': revisions[side], 'startedUtc': started}, indent=2) + '\n')
        with (out / 'gradle.log').open('w') as log:
            result = subprocess.run(cmd, cwd=tree, stdout=log, stderr=subprocess.STDOUT)
        for rel in ['plugin/build/test-results/sdkPerformance', 'plugin/build/sdk-measurement/descriptor-evidence.json', 'plugin/build/sdk-measurement/test-resources-evidence.json']:
            source = tree / rel
            target = out / Path(rel).name
            if source.is_dir(): shutil.copytree(source, target)
            elif source.is_file(): shutil.copy2(source, target)
        entry = {'pair': pair_index, 'position': position, 'side': side, 'sourceRevision': revisions[side], 'exitCode': result.returncode, 'evidence': str(out), 'finishedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        if result.returncode == 0:
            records = [json.loads(line) for line in (out / 'sdk.jsonl').read_text().splitlines()]
            assert len([r for r in records if r['kind'] == 'indentation']) == 30
            assert any(r['kind'] == 'completed' for r in records)
            assert any(r['kind'] == 'indentation-cleanup' and r['remainingGuideMarkup'] == 0 for r in records)
            entry['measuredRoundTrips'] = 30
        results.append(entry)
        (root / 'runs.json').write_text(json.dumps(results, indent=2) + '\n')
        print(f'FINISH pair={pair_index} side={side} exit={result.returncode}', flush=True)
        if result.returncode: sys.exit(result.returncode)
print('Completed all 12 fresh JVM runs', flush=True)
