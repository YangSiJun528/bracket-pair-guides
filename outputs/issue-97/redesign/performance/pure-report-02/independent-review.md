# pure02 독립 검토

현재 증거로 성능 무회귀 통과를 판정할 수 없습니다. 실행·집계의 무결성과 성능 판정은 별개입니다. sparse4096 full analyze의 cold/reuse latency 회귀가 정식 두 번째 campaign에서도 반복됩니다. repair 개선은 이 실패를 상쇄하거나 지우지 않습니다.

## 원본과 집계 확인

- [campaign](../pure-02/campaign.json): baseline072533fa3e223d53f937a15d50fe8637add8b2cb, candidate8d853ea08d92150c127efe43d807771542ef84d6. 다섯 jobs ×6 AB/BA쌍 ×2 sides =60 invocations, 전부 exit0/no timeout. 각 invocation의8 cases, 전체480 case/run estimates를 확인했습니다. 이는40 고유 cases를6쌍에서 비교한 것입니다.
- 60 raw JSON 및60 command record의 SHA256을 report 기록과 독립 대조했습니다. 원본 mean score와 allocation score로80 metrics ×6쌍의 baseline/candidate 값·비율·차이 및 paired median을 다시 계산해 정확 일치를 확인했습니다. 원본·기존 report는 수정하지 않았습니다.
- JMH1.37/Temurin17.0.17+10 aarch64, heap2GiB,1 thread,2 forks,2 warmup×1s,3 measurement×1s,GC profiler,240s/job 조건입니다. 각 case/run 값은 두 fork·여섯 measurement의 JMH **mean score**이며, report의 internal `median` slot 명칭은 개별 JVM median을 뜻하지 않습니다. fork·iteration을 독립 paired samples로 늘리지 않았습니다.
- report 집계80 metrics/60 any increase/34 all-six increase/7 >20% flags를 재확인했습니다. `report.json.performancePass=null`은 자동 판단 유보이고, [main-review](main-review.json)의 `performancePass=false`와 모순되지 않습니다.
- candidate JMH SHA256은79518a181b347d7410de5a382ccec3882f37ce59cc7ebdbd1b1f8b0943a020c6, baseline은0d2169b3dd8d503acf486c36361692c3ac36868b1d2ae94a35d6dcb98a4a2afe입니다. candidate JMH는 sdk01 freeze의 JMH와 같은 SHA입니다. git source 비교에서4b977d8→8d853ea의 analysis-core/src/main, analysis-model/src/main, benchmarks/src/main 차이가 없습니다. UI/runtime 후속 수정이 이 순수 계산 JAR의 새 개선으로 해석되면 안 됩니다. wrapper의 freeze 전후 일치 기록은 실행 시점 증거이며, 검토 중 JVM이나 측정을 새로 실행하지 않았습니다.

## 핵심 관측

비율은6쌍 C/B ratio의 median, 차이는6쌍 estimate delta의 median입니다. 둘을 별도 요약하므로 한 대표 baseline 값에 비율을 곱해 차이를 복원하지 않습니다.

| 지표 | C/B | 차이 | 증가 쌍 |
|---|---:|---:|---:|
| analyzeCold / sparse / 4096 / latency / median | 1.544735 | +184045.246699 ns/op | 6/6 |
| analyzeReuse / sparse / 4096 / latency / median | 1.521955 | +181289.114619 ns/op | 6/6 |
| repair / sparse / 4096 / allocation_bop / median | 0.415313 | -26636.035972 B/op | 0/6 |
| repair / sparse / 64 / allocation_bop / median | 0.477634 | -5652.007007 B/op | 0/6 |
| repair / sparse / 4096 / latency / median | 0.494941 | -6065.830936 ns/op | 0/6 |
| repair / sparse / 64 / latency / median | 0.558674 | -1180.737800 ns/op | 0/6 |
| repair / siblings / 4096 / latency / median | 1.187023 | +36.674593 ns/op | 6/6 |

