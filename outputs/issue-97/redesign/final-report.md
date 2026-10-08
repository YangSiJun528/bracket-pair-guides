# 이슈 #97 재설계 구현·검증 보고서 — 초안

**초안입니다. 정식 성능 비교·분석과 최종 보고가 남아 있습니다.** 현재 HEAD는 `8ea07aa86cf939d9a5e945bec180079ecc28eb60`입니다. Listener/session 소유권 3파일 수정 후 실제 minimum/current SDK29, root check06, Qodana04가 통과했습니다. Driver12도 정확한 12개 ARGB·PNG 비교와 기능 assertions를 통과했습니다. 새 공식13IDE matrix02는 동일 release ZIP에서13/13 strict PASS이며, 별도 actual minimum/current SDK도 각각29개 재실행·동일 identities로 통과했습니다. 정식 성능은 미실행입니다. 각 결과는 해당 소스 manifest에만 적용합니다.

현재 release ZIP SHA256은 `609f09d3db8c14cf66aafc77b1f24bdfa9062a874d877f42013e406436f161ef`, visual ZIP SHA256은 `1bbb6cb4162255d973a5f3c34b99da54e4dc272173488db1060a4d8778b64b77`입니다.

## 구현 구조

다섯 프로덕션 모듈이 컴파일 의존성으로 책임을 나눕니다. UI는 작업의 완전한 희망 상태를 전달하고 읽기 전용 결과만 조회합니다. SDK 입력 수집, 계산 호출, 작업 취소 순서와 최종 결과 승인은 runtime이 맡습니다.

```mermaid
flowchart LR
    Plugin[plugin: 구성·등록·패키징] --> UI[editor-ui]
    Plugin --> Runtime[analysis-runtime]
    Plugin --> Model[analysis-model]
    Runtime -->|api| UI
    Runtime -->|api| Model
    Runtime -->|implementation| Core[analysis-core]
    UI -->|api| Model
    Core -->|api| Model
    Plugin -. 런타임 패키징 .-> Core
    UI --> SDK[IntelliJ SDK]
    Runtime --> SDK
    Plugin --> SDK
```

| 변경 책임 | 소유 모듈·주요 코드 | 호출자가 몰라도 되는 결정 |
|---|---|---|
| 플랫폼 없는 값·조회 계약 | model: `BracketView`, `TokenWindow`, `AnalysisResult` | 구체 인덱스와 저장 배열 |
| 괄호 대응·가이드·repair | core: `BracketCalculator`, `CalculationAttempt`, `CalculationCache` | builder, 정렬, 재시도와 약한 canonical 저장 |
| 표시·편집기 이벤트·설정 | UI: `EditorGuide`, `ActiveGuidePresentation`, `RenderFrames` | runtime jobs와 source stamp |
| SDK 수집·실행·유효성·승인 | runtime: `EditorSource`, `EditorAnalysisSession`, `DocumentCalculation` | full/repair/native 취소·교체 순서 |
| 모듈 연결·IDE 등록 | plugin: `GuidePlugin`, highlighting-pass adapters | core 계산 진입점 |

```mermaid
classDiagram
    GuideWorkFactory <|.. RuntimeGuideWorkFactory
    GuideWork <|.. EditorAnalysisSession
    GuideView <|.. EditorGuide
    EditorGuide --> GuideWork : reconcile complete GuideDemand
    EditorAnalysisSession --> GuideView : EDT apply then approve
    EditorAnalysisSession --> DocumentCalculation
    DocumentCalculation --> BracketCalculator
    EditorAnalysisSession --> EditorSource
    EditorSource ..|> BracketInput
    BracketCalculator --> BracketInput
    BracketCalculator --> CalculationControl
    BracketCalculator --> CalculationAttempt
    BracketCalculator --> CalculationCache
    EditorGuide --> BracketView : read-only queries
    IndexedBracketView ..|> BracketView
    EditorGuide --> RenderFrames : resource authority
```

