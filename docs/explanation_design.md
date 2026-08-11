# 현재 설계와 재구성 보고서

이 문서는 2026-08-11 재구성 이후의 이름, 책임, 모듈, 테스트 경계를
설명한다. 구현 방법이나 명령 모음이 아니라 설계 판단의 근거를 기록하는
설명 문서다. 실행 절차는 [기여 가이드](../CONTRIBUTING.md), 런타임 흐름은
[아키텍처](explanation_architecture.md)를 기준으로 한다.

## 결론

프로덕션은 `plugin` Gradle 모듈 하나로 통합했다. 분석과 UI를 구분할 실제
배포 단위나 독립 제품 소비자가 없는데도 `engine`을 별도 artifact로 유지하면
공개 facade, ABI, 조립 설정, 서비스 interface가 생긴다. 이 비용은 현재
요구사항에서 회수되지 않는다.

물리 모듈을 합쳤다고 책임까지 합치지는 않았다. 분석, snapshot, 편집기 수명,
presentation, 설정은 패키지와 객체로 분리하며, ArchUnit이 패키지 의존 방향과
cycle을 검사한다. `benchmarks`는 성능 측정만 수행하는 별도 모듈이고 제품
계약을 정의하지 않는다.

## 검토 기준

즐거운 학습 강사의 Inflearn 강의 *클린 코더스: 실전 객체 지향 프로그래밍과
TDD 마스터 클래스*를 다음 기준으로 적용했다.

- [OOP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279438):
  데이터와 행위를 함께 두고, 객체가 무엇을 캡슐화하는지 이름으로 드러낸다.
- [TDD 1](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279445):
  실패하는 행위 테스트, 최소 구현, 즉시 리팩터링의 순환을 유지한다.
- [Architecture](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279449)와
  [Architecture UseCase](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279450):
  use case를 중심에 두고 도구와 전달 상세에 끌려가지 않게 한다.
- [SRP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279452):
  메서드 수가 아니라 변경을 요구하는 액터와 변경의 근원으로 책임을 나눈다.
- [DIP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279456):
  의존 방향을 정책 쪽으로 두되, 실제 변형이나 소비자가 없는 framework를
  미리 만들지 않는다.

강의 내용은 판단 기준으로 사용했으며, 이 구현이 강의의 공식 예제이거나
검증을 받았다는 의미는 아니다.

## 재구성 결과

| 이전 구조 | 현재 구조 | 판단 |
|---|---|---|
| `engine`과 `plugin` 프로덕션 모듈 | `plugin` 하나 | 같은 IntelliJ classpath와 같은 배포 JAR을 위한 인위적 공개 경계 제거 |
| `buildSrc`의 Kotlin 소스 parser와 사용자 정의 Gradle task | JUnit의 ArchUnit 1.5.0 테스트 | 컴파일된 Kotlin·Java bytecode의 실제 의존성을 검증하고 자체 도구 유지비 제거 |
| `BracketAnalysis` interface와 `IntellijBracketAnalysis` 구현 | final light service `analysis.intellij.BracketAnalysis` | 구현과 대체 소비자가 하나뿐인 interface 제거 |
| `BraceLanguageInventory` service와 구현 | `BraceLanguageCatalog.installedFamilies()` projection | 같은 플랫폼 registry의 별도 service lifecycle 제거 |
| XML의 분석 service descriptor | `@Service(Service.Level.APP)` light service | override나 외부 plugin 소비가 없는 final service에 플랫폼 기본 방식 사용 |
| 모듈 경계를 위한 public root `analysis` facade | 책임 패키지의 `internal` Kotlin 타입 | 제품 facade의 공개 JVM surface 제거; Java pairing core만 실제 package/JMH 소비를 위해 JVM-public으로 유지 |
| `BracketSnapshot`·`TokenWindow` interface와 단일 indexed 구현 | `analysis.snapshot`의 concrete internal 객체 | 결과 query와 상태를 소유한 객체를 직접 표현 |
| snapshot 계층의 중복 recognition bridge | `DocumentBracketRecognition` 한 종류 | 인식 단계의 완료/거부 상태만 남기고 전달용 복제 제거 |
| production 알고리즘을 다시 구현한 fake snapshot/window 계층 | `BracketSnapshotFixture`가 실제 snapshot 조립 사용 | 테스트와 제품 알고리즘의 이중 진실 공급원 제거 |

Gradle 모듈 수를 줄인 이유는 “모놀리스가 항상 낫다”가 아니다. 현재 분석
코드는 IntelliJ의 editor, highlighter, matcher registry를 실제 입력으로 쓰고,
별도 배포나 재사용 요구가 없다. 독립 CLI, 서버, 다른 host adapter처럼 두 번째
제품 소비자가 생기면 그때 의존성과 API를 근거로 물리 모듈을 다시 판단한다.

## 이름과 책임

클래스 이름은 수행 동작이 아니라 객체가 보유한 개념을 기준으로 정했다.

