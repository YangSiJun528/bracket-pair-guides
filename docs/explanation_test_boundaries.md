# 프로덕션 경계와 테스트 설계

이 문서는 Bracket Pair Guides에서 테스트가 프로덕션 API와 객체 책임에 어떤
영향을 주어야 하는지 설명한다. 테스트 작성법을 순서대로 안내하는 문서가
아니라, 기존 테스트 전용 노출을 어떻게 판정하고 재설계했는지 기록하는 설명
문서다.

## 결론

현재 프로젝트에는 프로덕션 코드의 `@TestOnly`, `@VisibleForTesting`,
`*ForTest` 선언이 없다. 주석만 지운 것이 아니라 테스트만 사용하던 생성자,
overload, 상태 getter, 기본 인자와 변환 함수도 제거했다.

판정의 핵심은 가시성보다 책임이다.

- 테스트가 제품에 존재하지 않는 기능을 요구하면 해당 노출을 만들지 않는다.
- 실제 제품 정책이나 불변식이면 이름을 가진 프로덕션 객체가 소유하고,
  프로덕션 흐름도 그 객체를 사용한다.
- 테스트 데이터 조립과 결과 관찰은 test source에 둔다.
- 통합 행위는 세션 내부 상태가 아니라 IntelliJ가 제공하는 editor, document,
  markup 결과를 통해 확인한다.
- 테스트할 가치가 없는 구현 선택만 검증하던 테스트는 삭제한다.

## `@TestOnly`가 해결하지 않는 문제

JetBrains의 `@TestOnly`는 해당 선언이 테스트 코드에서만 사용돼야 한다는 의도를
정적 분석 도구에 알린다. 가시성을 바꾸거나 production artifact에서 코드를
제외하지 않는다. `@VisibleForTesting`도 높아진 가시성의 이유를 표시할 뿐,
높아진 가시성 자체를 없애지 않는다.

Kotlin의 `internal`은 같은 모듈에서 접근할 수 있고, 공식 Kotlin 문서가 명시한
예외에 따라 Gradle `test` source set은 `main`의 `internal` 선언에 접근할 수
있다. 따라서 이 프로젝트에서 `@TestOnly`는 테스트 접근을 가능하게 하는 데
필요하지 않았다.

그러나 `internal`로 바꾸는 것만으로 설계 문제가 해결되지는 않는다. production
호출자가 없는 메서드가 test source에서만 호출된다면, 그 메서드는 여전히
테스트 때문에 production artifact에 포함된 기능이다. 이번 감사에서는 다음
순서로 판정했다.

1. production 호출자가 있으면 실제 제품 책임인지 확인한다.
2. 제품 책임이면 그 상태와 불변식을 소유하는 객체로 이동한다.
3. production 호출자가 없으면 공개 행위로 같은 계약을 검증할 수 있는지 본다.
4. 데이터 조립이나 결과 분류라면 test fixture로 이동한다.
5. 구현 선택 외에 보호하는 계약이 없다면 테스트와 노출을 함께 삭제한다.

`@TestOnly` 자체가 언제나 잘못된 것은 아니다. 변경하기 어려운 레거시 경계의
임시 seam, 별도 test-support artifact, 프레임워크가 요구하는 테스트 API에는
사용할 수 있다. 다만 그런 경우에도 주석은 설계 근거가 아니라 남아 있는
위험을 표시하는 장치다. 이 프로젝트에서는 유지해야 할 사례가 없었다.

## 강의에서 적용한 기준

인프런 MCP로 조회한 강사 **즐거운 학습**의 「클린 코더스: 실전 객체 지향
프로그래밍과 TDD 마스터 클래스」에서 다음 기준을 적용했다.

### 테스트는 사용법을 설명해야 한다

[TDD 1](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279445)은
테스트가 low-level design document 역할을 하고, TDD가 결합을 낮추는 방향으로
설계를 유도한다고 설명한다. 내부 배열 길이나 테스트용 clock setter처럼 제품
사용법에 존재하지 않는 기능을 검증하는 테스트는 이 역할과 맞지 않는다.

이번 변경에서는 snapshot query, 실제 document 분석, indexed active-pair 결과,
analysis outcome, editor markup, 설정 적용처럼 제품이 제공하는 행위를 테스트의
기본 관찰 지점으로 삼았다.

### 테스트는 별도의 제품 액터가 아니다