`GuideWorkFactory`, `GuideWork`, `GuideDemand`, `GuideView`는 소비자인 UI의 `ui.work` 계약입니다. `BracketInput`, `LineInput`, `CalculationControl`은 소비자인 core의 입력 계약입니다. model/core에는 IntelliJ 객체와 source stamp가 없습니다. runtime은 UI의 구현 패키지를 참조하지 않습니다.

UI에서 core/runtime의 계산·capture·builder·구체 인덱스를 직접 참조할 수 없고, plugin에서도 core를 직접 컴파일 참조할 수 없습니다. 최종 플러그인에는 필요한 다섯 owner jar가 포함되어야 하므로, 컴파일 차단과 패키징은 별도 검증합니다.

## 보존한 실행·표시 계약

- 문서 변경 콜백에서 영향을 받는 가이드를 동기적으로 숨긴 뒤 하나의 typed demand와 repair intent를 제출합니다. repair는 secondary full 작업의 75ms 지연과 독립적입니다.
- 계산은 독립 worker에서 시작하고 SDK 입력만 bounded read로 수집합니다. core가 한 계산 attempt와 unrelated-write retry를 소유합니다. lexer·extension callback 하나의 실행 시간까지 선점하지는 못합니다.
- runtime이 source, demand, ticket, lifetime을 확인하고 EDT에 적용합니다. 적용 후 재검증과 최종 승인을 수행하며 SDK 호출을 lifecycle lock 안에서 실행하지 않습니다.
- `RenderFrames`는 재진입한 새 렌더링의 리소스를 옛 rollback이 지우지 않도록 ownership을 관리합니다. 동일 pair·style의 실제 endpoint와 tracking marker는 재사용합니다.
- hidden/no-facet/close는 accepted 결과와 UI view를 해제합니다. close는 publication 권한을 즉시 취소하고 실행 중 capture의 local 참조는 작업이 unwind한 뒤 해제됩니다.
- native proof의 유효성은 runtime, 알림 episode·억제·사용자 설정은 UI가 소유합니다. ADR 0001/0002의 표시 및 쓰기 응답성 결정을 유지합니다.

## 현재 확인된 검증

기존 구현 세부사항 중심 테스트를 새 핵심 계약 테스트로 교체했습니다. 아래 각 행은 해당 기록의 소스 manifest에 고정된 개별 결과입니다. 서로 다른 실행의 통과를 현재 소스의 검증 범위를 넘어 일괄 통과로 합산하지 않습니다.

| 검증 | 현재 관측 결과 | 근거 |
|---|---|---|
| 실제 Kotlin/Java 컴파일 경계 | 32개 PASS; 실제 classpath·전이 의존성·출력 우회 및 긍정 대조군 포함 | [check06 집계](final-check-06/summary.json) |
| 패키징·BMF 보고 정책 | 각각9개 PASS | [check06 집계](final-check-06/summary.json) |
| 소스 단위 계약 | 55개 PASS: model4/core41/UI3/runtime3/plugin4 | [check06 집계](final-check-06/summary.json) |
| SDK 없는 model/core 독립 build | fresh45 PASS; check06 선택 소스·빌드 파일과 정확 일치 | [fresh 로그](final-check-04/pure.log), [동일성](final-check-06/pure-source-parity.json) |
| 실제 minimum/current SDK | 각각29 PASS·동일 testcase identities, 실패/오류/skip0 | [실행 기록](listener-lifetime-01/) |
| root check06 | PASS134,583.4669초, 실패/오류/skip0, sourceUnchanged=true; 변경 없는 task 재사용 포함 | [집계](final-check-06/summary.json) |
| 최종 패키징 |451 classes, duplicate owner0/test leak0 | [실행 기록](listener-lifetime-01/) |
| Qodana04 | PASS0 findings,358.0148초,sourceUnchanged=true,failThreshold0 | [집계](qodana/run-04/summary.json) |
| Driver compare12 | 기능 assertions 및12개 정확 ARGB·PNG바이트 일치 PASS,239.8648초,sourceUnchanged=true | [검증](driver/compare-12/verification.json), [실행](driver/compare-12/command.json) |
| Driver production 동일성 | 모든 production class/resource 바이트 동일; 전체 host/Linux ZIP은 다름. plugin JAR MANIFEST의 Build-JVM/Build-OS만 차이 | [동일성](driver/compare-12/production-equivalence.json) |
| paint-removal mutation08 (이전 소스) | 의도적 exit1·JUnit 실패1:9개 pixel 비교가 제거 검출, no-guide3개 정확 유지. 전체 테스트 통과가 아님 | [음성 대조군](driver/mutation-08/verification.json) |
| 수정 후 공식13IDE 전체 matrix02 |13/13 strict PASS, pendingTargets=[], 동일 source8ea07aa/release609f09d3...; outer exit0·2690.359957초 | [집계](compatibility/runs/20261008T125349781364Z/summary.json), [실행](compatibility/serial-command-02.json) |
| matrix02 실제 SDK 재실행 | minimum/current 각각29 PASS·동일 identities, sdkTasksPassed=true | [집계](compatibility/runs/20261008T125349781364Z/summary.json) |
| 정식 변경 전후 성능 비교 | NOT RUN; smoke는 성능 통과가 아님 | [계획](performance-plan.json) |

