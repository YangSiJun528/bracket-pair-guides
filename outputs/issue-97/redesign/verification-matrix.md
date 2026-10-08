# Redesign verification ledger

구현HEAD8d853ea/productionf65/baseline072533f. 계획된로컬실행·집계·판정을완료했으며성능무회귀는통과하지못했습니다. 아래최종증거와과거source별이력을분리합니다.

## 최종 로컬 증거

| 항목 | 결과·근거 |
|---|---|
| rootcheck10 | [184PASS](final-check-10/summary.json),failure/error/skip0;기존182보존+새BMF2 |
| SDK-free |freshstandalone51PASS;입력guardPASS |
| actualSDK |min/current35개씩PASS,freshXML·IC241/JBR17,IU263/JBR25 |
| TestKit/정책 |compile32+packaging9+BMF11=buildlogic52PASS,freshXML |
| 패키징 |fiveownerJAR451관측classes/duplicate0/actualbyteguardPASS;451상수기준아님 |
| Qodana08 | [0findingsPASS](qodana/run-08/summary.json),exit0,failThreshold0,sourceUnchanged,uploadfalse |
| Driver13 | [exact12+상호작용2PASS](driver/compare-13/verification.json),class/resource동일;manifestBuild-JVM/Build-OS만차이 |
| strict13matrix03 | [13/13PASS](compatibility/runs/20261008T184514375884Z/main-review.json),8failurelevels유지 |
| artifactbinding | [exactSHA3af988…78](final-check-10/existing-validation-artifact-binding.json);Driver13/matrix03는기존실행증거연결,8d재실행아님 |
| SDKsetup09 | [16setupPASS](sdk-final-smoke-09/main-review.json),성능통과아님 |
| BMFfinal01 | [최종pair01양측40cases80metricsPASS](performance/bmf-final-01/summary.json),exactjq/cachestore+reuse/출력삭제후재실행 |
| preservation | [원본HEAD516dclean/미변경·baselineprotected동일·84dancestor](checkpoint-07/final-preservation-audit.json) |

root/pure기본cache는184/3problems로discard,wholecheckreuse불가입니다. 기존resolved-taskaudit의명시notCompatibleWithConfigurationCache한계를BMFexporterstore/reusePASS와구분합니다.

## 성능 판정 — 회귀가 남아 있음

정식 pure02의60회와 SDK02의96회 실행·raw 검증·집계·메인 판정·독립 감사를 완료했습니다. source/compiled/JAR freeze를 유지하고 실패 표본 제외·재시도·threshold 완화를 하지 않았습니다. **pure와 SDK의 performancePass는 모두false입니다.** 이는 성능 무회귀 통과가 아니며 기능·구조 검증 통과로 상쇄되지 않습니다.

| 비교 | 집계와 반복 증가 | 핵심 잔존 비용 |
|---|---|---|
| pure02 |80metrics,any increase60,6쌍 모두 증가34,>20%flag7 | sparse4096 full latency cold1.545배(+184045ns),reuse1.522배(+181289ns),각6/6 증가 |
| SDK02 |774metrics,any increase511,6쌍 모두 증가64,>20%flag40 | 동일 caret callback:all40→288B(+248B),latency2.261배(+18.281us);tokens0→128B,latency1.941배(+4.844us),모두6/6 증가 |

[Pure main review](performance/pure-report-02/main-review.json)·[독립 감사](performance/pure-report-02/independent-review.md), [SDK main assessment](performance/sdk-report-02/main-assessment.json)·[독립 감사](performance/sdk-report-02/independent-review.md)에 모든 증가와 원본 연결을 남깁니다. SDK96개의fresh passing XML·producer 재실행0·raw/복사 artifact hash 검증은 [execution audit](performance/sdk-report-02/execution-main-review.json)로 확인했습니다.

7pure flags 중2개는 sparse full latency,5개는 checkpoint8의 서로 다른 진행량을 갖는 cancellation입니다. SDK40flags는 중복 median/p95·resource 요약과 범위가 다른 계측을 포함하므로40독립 결함이 아니며 이전37flags와 뺄셈해 새 결함3개라고 할 수 없습니다. metric의 증가·인과·제품 전체 성능비는 구분합니다.20% 미만의 반복 증가도 누락하지 않았습니다.