Sparse4096 cold의 각 pair ratio는1.52275,2.08542,1.56672,1.14457,1.51203,2.01336이고 reuse는1.53832,1.95656,1.50559,1.16911,1.50254,1.95671입니다. 순서 교대에도 양방향 모든 쌍에서 증가했습니다. JMH primary scoreConfidence의 candidate 하한이 baseline 상한을 넘는 쌍은 cold/reuse 각각2번·6번이고, 나머지는 겹칩니다. 이는 반복 관측의 강도와 불확실성을 함께 보여 줍니다. pair ratio의 모집단 CI나 독립 fork 기반 유의성 검정을 새로 주장하지 않습니다. 여섯 쌍만으로 원인이나 모든 환경에서의 효과를 확정할 수 없지만, 이를 무회귀 증거로 해석할 수는 없습니다.

Sparse repair의 allocation은4096에서약58.5%,64에서약52.2% 감소했고 latency는약50.5%·44.1% 감소했습니다. Nested repair도 할당·latency가 모두6쌍 개선됐습니다. 반대로 siblings4096 repair latency는1.1870배(+36.67ns/op)로6쌍 증가합니다. 작은 절대값이지만20% 미만이라는 이유로 숨기지 않습니다. siblings64 repair latency도1.1674배(+31.52ns/op),4/6 증가입니다.

## 20% 미만의 일관된 증가

아래는 cancelledAttempt를 제외한 **all-six increase** 중20% 이하 지표 전부입니다. 전체80 metrics 및 비일관 증가도 [report](report.json)에 남아 있습니다. 특히 siblings4096 full allocation은 약1.9~2.1%여도+55~59KB/op이며 상대값만 보고 무시할 수 없습니다. visibleQuery의 allocation 차이 약10^-6 B/op는 GC profiler의 매우 작은 추정값에 대한 비율입니다. 이를 실재하는 추가 객체 할당의 증거로 확정하지 않습니다.

| 지표 | C/B | 차이 |
|---|---:|---:|
| analyzeCold / siblings / 4096 / allocation_bop / median | 1.019376 | +55319.5277 |
| analyzeCold / siblings / 64 / allocation_bop / median | 1.016160 | +761.287322 |
| analyzeCold / siblings / 64 / latency / median | 1.035829 | +509.987359 |
| analyzeCold / sparse / 4096 / allocation_bop / median | 1.016904 | +8798.48834 |
| analyzeReuse / malformed / 64 / allocation_bop / median | 1.007902 | +87.9991678 |
| analyzeReuse / nested / 64 / allocation_bop / median | 1.008407 | +206.001321 |
| analyzeReuse / nested / 64 / latency / median | 1.029165 | +343.918922 |
| analyzeReuse / siblings / 4096 / allocation_bop / median | 1.020829 | +59423.5741 |
| analyzeReuse / siblings / 64 / allocation_bop / median | 1.021111 | +984.136483 |
| analyzeReuse / siblings / 64 / latency / median | 1.039041 | +570.208863 |
| analyzeReuse / sparse / 4096 / allocation_bop / median | 1.017394 | +9044.43643 |
| analyzeReuse / sparse / 64 / allocation_bop / median | 1.019733 | +248.000786 |
| repair / siblings / 4096 / latency / median | 1.187023 | +36.6745926 |
| visibleQuery / malformed / 4096 / allocation_bop / median | 1.078693 | +9.93229483e-07 |
| visibleQuery / malformed / 4096 / latency / median | 1.079164 | +0.170704172 |
| visibleQuery / malformed / 64 / allocation_bop / median | 1.069244 | +8.75636306e-07 |
| visibleQuery / malformed / 64 / latency / median | 1.069217 | +0.149275153 |
| visibleQuery / sparse / 4096 / allocation_bop / median | 1.010269 | +4.60120348e-07 |
| visibleQuery / sparse / 4096 / latency / median | 1.011549 | +0.0880992633 |
| visibleQuery / sparse / 64 / allocation_bop / median | 1.020002 | +8.8798935e-07 |
| visibleQuery / sparse / 64 / latency / median | 1.018573 | +0.140904452 |

