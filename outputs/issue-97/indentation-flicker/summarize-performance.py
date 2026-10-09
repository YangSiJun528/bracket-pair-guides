from pathlib import Path
import json, math, statistics as st
root = Path(__file__).resolve().parent / 'performance'
runs = json.loads((root / 'runs.json').read_text())
assert len(runs) == 12 and all(r['exitCode'] == 0 for r in runs)
observations = []
environments = []
for run in runs:
    records = [json.loads(line) for line in (Path(run['evidence']) / 'sdk.jsonl').read_text().splitlines()]
    env = next(r for r in records if r['kind'] == 'environment')
    environments.append(env)
    samples = [r for r in records if r['kind'] == 'indentation']
    assert len(samples) == 30 and all(r['textRestored'] and r['ownedWorkersQuiescent'] for r in samples)
    assert any(r['kind'] == 'completed' for r in records)
    assert any(r['kind'] == 'indentation-cleanup' and r['remainingGuideMarkup'] == 0 for r in records)
    actions = sorted({a['action'] for r in samples for a in r['actions']})
    item = {k: run[k] for k in ['pair', 'position', 'side', 'sourceRevision']}
    item['actions'] = {}
    for action in actions:
        events = [a for r in samples for a in r['actions'] if a['action'] == action]
        assert len(events) == 30 and all(e['actionEdtAllocatedBytes'] is not None for e in events)
        stats = {'count': len(events), 'guidePresentCount': sum(e['guidePresentAfterAction'] for e in events), 'previousMarkValidCount': sum(e['previousGuideMarkValidAfterAction'] for e in events)}
        for metric in ['actionEdtNs', 'actionEdtAllocatedBytes']:
            values = sorted(e[metric] for e in events)
            stats[metric] = {'median': st.median(values), 'p95NearestRank': values[math.ceil(.95*len(values))-1], 'min': min(values), 'max': max(values)}
        item['actions'][action] = stats
    vals = [r['asyncAllocatedBytes'] for r in samples]
    item['asyncAllocatedBytesMedian'] = st.median(vals) if all(v is not None for v in vals) else None
    observations.append(item)
for key in ['ide','ideVersion','java','javaVendor','vm','os','arch','maxHeapBytes','processors','warmups','repeats','harnessClassSha256','adapterClassSha256']:
    assert len({str(e[key]) for e in environments}) == 1, (key,[e[key] for e in environments])
comparisons = []
for action in observations[0]['actions']:
    for metric in ['actionEdtNs','actionEdtAllocatedBytes']:
        pairs = []
        for number in range(1,7):
            a = next(r for r in observations if r['pair']==number and r['side']=='A')['actions'][action][metric]
            b = next(r for r in observations if r['pair']==number and r['side']=='B')['actions'][action][metric]
            pairs.append({'pair':number,'baselineMedian':a['median'],'candidateMedian':b['median'],'medianRatio':b['median']/a['median'],'baselineP95':a['p95NearestRank'],'candidateP95':b['p95NearestRank'],'p95Ratio':b['p95NearestRank']/a['p95NearestRank']})
        ratio = st.median(p['medianRatio'] for p in pairs)
        comparisons.append({'action':action,'metric':metric,'pairs':pairs,'medianOfBaselineJvmMedians':st.median(p['baselineMedian'] for p in pairs),'medianOfCandidateJvmMedians':st.median(p['candidateMedian'] for p in pairs),'medianPairedRatio':ratio,'medianPairedP95Ratio':st.median(p['p95Ratio'] for p in pairs),'flagOver120Percent':ratio>1.20})
summary = {'runs':observations,'comparisons':comparisons,'matchingEnvironment':{k:environments[0][k] for k in ['ide','java','os','arch','maxHeapBytes','warmups','repeats']},'totalMeasuredActions':720,'limitations':['One warmed Java corpus, actual editor actions with headless ACTIVE; Driver separately checks focused painting.','Median of six paired fresh-JVM ratios; p95 retained as secondary, not pooled percentiles.','EDT action and inherited coroutine allocation scopes are non-additive.','No whole IDE allocation, long-prefix cost guarantee, or zero-flicker claim across all edits.']}
(root / 'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for c in comparisons: print(c['action'],c['metric'],'baseline',c['medianOfBaselineJvmMedians'],'candidate',c['medianOfCandidateJvmMedians'],'pairedRatio',round(c['medianPairedRatio'],4),'flag',c['flagOver120Percent'])
for side in ['A','B']:
    for action in observations[0]['actions']:
        entries=[r['actions'][action] for r in observations if r['side']==side]
        print(side, action, 'retained',sum(e['guidePresentCount'] for e in entries),'/',sum(e['count'] for e in entries))