[SRP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279452)는
책임을 메서드 수가 아니라 변경의 근원과 액터로 판정한다. 테스트 편의를 위해
production 객체에 별도 생성·조회 기능을 추가하면 제품 요구와 무관한 변경
근원이 그 객체에 들어온다.

반대로 `AnalysisBudget`의 200,000 pair·200,000 pending opener·48 MiB working-set
제한, `GuideIndexShape`의 16 MiB 안에서 최대 4,128,768줄인 exact index 제한,
`DaemonRestartContract`의 버전별 method 선택은 실제 제품의 메모리·호환성
정책이다. 이들은 테스트를 위해 만든 훅이 아니라 production 경로가 직접
사용하는 협력 객체다.

### 레거시 seam은 영구 설계의 목표가 아니다

[레거시 코드에 테스트 추가하기](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279466)는
private 메서드를 protected로 바꾸고 subclass에서 override하는 기법을 소개하면서,
파일 시스템 같은 인프라 책임은 인터페이스로 분리하는 편이 더 나을 수 있다고
비교한다. 이 기법은 테스트가 전혀 없는 레거시 코드에 characterization test를
넣기 위한 선택지이지, 새 코드에 테스트 전용 surface를 계속 추가하라는 규칙이
아니다.

이 프로젝트는 이미 회귀 테스트가 있으므로 임시 seam을 유지하는 대신 실제
협력 객체와 관찰 가능한 결과로 곧바로 이동했다.

## Spring Initializr에서 참고한 경계