## 취소5 flags의 해석

7개 >20% flag 중 정상 작업은 sparse4096 cold/reuse2개이고, 나머지5개는 cancelledAttempt입니다. malformed4096 allocation1.4303배(+20,872B/op), nested4096 allocation1.3042배(+28,376B/op)/latency1.3197배(+6,871ns/op), sparse4096 latency1.2276배(+1,291ns/op), sparse64 latency1.2845배(+1,438ns/op)이며 모두6쌍 증가입니다. 취소는 양측의 architecture-specific **8번째 checkpoint**에서 발생하므로 완료한 입력·알고리즘 작업량이 같지 않습니다. 비용 관측은 보존하되 equal-work throughput 회귀나 동일 cancellation latency SLA로 표현하지 않습니다. 이 caveat로 정상 full sparse 회귀를 면제할 수 없습니다.

## 이전 증거와 거절한 실험

[pure01](../pure-report-01/report.json)은 원래 정식60회에서 sparse4096 cold2.1943배/reuse1.9364배 등 반복 회귀를 발견했습니다. 이후 scanner/배치 할당 정리와 scalar initialPrefix 포트는 repair 비용을 개선했고 현재 정식 repair 결과도 이를 지지합니다. 그러나 investigation01 diagnostic02의10warm reuse약2.139배, diagnostic03약1.994배 등 full sparse 문제는 지속됐습니다. 정식 campaign 간 ratio 변화만으로 효과 크기를 직접 추정하지 않습니다. campaign마다 다른 소스·실행 시점이며 각 동시 baseline 비교가 판단 단위입니다.

[토큰 경계 조사](../investigation-01/token-boundary-options.md)는 양측 source scan이 JFR CPU의약80%이며 최종 recognize caller에 양쪽 loop가 inline됨을 확인했습니다. 프로파일된 timing은 오히려 candidate가 빠른 방향이었으므로 JFR/JIT로 무프로파일 회귀를 무효화하거나 callback/escape analysis/loop optimization 중 하나를 확정 원인으로 지목할 수 없습니다. private helper 추출은 실제 SDK와 recorded adapter에 함께 적용됐지만 이 정식 결과에서 full sparse 회귀 해결을 입증하지 못했습니다.

[batch seam diagnostic01](../investigation-02/diagnostic-01/observations.json)은 sparse4096 reuse2.603330배를 기록했습니다. 이 실험은 거절되어 core가 정확히4b로 복구됐고 새 public 입력 인터페이스는 남지 않았습니다. 해당 실패나 diagnostic을 삭제하거나 pure02로 대체하지 않습니다. 현재 formal은 복구 후 source/JAR의 독립 증거입니다. UI 재사용·lazy token initialization은 실제 SDK 경로에서 별도로 판단해야 하며 순수 JAR 결과를 그 개선의 증거로 전용하지 않습니다.

## 판정 범위

로컬20% 규칙은 조사 flag이며 자동 합격·자동 실패 규칙이 아닙니다.20% 이하의 일관된 증가도 보존하며 개선과 회귀를 합산해 상쇄하지 않습니다. 실제 원격 Bencher historical gate는 이 검토에서 실행하지 않았고, 같은 장비의 로컬6쌍 비교가 원격 trusted-PR gate 통과를 뜻하지 않습니다. SDK 실제 read/writer/native/UI/retention 성능과 supported-IDE/Driver correctness도 이 순수 JMH 검토 범위 밖입니다.

판정: 실행·집계 무결성은 확인했습니다. 정상 sparse full/reuse 반복 회귀가 남아 있어 **성능 무회귀 통과 불가**입니다. 원인 확정·추가 변경·새 측정 없이 원본의 실패·개선·불확실성·시험 범위를 함께 보존합니다. 검토는 파일 읽기와 로컬 Python 집계/해시 비교만 수행했으며 production/하네스/기준·원본 결과를 수정하지 않았습니다.