비교 단위는6 AB/BA쌍입니다. JMH 값은2fork×3measurement iterations의mean score이고 SDK는 각fresh fixture JVM30trials의median/nearest-rank p95입니다. fork/trial을 추가 독립 pair로 풀링하지 않습니다. 측정 전 plan에 고정한pair01의 finalraw도 양측40cases·80metrics를 actualroot exporter로 변환해 jq identity/value 정확 일치와 cache 저장·재사용·출력 삭제 후 재실행을 [BMF final01](performance/bmf-final-01/summary.json)에서 확인했습니다. BMF 변환 통과는 성능 판정을 바꾸지 않습니다.

## 검증 해석의 한계

실제 UI callback 비용의 반복 증가는 남아 있습니다. 다만 construction fixture는 production의 공유factory 수명과 다르고, repair는 candidate per-read observer와 baseline의 관측 부재가 비대칭입니다. edit-restoration은 candidate owned full+repair와 baseline trace 밖daemon이 다르며 baseline 두 refusal corpora는 각각180회censored입니다. candidate의 올바른 복구 관측도repair독점 origin을 뜻하지 않습니다. 겹치는 allocation 범위를 합산하거나 추정observer비용을 빼지 않습니다.

SDK writer900회 중 실제inside-body overlap은baseline514/candidate497이며 나머지는미관측 overlap으로 보존합니다. 취소900회 중candidate1회completed-before-request도 유지합니다. payload30개씩은5초내해제됐지만 capture-release는양쪽XML18개씩같은capturedString이남았고batch/array생존은0입니다. 이를candidate-only leak 또는 전체retained-heap무결성으로 일반화하지 않습니다. null/unsupported/관측subset p95도 원본에 보존했습니다.

기본 configuration cache는 켠 상태였지만 root/pure는 각각184/3cache problems로discard되어 전체check 재사용은 불가합니다. 기존resolved-task audit의 notCompatibleWithConfigurationCache 한계이며 테스트 실패 수와 별개입니다. BMF exporter 자체의cache재사용만 별도로입증했습니다.

컴파일 접근 차단은 모든 알고리즘·스레드 버그 부재의 증명이 아닙니다. SDK가 금지한beforeRemoved 중nested marker추가나 임의listener예외의완전한rollback을 보장하지 않습니다. 실제 unrelated host write를chunk사이에배치해 전체attempt 재시작을 결정적으로입증하는통합계약, 지연된실제native traversal의stale completion재현, 별도IDE프로세스재시작시험은검증범위에포함되지않습니다.

측정 전용descriptor는양측자동startup/pass만억제하고서비스·다른SDKplugin을유지합니다. loadeddescriptor·비descriptor바이트·ownedattachment/lifetime을검사하지만controlledmeasurement가actualpluginstartup검증을대신하지않습니다. Headless SDK의활동입력과actualSwingfocus/paint도구분합니다.13IDE검증은정적Verifier이며모든IDE의GUI실행을뜻하지않습니다.

원격Bencherhistory gate와원격CI는사용자승인범위밖이므로실행하지않았습니다. 로컬결과를원격통과로쓰지않습니다. 원본실패·oracle정정·음성대조군·cache정리세부이력은[ledger](verification-matrix.md)에보존했습니다.

## BMF 수정·대조군 이력

caller의all충돌과Project-capturingprovider저장실패(integration01)를수정했습니다. positive11PASS,원래provider복원시새TestKit2개모두expectedfailure,oldrawintegration02와finalrawBMF01각actualjq/cache검증을완료했습니다. 원본실패·historicalproposal은보존합니다. [세부감사](performance/bmf-integration-review.md).

## 과거 소스별 보존 ledger

아래당시current/PENDING표기는과거실행시점이며최종상태가아닙니다.

## Historical source4b verification and performance