첫 공식 matrix01은 이전 be4f620/ZIP b1442ca6...에서 exit1·1490.48초로 실패했습니다. IC5 strict PASS, IU4는 같은 one-argument listener deprecated 호출로 실패했고 나머지4는 disk guard로 미실행입니다. [원본 보고서](compatibility/runs/20261008T115340939459Z/)를 보존합니다. 이후 [지원 listener/session 소유권 3파일 수정](compatibility/document-listener-fix/session-ownership-review.md)을 적용하고 위 SDK·check06·Qodana04·Driver12를 실행했습니다. [Task-owned cache 정리](compatibility/owned-cache-retirement-01/)는 생성한 IDE3/installers3만 삭제해23.34GiB를 회수했고, pre-existing 항목과 원본 reports·제품 정보·해시는 보존했습니다. 새 matrix02는 이전 타깃 통과를 재사용하지 않고 동일 최종 ZIP의13개 strict 검증과 actual minimum/current SDK29개씩을 실행해 통과했습니다.

이전 check03의 SDK harness 컴파일 실패와 후속 [check04 컴파일](final-check-04/harness-compile.log), Driver07/09/10 실패, Qodana 실패는 원본 증거로 남아 있습니다. Driver oracle 정정 근거와 음성 대조군 범위는 아래 한계에 구분합니다.

컴파일 경계는 실제 Kotlin/Java compiler classpath, 전이 의존성, 공유 출력과 부정·긍정 compile probe를 확인합니다. 패키징은 owner 클래스·리소스·런타임 의존성의 별도 계약입니다. SDK 없는 빌드는 독립 `tools/pure-build`를 사용합니다. 기존 official Gradle Plugin Verifier, SDK runner, IntelliJ Driver, JMH/BMF 도구를 유지하며, 검증 실패와 원본 로그를 보존합니다.

SDK 29개 계약은 실제 worker/read/EDT publication, 중복 demand, source 변경·in-flight 취소, secondary repair 독립 실행, 실패·재진입 markup, XML 상태 roundtrip, hidden/disabled/close payload 해제, 동일 markup/marker 재사용, 같은 문서의 두 실제 편집기의 독립 publication과 close 수명을 포함합니다. 실제 IDE/JBR identity도 별도 확인합니다. headless 활동 입력은 명시적으로 주입하며 실제 Swing focus·visibility·Settings Apply와 paint는 Driver의 다른 증거입니다.

## 남은 한계와 최종 확인 항목

컴파일 차단은 잘못된 계층 참조를 막는 증거이며 모든 알고리즘·스레드 버그의 부재를 뜻하지 않습니다. `afterAdded`의 지원되는 재진입은 검증하지만, SDK가 금지한 `beforeRemoved` 중 nested marker 추가나 임의 listener 예외의 완전한 rollback까지 보장하지 않습니다.