| 이름 | 캡슐화하는 것 | 변경 근원 |
|---|---|---|
| `BracketAnalysis` | IntelliJ 입력에서 하나의 권위 있는 분석 outcome을 만드는 조립 | 분석 use case와 host 조립 |
| `BraceLanguageCatalog` | 설치된 matcher 정의와 언어 family projection | IntelliJ language registry 의미 |
| `BracketGuidePassRegistration` | highlighting pass 등록과 application service 연결 | IntelliJ highlighting extension 조립 |
| `DocumentBrackets` | 한 editor token stream의 bracket recognition | matcher adaptation과 token 의미 |
| `PairingMachine` | group별 opener 상태와 pair 생성 규칙 | 중첩·복구 정책 |
| `VisualColumn` | tab을 포함한 indentation의 visual column 계산 | guide column 산술 |
| `SnapshotAssembly` | coverage별 index 조립 순서와 결과 정책 | snapshot 구성 및 용량 결과 |
| `BracketSnapshot` | 한 stamp의 immutable query view와 active-pair memo | 결과 조회 계약 |
| `EditorAnalysisState` | 한 editor가 받아들인 snapshot·완료·거부의 원자적 상태 | 결과 수락과 retry 정책 |
| `EditorGuideSession` | 한 editor의 분석·viewport·presentation 수명 | editor lifecycle |
| `ActiveGuidePresentation` | 추적 pair, range marker, guide와 강조 markup 및 문서 revision별 즉시 geometry | active-pair 표시 수명 |
| `VisibleTokenDecorations` | viewport 범위의 bounded token markup | viewport 표시 정책 |
| `BracketGuidePreferences` | 사용자가 선택한 immutable 제품 옵션 | 제품 설정 의미 |
| `BracketGuideSettings` | IntelliJ persistent state | 저장 형식과 migration |

프로젝트 소유 프로덕션 타입에는 작업자 역할만 표현하는 `-er`, `-or` 이름을
두지 않았다. 다만 suffix 자체를 금지 규칙으로 삼지는 않는다. 도메인에서
실제로 그 명사가 가장 정확하거나 IntelliJ가 요구하는 외부 interface 이름은
의미를 우선한다. 이름 검사는 자동 문자열 규칙보다 책임 검토로 수행한다.

## 남겨 둔 추상화

YAGNI는 모든 interface와 callback을 제거한다는 뜻이 아니다. 다음 경계는 실제
정책 차이나 host 격리를 표현하므로 남겼다.

- `AnalysisOutcome`은 `Complete`, `Limited`, `Unavailable`의 서로 다른 제품
  상태와 invariant를 닫힌 계층으로 표현한다.
- `DocumentBracketRecognition`은 pair table과 구조적 용량 거부를 구분하는
  recognition 단계의 실제 중간 결과다.
- `PairingRules`, `CancellationProbe`, `PairSink`는 IntelliJ 밖의 primitive
  pairing core가 matcher 의미, 취소, 결과 저장을 전달받는 좁은 경계다.
- `BracketGuideHighlightingPass`의 분석 함수와 snapshot 조립의 함수 인자는
  thread/lifecycle 소유자가 작업을 전달하는 값이다. 별도 공개 서비스 계층이나
  범용 framework가 아니다.

반대로 “언젠가 다른 구현이 생길 수 있다”는 이유만으로 interface, factory,
provider, bridge, 별도 module을 두지 않는다. 두 번째 실제 소비자나 변형이
나오면 그 차이를 관찰한 뒤 추상화한다.

## 테스트 경계

테스트는 제품 API의 가시성을 결정하지 않는다.

- 테스트 때문에 공개한 제품 타입은 없다. Kotlin 제품 타입은 `private` 또는
  `internal`이고, Java pairing core의 JVM-public 가시성은 실제 sibling package와
  JMH 소비 때문에 유지한다.
- 같은 Gradle 모듈의 test source set은 Kotlin friend path를 통해 `internal`
  동작을 검증할 수 있으므로 public 전환이나 `@TestOnly`가 필요하지 않다.
- `BracketSnapshotFixture`는 `SnapshotAssembly`와 실제 index를 사용한다.
  active-pair 선택과 token-window 선택을 fake가 복제하지 않는다.
- IntelliJ entry 객체는 internal constructor의 함수나 값으로 협력을 받아
  lifecycle 행위를 기록한다. 테스트를 위한 별도 제품 service는 만들지 않는다.
- 프로덕션 source에는 테스트 전용 getter, convenience overload,
  `@TestOnly`, `VisibleForTesting` surface를 두지 않는다.

테스트 대상 선택 기준은 다음과 같다.

1. 사용자 또는 다음 책임 객체가 관찰하는 결과와 invariant를 테스트한다.
2. 용량 거부, cancellation, stale result, malformed input처럼 실패 비용이 큰
   정책은 단위 경계에서 직접 테스트한다.