| Obligation | Evidence | Status |
|---|---|---|
| SDK-free model/core | performance/investigation-01/validation-03 +final-check-07/pure-source-parity.json | PASS51 fresh standalone run with exact current-source parity; no second standalone execution claimed |
| Same input/result semantics | fingerprint-05.json | PASS8 exact baseline/previous fingerprints; not performance approval |
| Actual minimum/current SDK | final-check-07 | PASS31 each on4b: IC241.19416.15/JBR17.0.12, IU263.6259.32/JBR25.0.4.1 |
| Complete root check07 | final-check-07 +main-review.json | PASS173=root142+current SDK31; model4/core47/UI3/runtime3/plugin4/min31/current31/build-logic50; all old134 case identities retained; build-logic50 XML fresh |
|4b static inspection | qodana/run-05 SARIF+image proof | PASS0/failThreshold0/339.386s/same4b source |
|4b release packaging/identity | final-check-07 | PASS five owner JARs/451 observed classes +current production byte identity/root guard; release3add72c4...; count is not a fixed threshold |
| Owned temporary cache retirement | checkpoint-07/owned-temp-retirement-01 | Only owned TestKit transforms2.82GB, active consumers0/evidence preserved |
| Earlier SDK smoke08 | setup16 commands | Setup only; later formal SDK01 completion is separate evidence |
| Formal pure comparison | performance/pure-01 + pure-report-01 |60 invocations completed on a86db3f; regression assessment FAILED |
| Repair allocation diagnostic | investigation-01/diagnostic-02 | Improved after scalar port; diagnostic only, no formal waiver |
| Sparse4096 reuse | investigation-01/diagnostic-03; diagnostic-02 long warmup | Still about1.994x; separate10warm about2.139x; UNRESOLVED |
| Formal actual SDK01 comparison | performance/sdk-01 +sdk-report-01 |96 invocations completed on4b,774metrics/37 >20% flags; main-assessment false, confounds preserved |
| Final873 Driver/13IDE matrix | planned source-specific reruns | PENDING; no source8ea inheritance |

## Historical source8ea evidence

The table below reports earlier manifests, including failures on still earlier revisions. None is automatically a873699ec pass. Historical release609f09d3.../visual1bbb6cb4... are not current-source artifacts.