unrelated-write retry는 core 입력 adapter에서 `RetryCapture`를 발생시키는 계약으로 검증합니다. 실제 SDK의 unrelated host write를 입력 chunk 사이에 배치해 전체 attempt 재시작을 결정적으로 확인하는 통합 계약은 아직 없습니다. 이를 SDK retry 통과로 취급하지 않습니다.

native A→B→A는 proof gate 단위 계약과 Driver의 실제 native settings/markup·pixels 관측으로 분리됩니다. 지연된 실제 native SDK traversal의 stale completion을 결정적으로 거절하는 통합 계약은 아직 없습니다. XML roundtrip은 실제 직렬화·재적재를 검증하며 별도 IDE 프로세스 재시작 시험을 대신하지 않습니다.

Driver compare07의 차이는 괄호·가이드 밖 `Contract`·`run` 식별자 영역이었습니다. compare09는 daemon 관측을 추가한 뒤, 초기 분석과 겹쳐 진행된 JDK 자동발견·root indexing으로 capture readiness가 실패했습니다. 표준 초기 프로젝트 준비 대기를 추가한 compare10에서는 horizontal-only가 기존 oracle과 정확 일치했습니다.

compare10의 나머지 세 oracle 차이는 독립 unchanged-production counterfactual에서도 동일하게 재현됐습니다. 두 native scenario에서는 이전 caret의 identifier-usage 배경이 사라졌고, 편집 후에는 SDK native indent guide가 갱신됐습니다. 모든 12개 baseline/candidate 영상이 정확 일치함을 확인하고 메인이 이미지를 독립 검토한 뒤 3개만 baseline production의 settled capture로 정정했습니다. 이전 이미지·실패 원본·검토 결정을 보존하며 정확 픽셀 기준은 변하지 않았습니다. counterfactual은 기존 oracle 통과가 아닙니다. 이후 compare11은 정정된 12개 oracle과 정확 일치했고 기능 assertions를 통과했습니다. Mutation08은 가이드 그리기를 제거한 격리 복사본에서 9개 시각 비교 실패를 의도대로 검출했습니다. 이는 mutation 테스트 전체 통과가 아닙니다.

정식 성능 비교는 아직 없습니다. 초기 SDK smoke에서 자동 startup에 의한 소유하지 않은 계산 가능성을 발견해 측정 전용 descriptor에서 양측의 자동 startup/pass만 제외하고 서비스는 유지하도록 격리했습니다. 모든 workload의 전후 attachment 검사와 실제 owned job/read·markup 관측이 필요합니다. 이 범위는 실제 plugin registration/lifecycle의 대체 증거가 아니며, 그것은 SDK·Driver·패키징·IDE 호환성 검증으로 따로 확인합니다. 이전 구현의 성능 결과도 재구현의 통과 증거로 승계하지 않습니다.

이전 root check05, Qodana03, Driver11과 음성 paint mutation08의 관측 결과는 해당 소스 증거로 보존합니다. 첫 공식 matrix의 deprecated listener 실패에 따라 지원되는 parent-disposable 등록과 session acquire/release 소유권을 적용했습니다. 수정 후 actual min/current SDK29, root check06, Qodana04와 패키징이 통과했습니다. Driver12는 동일 production class/resource의 Linux 재빌드에서 정확12개 이미지와 기능 assertions를 통과했습니다. 전체 ZIP의 동일성은 주장하지 않으며, compatibility에는 위 exact host release ZIP을 사용합니다. 새 전체 공식13IDE matrix02는 run20261008T125349781364Z에서 동일 release ZIP으로13/13 strict PASS를 기록했으며, actual minimum/current SDK도 각각29개 재실행·동일 identities로 통과했습니다. 남은 전체 검증은 동조건 정식 성능·할당·retention 비교와 그 분석 및 최종 보고입니다. 원격 push, PR 생성, 병합 및 외부 업로드는 수행하지 않습니다.