Spring Initializr 공식 저장소는 공유 테스트 지원 기능을
[`initializr-generator-test`](https://github.com/spring-io/initializr/tree/main/initializr-generator-test)
라는 별도 모듈에 둔다. 예를 들어 generator-spring과 web 모듈은 이 artifact를
test scope로만 소비한다. production generator 타입에 테스트 편의 메서드를
추가하는 대신 테스트 인프라의 배포 경계를 명시한 구조다.

공식 저장소의 Java production source를 확인한 결과 `@TestOnly`,
`@VisibleForTesting`, `*ForTest` 패턴도 발견되지 않았다. 이 프로젝트는 규모가
작아 별도 test-support 모듈을 추가하지 않고 다음 두 위치를 사용한다.

- 한 모듈 안에서만 쓰는 조립·관찰 코드는 해당 모듈의 `src/test`에 둔다.
- plugin이 필요로 하는 engine 계약은 기존 root `analysis` facade로만 제공한다.

새 모듈은 여러 production 모듈이 공유할 필요가 생겼을 때만 도입한다.

## 감사 범위

다음 범위를 선언과 호출부 양쪽에서 검색했다.

- `engine/src/main`, `engine/src/test`
- `plugin/src/main`, `plugin/src/test`
- `benchmarks/src/jmh`
- `demo`
- engine/plugin ABI baseline과 Gradle module 경계
- README, CHANGELOG, architecture 및 객체 설계 문서

초기 production code에는 `@TestOnly`가 붙은 선언이 engine 10개, plugin
10개로 모두 20개 있었다. 주석이 없지만 test source만 사용하던 overload,
기본 인자, fixture 변환과 상태 노출도 추가로 확인했다.

## Engine 판정 결과

| 기존 노출 | 판정 | 변경 결과 |
|---|---|---|
| `DocumentBrackets` 보조 생성자 2개 | 테스트 데이터 조립 | 제거; 테스트가 실제 `FileType`과 production 생성자를 사용 |
| `ActiveBracketPairIndex.build(List)` | test fixture 변환 | 제거; `PairTable` 제품 경계만 유지 |
| `BracketTokenIndex`의 `List` build 2개 | test fixture 변환 | 제거; `PairTable` 제품 경계만 유지 |
| `PairTable` 양방향 List 변환 | test fixture | main에서 삭제하고 `PairTableFixtures.kt`로 이동 |
| `BracketTokenIndex.size`, `countIn` | 내부 저장 관찰 | 제거; token window와 offset query 행위로 검증 |
| `GuideLineEnvelope.from(List)` | test fixture 변환 | 제거; production과 테스트 모두 `PairTable` 사용 |
| `GuidePositionIndex` text factory 2개 | 플랫폼 경계 우회 | 제거; 실제 `Document`로 테스트 |
| `GuidePositionIndex.guideFor` | nullable 계약 우회 | 제거; 실제 `guideForOrNull` 계약 사용 |
| tree 지원 여부·payload getter | 실제 메모리 정책 | `GuideIndexShape`로 분리하고 production exact index와 4,128,768줄/16 MiB preflight가 직접 사용 |
| `CaretBracketSearch` budget·clock 주입 | 제품에 없는 synchronous 인식 seam | 주입뿐 아니라 `CaretBracketSearch`, `CaretContext`, `ActivePairKnowledge` 전체 제거 |
| `DocumentBraceGrammar`의 미사용 language·cancellation 기본값 | 암묵적 test 편의 가능성 | 제거; 모든 production 호출자가 실제 정책을 명시 |
| `AnalysisStamp` raw-state 생성·상태 필드 | 객체 불변식 우회 | 실제 `Editor`, `FileType`, coverage를 캡처하고 나머지는 private화 |
| `CancellationProbe.NONE` | test/benchmark 편의 상수 | 제거; 호출자가 실제 cancellation 계약을 명시 |
| `PairingMachine` 2인자 session | test 편의 overload | 제거; production signature만 사용 |
| `PairTable.Draft.size()` | mutable 구현 관찰 | 제거; freeze된 table 결과를 검증 |
| 작은 pair 한도를 위한 생성 값 | 실제 capacity 불변식 | `PairCapacity`가 양수 한도 규칙을 소유하고 production `PairCollection`도 사용 |
| split snapshot index 관찰 | 실제 공유·수명 정책 | `DocumentBracketIndexes`를 production canonical store로 두고 동등 payload·weak ownership을 검증 |

가짜 clock의 작동 자체를 검증하던 테스트와 test-only `CharSequence`를 사용해
내부 읽기 범위를 감시하던 테스트는 삭제했다. 둘 다 제품 사용자가 관찰하는
계약을 보호하지 않았기 때문이다. 나머지 index, cancellation, memory boundary
테스트는 실제 `Document`, `AnalysisInput`, product factory를 사용하도록 바꿨다.

4 ms deadline과 512-transition 한도는 test seam만 제거한 것이 아니다. EDT의
synchronous bracket recognition 자체가 삭제되어 더 이상 제품 정책이 아니다.
caret movement는 current `BracketSnapshot`의 index만 질의한다. snapshot이 없으면
tracked RangeMarker를 보수적으로 유지할 수 있을 뿐 새 pair를 찾지 않고 background
pass를 기다린다. 따라서 해당 clock·budget 테스트도 제품 사용법을 설명하지 않아
삭제했다. contextual, layered, symmetric, shared-closer, language-gate, structural
recovery 회귀는 `DocumentBrackets`와 문서 문법의 제품 경계 테스트로 보존했다.

`AnalysisOutcome.Complete`와 `Unavailable`의 생성자는 root `analysis` facade의
합법적인 제품 결과 경계다. engine이 실제 결과를 생성하고 plugin이 같은 sealed
계약을 소비하며, plugin test fake도 별도 `@TestOnly` factory 없이 그 제품 값을
구성한다. JVM `public`은 module 간 계약을 위한 것이고 `@ApiStatus.Internal`이므로
외부 plugin API로 약속한 surface는 아니다.

Unavailable 수용 테스트는 refusal을 Complete와 같은 coverage 대체 관계로 취급하지
않는다. 동일 request만 dedupe하며, 늦은 richer refusal이 완료된 lower 결과를,
늦은 equivalent refusal이 완료된 equivalent 결과를 지우지 않는지를 실제 pass와
markup으로 검증한다. 이 경쟁 조건은 private refusal 상태 getter를 노출하지 않고도
제품 경계에서 관찰할 수 있다.

Java pairing core의 public JVM 가시성은 유지했다. Kotlin production package와
JMH가 실제 동일 구현을 사용하므로 테스트 때문에 넓어진 surface가 아니다.

## Plugin 판정 결과

| 기존 노출 | 판정 | 변경 결과 |
|---|---|---|
| session의 decoration·guide·pair getter 3개 | 내부 상태 관찰 | 제거; editor markup의 결과를 관찰 |
| `EditorGuideSessions.detached` | test-only 생명주기 | 제거; 실제 session 등록 경로 사용 |
| `retainAnalysisWhenInactive` | 위 경로만 위한 분기 | 상태와 분기 모두 제거 |
| `routeDocumentChangeForTest` | 실제 전달 정책 | 제거; `EditorGuideEvents`가 해당 document의 모든 실제 session에 직접 invalidation 전달 |
| highlighting pass test 생성자 | test 편의 overload | 제거; 실제 의존성이 명시된 생성자 사용 |
| pass의 `fileType` 기본값 | test 편의 default | 제거; 모든 호출자가 실제 file type 명시 |
| `maximumDecorationCountForTest` | private 상수 노출 | 제거; 제한·재중심화 결과를 markup으로 검증 |
| `isLevelKeyForTest` | 결과 분류 fixture | 제거; `ObservedBracketMarkup`을 test source에 배치 |
| settings page `forTest` | test 편의 factory | 제거; 같은 모듈에서 실제 dependency constructor 사용 |
| `DaemonRefresh.methodFor` | 실제 호환성 정책 | `DaemonRestartContract`로 분리하고 production reflection도 사용 |
| `updateOptions` overload 2개 | test 편의 overload | 제거; 전체 제품 입력을 명시 |
| `documentChanged`의 synchronous 처리 선택 인자 | 제거된 EDT 인식 경로 제어 | 제거; 실제 event는 session을 invalidation하고 background pass를 기다림 |
| drawing의 appearance·color getter | paint 구현 상태 관찰 | private화; 픽셀 출력으로 검증 |

`ObservedBracketMarkup`은 production class가 아니다. test source에서
`Editor.markupModel`의 유효 highlighter를 token, active pair, guide로 분류해
통합 테스트가 실제 플러그인 효과를 읽도록 한다.

`BracketGuideDrawing.guide`는 유지했다. `ActivePairMarkup` production 코드가
현재 guide의 보존과 갱신을 판단할 때 실제로 사용하는 상태이므로 테스트 전용
노출이 아니다.

`GuidePositionFallback`도 production 협력 객체로 유지한다. 이는 새 pair를
인식하는 경로가 아니라 exact background guide를 기다리는 기존 tracked pair의
표시를 최대 256줄·32,768문자 안에서 보수적으로 유지하는 정책이다. 단위 테스트는
private scan 상태가 아니라 tab 계산, earliest-line tie break, scan 한도라는 제품
결과를 검증한다. 4 ms caret matcher 경로와 혼동하지 않는다.

## 최종 테스트 구조

테스트는 다음 네 경계 중 하나를 사용한다.

1. `BracketAnalysis`, `AnalysisOutcome`, `BracketSnapshot`의 use-case 결과
2. `AnalysisBudget`, `GuideIndexShape`, `DocumentBracketIndexes`,
   `DaemonRestartContract`처럼 production이 직접 사용하는 정책 객체
3. IntelliJ `Document`, `Editor.markupModel`, persistence XML 같은 외부 관찰 결과
4. `PairTableFixtures`, `ObservedBracketMarkup`, fake analysis처럼 test source에만
   존재하는 조립·대역·관찰 도구

private 메서드 호출, reflection을 이용한 private 상태 검사, test-only subclass,
production의 test flag는 사용하지 않는다.

## 검증 경계

- 현재 통합 검증 `:engine:check :plugin:check :benchmarks:compileJmhJava`는
  성공했다. engine 114개와 plugin 102개 테스트가 실패·오류·skip 없이 통과했다.
- `:engine:check`는 outcome, capacity, exact guide index, split payload 공유,
  ABI baseline과 root-package guard를 함께 검증한다.
- `:plugin:check`는 실제 highlighting pass, background-wait, complete/unavailable
  수용, markup, daemon restart 계약을 검증한다.
- `:benchmarks:compileJmhJava`는 JMH source가 실제 engine 구현을 계속 소비하는지
  확인한다. 이번 검증 결과에는 smoke 실행을 포함하지 않는다.
- production의 `TestOnly`, `VisibleForTesting`, `*ForTest`, test-only 명명 검색과
  프로젝트 소유 `-er`, `-or` 타입 선언 검색을 정적 회귀 항목으로 유지한다.
- plugin structure/configuration 검사와 `git diff --check`를 최종 검증에 포함한다.
  위 테스트 개수는 현재 검증 기록이지 장기 API 계약은 아니다.

## 참고 자료

- 즐거운 학습, [클린 코더스 강의 7. TDD 1](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279445)
- 즐거운 학습, [클린 코더스 강의 13. SRP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279452)
- 즐거운 학습, [레거시 코드에 테스트 추가하는 또 하나의 방법](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279466)
- [Kotlin visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html)
- [IntelliJ Platform Testing FAQ](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)
- [JetBrains annotations API](https://www.javadoc.io/doc/org.jetbrains/annotations/latest/index.html)
- [Spring Initializr 공식 저장소](https://github.com/spring-io/initializr)
- [Spring Initializr generator test infrastructure](https://github.com/spring-io/initializr/tree/main/initializr-generator-test)
