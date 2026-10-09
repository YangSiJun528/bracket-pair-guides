# PR98 Benchmark Gate의 baseline 부재와 비교 범위

Required Benchmark Gate는 현재 실패 상태가 맞습니다. 새 benchmark identity에 대응하는 실제 history boundary가 없으므로 회귀 검사가 성립하지 않았습니다. alert0건을 성능 통과로 표현하면 안 됩니다.

## 실제 원격 보고서의 로컬 검토

- PR 소스: `120360c713e7fde9c72b23ffd1677db14587501b`. 비교 대상 unchanged main: `516d4edda86a987aad66a3be9227fca67dd4f1f2`.
- 로컬 원본: `/private/tmp/issue-97-pr98-bencher-evidence/analyzeCold/expanded-report.json`, SHA256 `c7f6e1582f95cfc5cd2c8ccb3c1a28e00ebd860a0408b83f6d2c7a60f7c9a79d`.
- 원본 JSON에서 analyzeCold8 cases ×2 measures =16개를 확인했습니다. 모든 threshold는 percentage/upper_boundary0.2/max_sample_size1이며 **16개 boundary가 전부 `{}`**, alerts는 빈 배열입니다. threshold 설정이 존재하는 것과 실제 비교 경계가 생성된 것은 별개입니다.
- 메인이 확인한 CI 실행 범위는 다섯 jobs 중 첫 analyzeCold뿐입니다. 나머지 analyzeReuse/visibleQuery/repair/cancelledAttempt를 실행·통과했다고 주장하지 않습니다. 로컬 expanded report 하나로 전체 workflow 상태를 독립 재검증했다고 주장하지도 않습니다.
- Required Gate의 no-baseline 거절은 false pass 방지 동작입니다.20% 기준을 낮추거나 빈 boundary/unsupported case를 통과로 처리할 이유가 되지 않습니다.

## unchanged main과 PR의 구현 차이

PR의 `CalculationContractBenchmark`는64/4096 ×siblings/nested/sparse/malformed의8 inputs에 다섯 use cases를 적용한40 cases입니다. `CalculationWorkload`는 recorded text 전체를512-character chunks로 스캔하고, tokens/activePair/guidePosition 모두 요청하며128-character prefix/4096-character continuation, 동일 query range·checksum을 사용합니다. sparse는 pairCount개의 문장에 둘러싼 **한 쌍**, malformed는 `{]` 반복에서 `{`만 인식하는 unmatched opens입니다. 이름이나 pairCount만 맞춰 다른 입력을 쓰면 동일 benchmark가 아닙니다.

main의 `DocumentBrackets.recognize`는 실제 Editor.highlighter와 `DocumentBraceGrammar`를 직접 순회합니다. `BracketAnalysis.analyze`는 SDK `AnalysisInput`/`AnalysisStamp`, `SnapshotAssembly`, `DocumentGuidePositions`, `DocumentBracketIndexes.canonical`을 조립합니다. PR96의 capture/input protocol이나 PR의 whole-attempt `BracketCalculator`가 없습니다. SDK stamp는 document revision/FileType/highlighter identity/tab size를 포함합니다.

| 새 use case | main의 실제 연결점 | 비교 가능한 범위와 제한 |
|---|---|---|
| analyzeCold8 | public Java PairingMachine/PairTable, internal PairCollection/SnapshotAssembly/active·token·guide indexes | 동일 recorded 입력·결과를 계산하도록 additive adapter를 만들 수 있음. 실제 SDK recognition을 recorded scan으로 연결하는 별도 경계가 필요하며 전체 동일 scope는 검증 전 확정 불가 |
| analyzeReuse8 | 위 계산 +DocumentBracketIndexes.canonical | 실제 document identity/revision/coverage/layout 기반 canonical reuse를 유지해야 함. 자체 cache·fake stamp로 대체하면 unchanged-main reuse 비용이 아님 |
| visibleQuery8 | BracketSnapshot.visibleTokens(TextRange,focusOffset=1,limit=2048) | 가장 직접적 대응. setup 결과·range·checksum 의미 일치 확인 필요 |
| repair8 | presentation.GuidePositionFallback.guideAfterChange의 bounded exact scan | main에도256-line/32768-character budget의 exact fallback은 존재. 그러나 UI 동기 Editor/Document/settings 경로와 PR의 suspend captured-prefix repair는 동일 측정 경계가 아님 |
| cancelledAttempt8 | ProgressIndicator.checkCanceled/CancellationProbe |8번째 호출에서 취소시키는 비용 관측 가능. checkpoint 위치·완료 작업량이 달라 equal-work throughput 비교 불가 |

main의 Kotlin internal 계산/index/assembly 접근에는 benchmark 전용 friend compilation 또는 동일-module additive bridge가 필요하고, SDK 의존 타입에는 IntelliJ SDK compile/runtime classpath가 필요합니다. public Java pairing API만으로 전체 분석·canonical reuse를 보존할 수 없습니다. 이 계획의 구현·컴파일·fingerprint·측정은 수행하지 않았습니다.

repair의 malformed2 cases는 pair가 없어 null이 될 수 있지만, 이 퇴화 경로 일치는 다른6 cases의 repair scope 일치를 증명하지 않습니다. main fallback/index 생성을 새 repair와 같은 이름으로 기록하거나, PR96/current의 capture orchestration을 main benchmark에 복제하면 같은 production 경로의 baseline이라는 주장이 성립하지 않습니다.

## baseline 생성과 마이그레이션 수용의 구분

현재 candidate를 실행한 수치를 unchanged-main baseline으로 relabel하면 source provenance가 거짓이 됩니다. 똑같은 입력·새 identity·JAR를 main 이름으로 올리더라도 분석한 production이 main으로 바뀌지 않습니다. 이후 candidate의 자기 비교는 현재 PR의 main 대비 회귀를 소급 검증하지 않습니다. 기존 low-level sort/pairing history도 새 whole-analysis/repair identity의 baseline으로 바꿀 수 없습니다.

방어 가능한 조사 순서는 unchanged main의 production bytes를 보존한 additive adapter에서 실제 연결점을 사용하고, 입력·결과·reuse·범위를 검증한 뒤 비교 가능한 부분만 분리해 보고하는 것입니다. 그것만으로40 cases 전체에 strict regression history가 생겼다고 선언할 수 없습니다. 현재 local pure/SDK 비교의 실패·개선·범위 한계도 별도 유지해야 합니다.

**명시적인 일회성 마이그레이션 수용**은 새 benchmark identity와 비교 불가능한 과거 경계를 인지하고, 기존 no-baseline 실패 및 알려진 회귀를 보존한 상태에서 사용자가 별도로 내리는 위험 수용 결정입니다. 이것은 회귀 검사 PASS가 아닙니다. 그런 결정을 했다고 threshold·필수 검사 결과·unsupported 비교를 통과로 바꾸거나 candidate 수치를 unchanged-main history로 위장할 수 없습니다. 본 문서는 그 수용을 수행·승인하지 않습니다.

이 기록은 로컬 파일 읽기·JSON/해시 확인·git show 기반 조사뿐입니다. 소스/빌드/측정/원격 제출/커밋은 수행하지 않았습니다.