| Obligation | Latest evidence | Status |
|---|---|---|
| SDK-free model/core | final-check-04/pure.log fresh45; final-check-06/pure-source-parity.json exact selected-file parity | PASS45, zero failed/skipped |
| Pure contracts | final-check-06 matching XML; model4+core41, including malformed token/prefix rejection and valid reuse afterwards | PASS45 |
| Actual Kotlin/Java compile restriction and positive controls | final-check-06 CompilationBoundaryTest32 | PASS32; actual UI core/runtime negatives, plugin-core and pure-SDK negatives included |
| Transitive/source/output/compiler-input bypass rejection | same32 suite and effective actual serialized plugin-input audit | PASS; original real serialization-plugin bypass/failure retained in earlier evidence |
| Packaging and benchmark-report policy | final-check-06 PackagedOwnerTest9 + BenchmarkReportTest9 | PASS18 |
| Module policy/bytecode architecture | final-check-06 UI3+runtime3+plugin architecture4 | PASS10 |
| Actual runtime/read/cancel/stale/lifetime/markup | listener-lifetime-01 actual minimumSdkTests; IC241.19416.15/JBR17 | PASS29; includes two actual editors sharing one document with independent close/publication |
| Complete root check and formatting | final-check-06 check.log/summary.json; source8ea | PASS134;583.4669s/zero fail-error-skip/sourceUnchanged=true; task reuse recorded |
| Release/visual archive separation | listener-lifetime-01 package audit | PASS451 classes; no duplicate owner classes or test leakage; current release609f09d3.../visual1bbb6cb4... |
| Current actual IDE execution | official currentSdkTests, expected29 identities | PASS29 listener-lifetime-01; zero fail/error/skip and same minimum identities |
| First supported IDE compatibility matrix | compatibility/runs/20261008T115340939459Z, old be4f620/ZIP b1442ca6... | FAILED exit1/1490.48s: IC5 strict PASS, IU4 same deprecated listener FAILED, remaining4 disk-guard NOT RUN |
| Supported listener fix | compatibility/document-listener-fix/proposed-session-ownership.patch applied exactly to three production files | Applied; actual min/current29 +check06 +Qodana04 PASS; fresh entire official13IDE matrix02 PASS13/13 strict (`20261008T125349781364Z`) |
| Task-owned cache retirement | compatibility/owned-cache-retirement-01; reports/product information/hashes preserved | Task-created IDE3/installers3 removed only; pre-existing none deleted;23.34GiB recovered |
| Driver rendering/state transitions | driver/compare-12/verification.json and command.json | PASS12 exact ARGB/PNG bytes and functional assertions;239.8648s/sourceUnchanged=true; unchanged pixel criteria |
| Driver production equivalence | driver/compare-12/production-equivalence.json | All production class/resource bytes equal; whole host/Linux ZIPs differ only through plugin JAR manifest Build-JVM/Build-OS metadata |
| Visual negative control | driver/mutation-08/verification.json, earlier source | Intentional exit1/JUnit failure: nine expected paint comparisons detect removal; not a passing mutation suite |
| Fresh entire supported IDE matrix02 | compatibility/runs/20261008T125349781364Z/summary.json; compatibility/serial-command-02.json | PASS13/13 strict on exact release609f09d3.../source8ea07aa; exit0/2690.359957s; pendingTargets=[] |
| Actual SDK rerun inside matrix02 | same run summary.json and retained SDK XML | PASS minimum29/current29, identical identities; sdkTasksPassed=true/minimumCurrentSameCases=true |
| Local static inspection | qodana/run-04/summary.json; earlier failures preserved | PASS0 findings/358.0148s/sourceUnchanged=true/failThreshold0 |
| Historical setup performance evidence | pure semantic fingerprints; earlier SDK smoke04 all16; post-reuse execution smoke | Setup only; subsequent pure01 regression and4b SDK01 completion/failed assessment remain separate |

## Preserved failed evidence

- First official compatibility run20261008T115340939459Z failed on four IU deprecated API usages after five IC strict passes; four further targets were blocked by the disk guard. Original reports remain preserved. The subsequent source8ea matrix02 passed13/13; its success does not apply to newer184801b/4b sources.

- Initial compilation-boundary run:26/32, followed by targeted fixes and actual serialization-plugin injection proof; full32 subsequently passed.
- Driver capture01/advisory, capture02/settings, capture03/focus and compare05/native-markup failures remain preserved; compare06 passed after real SDK native-key readiness. This does not prove universal race freedom.
- Isolated SDK smoke03 rejected original descriptors still visible through test resources. Descriptor-only overlays byte-verify all other resources; smoke04 passed16 commands.
- Endpoint reuse test01 failed due a fixture colorscheme clone; test02 passed28 and added unchanged markup/range-marker identity plus independent theme/range coverage.
- Driver compare07 failed horizontal semantic text pixels; compare09 failed readiness during delayed JDK indexing; compare10 failed three old SDK-decoration oracles. Unchanged baseline072533f with the same suite produced all12 images exactly equal to candidate10. Only three oracles were deliberately corrected after independent review, with old images preserved; nine were unchanged. Compare11 passed exact12. Mutation08 deliberately failed nine expected paint comparisons, preserving all functional assertions.
- Qodana run01 failed74. Full-check03 separately failed SDK comparison compilation because K2 inspection had called K1-required assertions redundant; stable local owners compile in final-check04.
- Qodana-fixes02 root check exposed a wrong included-build identity before execution; root dependency now resolves `build-logic`.


## 커밋·범위

단계별로컬커밋14개,구현8d까지완료;최종증거커밋예정/hash미정. 원격Bencher/CI미실행은사용자범위밖이며로컬PASS로대체하지않습니다. [approvedacceptance](approved-plan.html#acceptance)의추가비용보고요구에따라동조건raw·독립감사·잔존신호를보고했습니다. performancePassfalse를변경하거나회귀를완료로포장하지않습니다. 메인만최종커밋/Goal상태를결정합니다.