3. private 계산의 각 분기보다 public/internal 행위 결과를 테스트한다.
4. 테스트가 내부 상태 노출을 요구하면 먼저 책임이 너무 결합됐는지, 이미 더
   높은 수준에서 같은 행위를 검증하는지 확인한다.
5. refactoring 뒤에도 가치가 남지 않는 구현 모양 테스트는 삭제한다.

ArchUnit은 behavior test의 대체물이 아니다. 아래 다섯 구조 속성만 맡는다.

- production package dependency가 네 개의 큰 구역 사이에서 안쪽을 향하는가;
- package slice에 cycle이 없는가;
- neutral policy package가 IntelliJ에 의존하지 않는가;
- editor event adapter가 analysis 타입을 직접 알지 않는가;
- editor event adapter가 analysis 타입을 반환하는 method를 호출하지 않는가.

정확한 edge 목록은 문서에 복사하지 않는다.
[`ArchitectureTest.kt`](../plugin/src/test/kotlin/com/sijunyang/bracketpairguides/architecture/ArchitectureTest.kt)가
유일한 실행 규칙이다.

## Spring Initializr에서 참고한 범위

Spring Initializr를 구조 템플릿으로 복사하지 않았다. 규모와 소비자 수가 다른
프로젝트이므로 다음 세 가지 관찰만 적용했다.

첫째,
[`ProjectDescription`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectDescription.java)과
[`MutableProjectDescription`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/project/MutableProjectDescription.java)은
동작 주체 이름보다 프로젝트 설명이라는 도메인 개념을 전면에 둔다. 이
저장소도 `BracketSnapshot`, `AnalysisOutcome`, `BracketGuidePreferences`처럼
보유 상태와 invariant를 이름으로 드러낸다.

둘째,
[`Build`](https://docs.spring.io/initializr/docs/current/api/io/spring/initializr/generator/buildsystem/Build.html)는
dependencies, BOM, repositories, properties와 settings를 한 build aggregate로
묶는다. 이 저장소의 `EditorAnalysisState`, `ActiveGuidePresentation`,
`BracketSnapshot`도 함께 변하고 함께 유효해야 하는 상태를 한 객체가 소유한다.

셋째, Spring Initializr의 물리 module은 실제 재사용 근거가 있다. 공식
[README](https://github.com/spring-io/initializr)는 generator core, 교체 가능한
Spring convention, metadata, web endpoint, test infrastructure를 구분하고 CLI,
IDE, web UI 같은 서로 다른 소비자를 명시한다. Bracket Pair Guides에는 현재
그런 독립 제품 소비자가 없으므로 module 외형만 모방하지 않았다. JMH harness는
측정 소비자일 뿐 지원 API를 요구하는 제품 소비자가 아니다.

## 자동화된 설계 보호의 한계

ArchUnit은 bytecode 의존성을 확인하지만 이름의 정확성, 한 클래스 안의 혼합된
변경 근원, thread ownership, 테스트 가치까지 판단하지 못한다. Kotlin
`internal`도 같은 module 안에서 package 접근을 막지 않는다. 따라서 다음
보호 장치를 함께 사용한다.

- 행위 테스트: outcome, recognition, index, editor lifecycle과 presentation
- source visibility와 code review: 실제 소비자가 없는 Kotlin 구현은
  `private` 또는 `internal` 유지
- Qodana: IntelliJ와 JVM 정적 분석
- code review: 이름, 책임, YAGNI, thread ownership
- ArchUnit: package 방향, cycle, neutral policy의 platform 의존성

아키텍처 테스트를 통과시키기 위해 허용 edge를 늘리는 것은 해결이 아니다.
새 의존성이 어떤 use case와 변경 근원을 나타내는지 설명할 수 있을 때만 규칙을
수정한다.

## 검증 기준

현재 설계의 기본 검증 명령은 다음과 같다.

```shell
./gradlew :plugin:check :benchmarks:jmhJar
```

`plugin:check`는 behavior test와 ArchUnit 검증을 포함한다. Plugin descriptor,
IDE 호환성, Qodana는 각각 [기여 가이드](../CONTRIBUTING.md)에 기록된 별도
경계다. 이 문서는 시점에 따라 달라지는 테스트 개수를 설계 근거로 고정하지
않는다.

## 후속 변경 판단

다음 조건이 생기면 현재 결론을 다시 검토한다.

- IntelliJ 없이 실행되는 실제 분석 제품이 생긴다.
- plugin 이외의 배포 artifact가 안정된 분석 API를 소비한다.
- 같은 책임에 두 개 이상의 구현이 생기고 호출자가 이를 교체해야 한다.
- package 규칙만으로 막을 수 없는 독립 release cadence나 dependency classpath가
  필요하다.

그 전까지는 하나의 프로덕션 모듈, internal concrete 객체, package DAG를
기본값으로 유지한다.
