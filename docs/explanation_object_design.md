# Bracket Pair Guides 객체 설계 재구성

이 문서는 Bracket Pair Guides 전체 코드베이스의 이름과 책임을 객체 중심으로 다시 설계한 이유를 설명한다. 구현 절차를 안내하는 문서가 아니라, 어떤 기준으로 경계를 판단했고 각 변경이 어떤 구조적 문제를 해소하는지를 기록하는 설명 문서다.

## 결론

이번 재구성의 핵심은 `-er`, `-or` 접미사를 기계적으로 제거하는 것이 아니다. 객체가 보유한 상태, 지키는 불변식, 변경을 요청하는 주체를 먼저 확인한 뒤 그 객체가 **무엇인지**를 이름으로 드러내는 것이다. 그 결과 다음 방향을 확정했다.

- `engine`의 공개 경계는 동사형 요청과 포괄적인 engine/result 이름 대신 분석 입력, 분석 범위, 분석 시점, snapshot이라는 도메인 개념으로 표현한다.
- 전체 인식, caret 주변 제한 검색, 언어별 괄호 규칙, index 조립은 서로 다른 변경 근원으로 분리한다.
- snapshot builder와 pipeline처럼 같은 생성 책임을 나눠 가진 요소는 하나의 조립 경계로 합친다.
- `plugin`의 설정 변경, daemon 갱신, editor event, session 분석 상태, markup 상태는 서로 다른 변경 근원에 따라 분리한다.
- visible token decoration은 이를 조작하는 별도 manager 대신 자신의 highlighter 생명주기를 소유하는 aggregate가 된다.
- IntelliJ, JMH, 컴파일러, upstream fixture의 고정 이름은 프로젝트 명명 규칙의 위반으로 계산하지 않는다.

## 적용 범위와 판정 기준

감사 범위는 다음을 포함한다.

- `engine/src/main`, `plugin/src/main`의 프로덕션 타입과 함수 경계
- `engine/src/test`, `plugin/src/test`의 테스트 대상 이름, test fixture, fake
- `benchmarks`의 JMH 진입점과 내부 구현 참조
- `demo/BracketGuideDemo.java`의 사람이 읽는 예제 입력
- `engine/api/engine.api`, `plugin/api/plugin.api`의 ABI 경계
- `plugin.xml`, README, CHANGELOG, architecture 및 benchmark 문서

각 이름과 책임은 다음 질문으로 판정했다.

1. 이 객체는 어떤 상태나 규칙을 캡슐화하는가?
2. 이 객체가 항상 지켜야 하는 불변식은 무엇인가?
3. 누가, 또는 무엇이 이 객체의 변경을 요구하는가?
4. 이름이 객체의 정체를 말하는가, 아니면 단순히 수행 동작만 말하는가?
5. 동일한 변경 근원을 가진 행위가 흩어져 있거나, 서로 다른 변경 근원이 한 타입에 함께 있는가?
6. 상위 정책이 IntelliJ API, reflection, markup 같은 하위 상세에 직접 의존하는가?
7. 기존 테스트가 외부 행위를 고정하는가, 내부 구현 형태를 고정하는가?

`-er`, `-or` 검색은 네 번째 질문을 시작하기 위한 휴리스틱으로만 사용했다. `Color`처럼 실제 값인 명사, IntelliJ의 `Editor`처럼 외부 타입인 이름, JMH의 `Benchmark` 관례까지 일괄 변경하지 않는다. 반대로 접미사가 없어도 `AnalyzeRequest`, `BracketEngine`처럼 동사형이거나 지나치게 포괄적인 이름은 재검토했다.

## 강의에서 적용한 원칙

원칙은 인프런 MCP로 조회한 강사 **즐거운 학습**의 「클린 코더스: 실전 객체 지향 프로그래밍과 TDD 마스터 클래스」 실제 단원 내용을 기준으로 추출했다.

### 객체지향과 이름

[소개 및 OOP 단원](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279438)은 데이터와 행위를 함께 캡슐화하고, 내부 구현을 감추며, 클래스에는 명사형 개념을 두고 메서드에는 동작을 두는 방향을 설명한다. 또한 구체 구현보다 인터페이스에 의존하고, 상속보다 낮은 결합도의 협력을 선호한다.

이 원칙을 다음과 같이 적용했다.

- `VisibleTokenDecorationManager`를 별도 행위자 객체로 유지하지 않고 `VisibleTokenDecorations`가 자신의 highlighter 집합과 교체·갱신·폐기 규칙을 소유하게 한다.
- `PairTable.Builder`는 만드는 사람이라는 역할보다 완성 전 상태라는 정체가 중요하므로 `PairTable.Draft`로 바꾼다.
- 명령과 조회의 기대가 섞이지 않도록 입력, snapshot, 상태 전이를 분리된 값과 객체로 표현한다.

### SRP: 메서드 수가 아니라 변경의 근원

[SRP 단원](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279452)은 책임을 메서드 개수가 아니라 변경을 요구하는 액터와 변경의 근원으로 판단한다. 서로 다른 변경 근원이 한곳에 있으면 충돌, fan-out, colocation 문제가 생긴다. 테스트와 리팩터링을 통해 설계를 점진적으로 드러내는 emergent design도 함께 강조한다.

이 기준으로 다음 결정을 내렸다.

- 설정 값의 차이를 해석하고 editor session에 전파하는 변경 근원과, IntelliJ 버전별 daemon restart reflection을 다루는 변경 근원을 분리한다.
- editor session 안에서도 분석 stamp/snapshot의 생명주기와 active pair markup의 생명주기를 분리한다.
- 언어 목록 discovery, 문서 전체 괄호 인식, caret 주변 제한 검색을 별도 개념으로 둔다.
- snapshot 생성의 한 변경 근원을 `AnalysisSnapshotBuilder`와 `AnalysisPipeline` 두 타입에 나누지 않는다.

### TDD와 안전한 구조 변경

[TDD 1 단원](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279445)은 실패하는 테스트, 이를 통과하는 최소 구현, 즉시 수행하는 리팩터링의 반복을 핵심으로 둔다. 테스트는 회귀 방지 장치이자 낮은 수준의 설계 문서이며, 테스트 가능성은 결합도를 낮추는 압력으로 작동한다.

이번 작업에서는 기존 테스트를 characterization suite로 사용했다. green
상태에서 이름과 책임을 작은 단위로 이동했고, 강화한 불변식에는 전용 테스트를
추가했다. 구현 상세를 확인하는 테스트보다 외부에서 관찰 가능한 snapshot,
markup, cancellation, stamp 행위를 우선했다.

후속 감사에서는 production의 `@TestOnly` 20개와 주석 없이 test source만
사용하던 생성자, overload, getter도 함께 제거했다. 강사 즐거운 학습의
[레거시 코드 테스트 단원](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279466)이
소개하는 임시 seam보다 실제 협력 객체와 제품 결과를 우선했다. 항목별 판정은
[프로덕션 경계와 테스트 설계](explanation_test_boundaries.md)에 기록했다.

### 아키텍처는 도구가 아니라 사용법을 드러내야 한다

[Architecture 단원](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279449)은 아키텍처가 프레임워크나 기술 목록이 아니라 시스템이 무엇을 하는지, 즉 use case를 드러내야 한다고 설명한다. UI, DB, framework 같은 상세 결정은 늦출 수 있어야 하고 핵심 규칙은 테스트 가능한 형태로 상세와 분리되어야 한다.

따라서 package와 공개 경계는 IntelliJ 구현 수단보다 bracket analysis라는 사용법을 먼저 드러내게 한다.

- `engine`은 bracket 분석, pair knowledge, snapshot과 index 규칙을 소유한다.
- `plugin`은 IntelliJ highlighting pass, editor event, settings, markup이라는 전달·표현 상세를 소유한다.
- `benchmarks`는 배포 코드가 아니라 선택한 성능 구현을 검증하는 별도 관찰 경계다.
- IntelliJ 서비스 등록과 extension point는 가장 바깥 adapter에 남긴다.

### DIP와 의존성 방향

[DIP 단원](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279456)은 상위 정책이 하위 상세에 의존하지 않고, 경계를 가로지르는 소스 의존성이 안정적인 정책을 향해야 한다고 설명한다. 객체지향 설계의 핵심을 의존성 관리로 본다.

이 저장소에서는 Gradle 의존성 `plugin -> engine`을 유지한다. engine은
plugin의 session, settings, markup 타입을 알지 않는다. plugin은 root
`analysis` facade의 공개 타입만 소비한다. platform-neutral pairing state는
IntelliJ token 분류 adapter와 분리된 상태를 유지한다. 테스트 대역은 상속으로
concrete service를 흉내 내지 않고, 필요한 함수 또는 명시적 결과 경계를 주입한다.

## Spring Initializr에서 참고한 점

[Spring Initializr 공식 저장소](https://github.com/spring-io/initializr)는 core generator, Spring 전용 convention, test infrastructure, metadata, web delivery를 별도 모듈로 구분한다. 특히 다음 세 개념을 참고했다.

- [`ProjectDescription`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectDescription.java)은 생성할 프로젝트의 입력 상태를 하나의 도메인 계약으로 표현한다. `AnalysisInput`, `CaretContext`, `AnalysisCoverage`도 같은 이유로 호출 인자 묶음이 아니라 의미 있는 입력 개념이 된다.
- [`ProjectGenerationContext`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectGenerationContext.java)는 프로젝트 생성에 필요한 구성과 기반을 담는 context라는 정체를 이름으로 드러낸다. `CaretContext`도 검색을 수행하는 행위자 이름 대신 검색을 규정하는 현재 editor 상태를 표현한다.
- [`Build`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/buildsystem/Build.java)는 properties, dependencies, BOM, repositories를 소유하는 aggregate다. `VisibleTokenDecorations`, `BracketSnapshot`, `PairTable.Draft`도 관련 상태와 불변식을 소유하는 방향으로 설계한다.

모듈 경계에서도 `initializr-generator`의 핵심 생성 정책, `initializr-generator-spring`의 선택적 convention, `initializr-generator-test`의 테스트 기반, `initializr-web`의 전달 경계를 분리한 점을 참고했다. 이는 이 저장소의 engine, plugin, benchmarks 분리를 유지하는 근거다.

다만 Spring Initializr의 모든 이름을 모범 답안으로 복제하지는 않았다. 공식 코드에도 `BuildItemResolver` 등 `-er` 이름이 존재한다. 그 이름은 해당 저장소의 계약과 역사 안에서 판단할 대상이다. 이번 작업은 구조와 aggregate 경계를 참고하되, 이 저장소의 객체가 실제로 캡슐화하는 것을 다시 관찰해 이름을 결정했다.

## 감사 결과

감사 시점의 프로덕션 코드에는 프로젝트가 직접 정의한 `-er`, `-or` 타입이 11개 있었다.

- engine: `EditorHighlighterActiveBracketPairResolver`, `BracketPairAnalyzer`, `ResolvedLanguageBraceMatcher`, `AnalysisSnapshotBuilder`, `PairTable.Builder`
- plugin: `ActiveGuidePositionResolver`, `EditorGuideEventRouter`, `EditorGuideSettingsApplier`, `IdentityEventBatcher`, `VisibleTokenDecorationManager`, `BracketGuideRenderer`

이 중 Kotlin 공개 ABI에 직접 노출된 타입은 없었다. 그러나 공개 facade에는 `AnalyzeRequest`라는 동사형 이름과 `BracketEngine`, `AnalysisResult`처럼 실제 도메인 개념을 충분히 드러내지 않는 이름이 있었다. 따라서 내부 suffix 제거만으로 작업을 끝내지 않고 공개 경계까지 재설계했다.

책임 감사에서는 다음 결합도 확인했다.

- `AnalysisSnapshotBuilder`와 `AnalysisPipeline`이 같은 snapshot 생성 책임을 나눠 가졌다.
- `EditorGuideSettingsApplier`가 설정 전파와 version-compatible reflection을 함께 가졌다.
- `VisibleTokenDecorationManager`가 window 정책, highlighter 재사용·폐기, 색상 갱신을 대신 소유해 실제 decoration 객체가 빈 데이터 구조로 남았다.
- `EditorGuideSession`은 699줄에서 분석 상태와 presentation 상태를 함께 관리했다.
- `GuideLineHighlightingPassTest`는 1,910줄, 42개 테스트에서 pass, session, token window, settings transition, active presentation을 함께 검증했다.
- `FakeBracketEngine.kt`는 258줄이며 active-pair 선택과 visible-token selection 일부를 테스트 코드에서 다시 구현했다.

## 엔진 이름과 책임 매핑

| 변경 전 | 변경 후 | 새 객체가 나타내는 것 |
|---|---|---|
| `BracketEngine` | `BracketAnalysis` | plugin이 사용하는 bracket 분석 use case 경계 |
| `AnalyzeRequest` | `AnalysisInput` | 한 번의 분석을 규정하는 editor, file type, coverage, language 선택 |
| `ActivePairRequest` | `CaretContext` | 현재 caret에서 제한 검색을 규정하는 문맥 |
| `ActivePairResult.Complete` | `ActivePairKnowledge.Known` | pair 존재 여부까지 확정된 지식 |
| `ActivePairResult.Incomplete` | `ActivePairKnowledge.Unknown` | 예산 때문에 아직 확정할 수 없는 지식 |
| `AnalysisResult` + `AnalysisSnapshot` | `BracketSnapshot` + `IndexedBracketSnapshot` | 공개 query 계약과 compact index 구현을 분리한 불변 분석 결과 |
| `VisibleTokens` + `VisibleTokenView` | `TokenWindow` | viewport 주변의 제한된 token 관측 창 |
| `AnalysisCapabilities` | `AnalysisCoverage` | snapshot이 답할 수 있어야 하는 질의 범위 |
| `AnalysisRevision` | `AnalysisStamp` | 분석 입력의 동일성과 freshness를 판정하는 값 |
| `BracketPairAnalyzer` | `DocumentBrackets` | 한 document에서 인식된 bracket pair 집합을 만드는 경계 |
| `EditorHighlighterActiveBracketPairResolver` | `CaretBracketSearch` | transition·시간 예산을 공유하는 caret 주변 검색 |
| `IntellijBracketPairingEngine` | `DocumentBraceGrammar` | IntelliJ token과 matcher를 core pairing role로 해석하는 문서 문법 |
| `BracketLanguageSupport`, `LanguageBraceMatchers` | `BraceLanguageCatalog` | 설치된 brace language family discovery와 definition lookup |
| `ResolvedLanguageBraceMatcher` | `BraceLanguageDefinition` | capability ID, matcher, topology, pairing rules의 결합 |
| `AnalysisPlan` | `IndexLayout` | coverage가 요구하는 index 배치 |
| `TokenIndexMode` | `TokenStorage` | token metadata가 없음·결합·분리 중 어떤 저장 형태인지 나타내는 값 |
| `AnalysisSnapshotBuilder`, `AnalysisPipeline` | `SnapshotAssembly` | pair 인식부터 index 생성까지 한 snapshot 조립 책임 |
| builder 내부의 multiline range | `GuideLineEnvelope` | guide index가 실제로 읽어야 하는 line 범위 |
| `PairTable.Builder` | `PairTable.Draft` | freeze 전 단일 사용 mutable pair table 상태 |
| `PairTable.builder()` / `build()` | `PairTable.draft()` / `freeze()` | 초안 생성과 소유권 이전을 드러내는 동작 |

`BracketPair`, `BracketGuide`, `BraceLanguageFamily`, `ActiveBracketPairIndex`, `BracketTokenIndex`, `GuidePositionIndex`, `PairingMachine`, `PairingRules`, `PairTable`, `BracketRole`, `StructuralRole`은 객체나 자료구조의 정체와 규칙을 이미 드러내므로 유지한다.

## 플러그인 이름과 책임 매핑

| 변경 전 | 변경 후 | 새 객체가 나타내는 것 |
|---|---|---|
| `IdeCompatibilityNotice` | `UnsupportedIdeWarning` | 지원하지 않는 IDE에서 한 번만 나타나는 경고; 입력도 `Unsupported`로 제한 |
| `ActiveGuidePositionResolver` | `GuidePositionFallback` | authoritative index가 없을 때 사용하는 제한된 guide 위치 정책 |
| `EditorGuideEventRouter` | `EditorGuideEvents` | IntelliJ editor event와 session 생명주기의 application 경계 |
| `IdentityEventBatcher` | `IdentityEventBatch` | 객체 identity별 pending event와 예약 상태 |
| `EditorGuideSettingsApplier` | `GuideSettingsChange` + `DaemonRefresh` | 설정 차이·session 전파와 daemon API 호환 책임의 분리 |
| `GuideLineHighlightingPass` | `BracketGuideHighlightingPass` | bracket guide를 위한 한 번의 platform highlighting pass |
| `GuideLineHighlightingPassFactory` | `BracketGuideHighlighting` | highlighting extension point의 등록 객체 |
| `BracketGuideRenderer` | `BracketGuideDrawing` | guide geometry와 paint 동작을 가진 drawing |
| `GuideRenderOptions` | `GuideAppearance` | line width, opacity, segment 표시 여부라는 표현 값 |
| `PluginConfigurable` | `BracketGuideSettingsPage` | 사용자가 보는 Settings 페이지 |
| `PluginSettings` | `BracketGuideSettings` | 플랫폼 persistence component |
| `PluginOptions` | `BracketGuidePreferences` | 정규화된 사용자 설정 값 |
| `StoredBracketColors` | `StoredColorFormat` | 기존 XML 정수 슬롯을 보존하는 persistence 표현 규칙 |
| `VisibleTokenDecorationManager` | `VisibleTokenDecorations`에 흡수 | token window와 highlighter 생명주기를 소유하는 aggregate |
| `ReusableHighlighters` | `PreviousTokenMarks` | 다음 decoration에 재사용할 수 있는 이전 markup 집합 |
| `EditorGuideSession` 내부 분석 상태 | `EditorAnalysisState` | stamp, snapshot, freshness와 coverage 판정 |
| session의 pair/range/anchor 상태 | `TrackedBracketPair` | 원본 pair, endpoint·anchor `RangeMarker`, 편집 후 보정과 depth hint |
| `ActivePairDecoration`과 session의 highlighter 상태 | `ActivePairMarkup` | guide와 pair `RangeHighlighter` 생명주기 |
| session companion registry | `EditorGuideSessions` | editor user-data key, 설치·조회·폐기와 background accepted-stamp 조회 |
| `GuidePaintState` user data | `BracketGuideDrawing`에 흡수 | guide, appearance, color와 framework paint callback의 결합 |

`EditorGuideSession`은 editor 하나의 세션이라는 정체를 유지하되, 모든 상태를
직접 소유하지 않고 `EditorAnalysisState`, `TrackedBracketPair`,
`ActivePairMarkup`, `VisibleTokenDecorations`의 협력을 조정한다. 전역 registry는
`EditorGuideSessions`가 소유한다. `BracketGuideDrawing`은 별도 user-data 상태를
읽는 singleton이 아니라 guide, appearance, color를 함께 가진
`CustomHighlighterRenderer` 인스턴스가 된다. `DocumentChange`,
`BracketColorPalette`, `IdeCompatibility`는 현재 이름이 값이나 상태의 정체를
나타내므로 유지한다.

## 책임을 합치고 나눈 이유

### Snapshot 조립은 하나의 변경 근원이다

기존 builder는 pair가 없거나 coverage가 비활성인 경우를 처리했고, pipeline은 index 생성 순서와 memory peak를 처리했다. 둘 다 “어떤 snapshot을 어떻게 조립할 것인가”가 바뀔 때 함께 수정됐다. `SnapshotAssembly`가 전체 흐름을 소유하고, `GuideLineEnvelope`만 guide index의 독립 값으로 분리한다.

### 언어 discovery와 문서 인식은 다르다

설정 화면에서 설치 언어 목록을 구성하는 이유와 token stream에서 matcher를 적용하는 이유는 다르다. `BraceLanguageCatalog`는 discovery와 definition lookup을 맡고, `DocumentBraceGrammar`는 현재 문서 token의 role과 context를 해석한다. `DocumentBrackets`는 그 문법을 사용해 완전한 pair 집합을 만든다.

### 완료된 결과와 미확정 상태는 null 하나로 표현하지 않는다

caret 제한 검색은 “pair가 없음이 확정됨”과 “예산이 끝나 아직 모름”을 구분해야 한다. `ActivePairKnowledge.Known(pair?)`와 `Unknown`은 이 도메인 차이를 타입으로 유지한다. provisional markup을 유지할지 제거할지는 이 차이에 의존한다.

### 설정 전이와 daemon API 호환은 다른 액터가 바꾼다

사용자 preference와 editor presentation 정책이 바뀌면 `GuideSettingsChange`가 변한다. IntelliJ의 restart overload나 reflection 조건이 바뀌면 `DaemonRefresh`가 변한다. 기존 테스트도 restart 메서드 선택만 독립적으로 검사하고 있어 분리 근거가 이미 존재했다.

### Markup은 자신의 생명주기를 소유한다

visible token highlighter와 active pair highlighter는 생성, 재사용, attribute 갱신, 폐기라는 명확한 생명주기를 가진다. 이를 manager나 session의 절차로 두지 않고 `VisibleTokenDecorations`, `ActivePairMarkup`이 직접 소유하게 한다. session은 event에 따라 이 객체들에 명령할 뿐 세부 range 조작을 알지 않는다.

## 유지한 이름과 예외

### 의도적으로 유지한 프로젝트 이름

- `BracketPair`, `BracketGuide`, `TokenWindow`: 분석 결과의 도메인 값이다.
- `ActiveBracketPairIndex`, `BracketTokenIndex`, `GuidePositionIndex`: 검색 가능한 자료구조의 정체가 이름에 드러난다.
- `PairingMachine`: 실제 상태 기계이며 단순 행위자 접미사 이름이 아니다.
- `PairTable`: primitive pair geometry의 불변 표다.
- `EditorGuideSession`: editor 한 개에 묶인 생명주기 aggregate다.
- `DocumentChange`: document event에서 정규화한 변경 값이다.

### 외부 IntelliJ 계약

다음 이름은 프로젝트가 바꿀 수 없거나 외부 계약을 정확히 나타낸다.

- `Editor`, `ProgressIndicator`, `BraceMatcher`, `PairedBraceMatcher`, `PairedBraceMatcherAdapter`, `XmlAwareBraceMatcher`
- `HighlighterIterator`, `RangeHighlighter`, `RangeMarker`, `CustomHighlighterRenderer`
- `ApplicationManager`, `NotificationGroupManager`, `DaemonCodeAnalyzer`와 각종 IntelliJ listener
- extension point ID `com.intellij.lang.braceMatcher`
- compiler 생성 타입 `DefaultConstructorMarker`

`IdeCompatibilityStartupActivity`처럼 IntelliJ `ProjectActivity`의 구현임을 드러내는 adapter 이름도 유지할 수 있다. 이는 도메인 객체 이름을 행위자 접미사로 대신한 경우와 구분한다.

### 테스트, JMH, upstream fixture

- `...Test`는 테스트 프레임워크 관례다.
- `LongArraySortBenchmark`, `LongArraySortCancellationBenchmark`, `PairingMachineBenchmark`의 `Benchmark`는 JMH 진입점이라는 정체다.
- JMH 진입점이 아니면서 입력 배열을 만드는 `BenchmarkLongArrays`는 실제
  샘플 집합이라는 정체에 맞춰 `LongArraySamples`로 변경했다.
- `plugin/src/test/testData/real-world/IconUtil.kt`와 `YAMLUtil.java`는 고정된 upstream 회귀 fixture이며 `UPSTREAM_NOTICE.txt`와 license가 있다. 그 안의 이름은 변경하지 않는다.
- `**/build/**`의 JMH generated source는 생성물이므로 감사 대상에서 제외한다.

테스트 소유 fixture도 실제 정체를 드러내도록 `Recorder`→`RecordedPairs`,
`LegacyAnalyzer`/`CurrentAnalyzer`→`LegacyDaemonApi`/`CurrentDaemonApi`,
`TestBraceMatcher`→`BraceGrammarFixture`, `StrictTagMatcher`→`StrictTagGrammar`,
`CharacterSyntaxHighlighter`→`CharacterSyntax`, `CharacterLexer`→`CharacterTokens`,
`FixedWidthInlayRenderer`→`FixedWidthInlay`로 변경했다.

## ABI와 plugin 등록 계약

공개 engine facade 이름이 변경되므로 `engine/api/engine.api`의 변화는 의도적이다. 최종 baseline에는 root `analysis` package의 다음 공개 경계만 포함되어야 한다.

- `BracketAnalysis`
- `AnalysisInput`, `CaretContext`
- `ActivePairKnowledge`
- `BracketSnapshot`, `TokenWindow`
- `AnalysisCoverage`, `AnalysisStamp`
- presentation DTO인 `BracketPair`, `BracketGuide`, `BraceLanguageFamily`

engine 구현 타입은 공개 facade에 누출하지 않는다. `checkEngineApiPackages`의 root-package 및 leaked-type 검사를 유지한다. `plugin/api/plugin.api`는 계속 비어 있어야 한다.

`plugin.xml`에서는 다음 구현 클래스 참조가 최종 이름과 일치해야 한다.

- application service: `BracketAnalysis`
- settings service: `BracketGuideSettings`
- highlighting extension: `BracketGuideHighlighting`
- configurable: `BracketGuideSettingsPage`
- startup activity: `IdeCompatibilityStartupActivity`

반면 사용자 데이터 호환성을 위해 Settings page ID, persistent state name, storage filename, notification group ID, extension point ID는 변경하지 않는다.

## 행위와 성능 계약

이 작업은 구조 변경이며 다음 외부 행위를 바꾸지 않는다.

- language별 공식 matcher만 사용하고 legacy file-type 또는 raw-character fallback을 추가하지 않는다.
- complete full scan과 제한 caret 검색은 contextual, layered, symmetric, shared-closer, structural 규칙에서 같은 결과를 낸다.
- caret 검색은 기존 transition 수와 best-effort 시간 예산을 유지한다.
- token decoration cap, viewport window, stable focus envelope, highlighter 재사용 규칙을 유지한다.
- cancellation 확인 주기와 index의 primitive storage·peak-memory 생성 순서를 유지한다.
- stale pass가 현재 session dependency나 snapshot을 덮어쓰지 못하게 한다.

`PairTable.Builder`가 `PairTable.Draft`로 바뀌면 core test와 `PairingMachineBenchmark`도 함께 변경한다. `CancellableLongArraySort.kt` 파일명은 Java benchmark가 생성 JVM facade `CancellableLongArraySortKt`를 직접 참조하므로, 별도 근거 없이 변경하지 않는다.

JMH는 절대 성능 합격선을 제공하지 않는다. 기존 입력 크기, distribution, cancellation 지연을 유지한 동일 조건 비교와 `jmhJar` 컴파일을 통해 구조 변경이 benchmark 경계를 깨뜨리지 않았는지 확인한다.

## TDD 검증 계획

검증은 다음 순서로 진행했다.

1. 기존 characterization test가 green인지 확인했다.
2. 이름과 책임을 작은 단위로 옮기고 기존 테스트도 새 소유 객체의 이름으로
   이동했다.
3. highlighter 참조 동일성과 language-family 방어 복사처럼 새로 강화한
   불변식은 전용 테스트로 추가했다.
4. 전체 회귀 테스트와 ABI baseline 및 package guard를 검증했다.
5. plugin structure와 configuration 정적 계약을 검증했다.
6. benchmark harness를 패키징하고 smoke run으로 실행 경계를 확인했다.

핵심 characterization 범위는 다음과 같다.

- engine facade의 stamp 보존, coverage별 index 생성, defensive copy, cancellation 전파
- full/caret parity의 contextual, layered, symmetric, structural, budget 행위
- active/token/guide index의 strict boundary, overflow, cancellation, random-model parity
- session의 stale result 거부, highlighter 교체, split editor, secondary caret, 설정·theme 전이
- visible token cap과 재중심화, active markup 재사용·폐기
- guide drawing의 soft wrap, viewport clip, opacity, invalid range 안전성
- unsupported IDE warning의 내용과 한 번만 표시되는 성질

다음 명령으로 최종 상태를 검증했다.

```shell
./gradlew :engine:test :plugin:test
./gradlew :engine:check :plugin:check
./gradlew :benchmarks:jmhJar
./gradlew :benchmarks:jmh -PbenchmarkSmoke=true
./gradlew :plugin:verifyPluginProjectConfiguration :plugin:verifyPluginStructure
```

### 구현 결과

- engine 공개 경계는 `BracketAnalysis`, `AnalysisInput`, `CaretContext`,
  `AnalysisCoverage`, `AnalysisStamp`, `ActivePairKnowledge`, `BracketSnapshot`,
  `TokenWindow`로 교체했다. `AnalysisStamp`는 identity hash 정수 대신 실제
  highlighter 참조를 캡처해 `===`로 동일성을 판정한다.
- `BraceLanguageCatalog`가 언어 family와 definition을 함께 소유하고,
  `DocumentBraceGrammar`, `DocumentBrackets`, `CaretBracketSearch`가 문법 해석,
  전체 인식, 제한 검색을 각각 소유한다.
- builder와 pipeline은 `SnapshotAssembly`로 합쳤고 guide line 범위는
  `GuideLineEnvelope`로 분리했다. `PairTable.Draft.freeze()`가 mutable 초안의
  단일 사용과 배열 소유권 이전을 명시한다.
- plugin에서는 `GuideSettingsChange`와 `DaemonRefresh`, `EditorAnalysisState`,
  `TrackedBracketPair`, `ActivePairMarkup`, `VisibleTokenDecorations`,
  `EditorGuideSessions`로 상태와 생명주기를 이동했다. `BracketGuideDrawing`은
  guide, appearance, color와 paint 행위를 한 인스턴스에 둔다.
- `IdeCompatibility`는 지원 상태만 표현하고, startup adapter가 extension
  registry를 조회한다. `UnsupportedIdeWarning`은 누락된
  `com.intellij.lang.braceMatcher`를 명시하는 오류를 애플리케이션 전체에서
  한 번만 표시한다.
- 계획과 달리 `BracketSnapshot`과 `TokenWindow`는 concrete class로 고정하지
  않고 공개 query interface로 유지했다. plugin 테스트가 내부 index 구현을
  복제하지 않고 명시적 결과 대역을 주입할 수 있는 DIP 경계이기 때문이다.
- 최종 source scan에서 engine, plugin, benchmark, demo와 프로젝트 소유 test
  fixture의 `-er`, `-or` 타입 선언은 0개다. upstream test data, generated
  source, 외부 IntelliJ/JMH 계약은 제외했다.
- production의 테스트 전용 생성자, 상태 getter, fixture 변환, clock·budget
  제어를 제거했다. 실제 메모리·호환성 정책은 `GuideTreeShape`,
  `DocumentChangeRoute`, `DaemonRestartContract`가 소유하며 production 경로도
  이 객체들을 직접 사용한다.

### 테스트 결과

- `:engine:test`: 106개 성공, 실패·오류 0개. 구현 seam 자체만 검증하던
  테스트 2개는 노출과 함께 삭제했다.
- `:plugin:test`: 102개 성공, 실패·오류 0개. 실제 daemon restart 계약
  테스트를 추가했다.
- `:engine:check`, `:plugin:check`: 성공. 갱신한 ABI baseline, root-package
  guard, 빈 `plugin.api` 계약을 포함한다.
- `:benchmarks:jmhJar`: 성공. `-PbenchmarkSmoke=true` 실행도 38개 benchmark
  parameter 조합을 완료했다. smoke 수치는 성능 판단 근거로 사용하지 않는다.
- `:plugin:verifyPluginProjectConfiguration`, `:plugin:verifyPluginStructure`: 성공.
- `git diff --check`: 성공.

## 의도적 유예 항목

- `BracketGuideHighlightingPassTest`의 물리적 파일 분할은 새 책임별 테스트
  이동과 별개로 유예했다. 이번 변경에서는 기존 42개 행위를 잃지 않는 것이
  우선이었다.
- `FakeBracketAnalysis`의 active-pair 선택과 token-window 선택 로직은 여전히
  test fixture 안에 일부 존재한다. active-pair tie-break 전용 비교는 있지만
  production과 fake token-window를 직접 비교하는 parity test는 없다. 따라서
  production의 단일 진실 공급원으로 취급하지 않으며 engine parity 및 snapshot
  테스트를 authoritative 검증으로 유지한다.
- `BracketGuidePreferences`의 flat XML 필드는 기존 설정 파일과 property 이름을
  보존하기 위해 유지했다. persistence DTO와 더 세분화된 runtime appearance 값의
  완전한 분리는 별도 migration 근거가 생길 때 수행한다.
- `PairSink`의 일곱 primitive 인자와 `PairTable`의 parallel array는 hot path에서
  pair별 객체 할당을 피하기 위해 유지했다. 이 예외는 benchmark와 cancellation
  테스트로 보호한다.
- pairing, sorting, index 알고리즘 자체의 성능 최적화는 이번 이름·책임 변경 범위에 포함하지 않는다. 행위와 allocation 경계를 보존하고 별도 benchmark 근거가 있을 때만 변경한다.
- 외부 IntelliJ 타입, JMH 관례, upstream fixture, persistence ID는 명명 일관성을 이유로 변경하지 않는다.
- 모든 영단어의 `-er`, `-or` 철자를 금지하지 않는다. 접미사보다 객체의 실제 정체와 변경 근원이 우선이다.

## 참고 자료

- 즐거운 학습, [클린 코더스 강의 1. 소개 및 OOP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279438)
- 즐거운 학습, [클린 코더스 강의 7. TDD 1](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279445)
- 즐거운 학습, [레거시 코드에 테스트 추가하는 또 하나의 방법](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279466)
- 즐거운 학습, [클린 코더스 강의 10. Architecture](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279449)
- 즐거운 학습, [클린 코더스 강의 13. SRP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279452)
- 즐거운 학습, [클린 코더스 강의 15.1. DIP](https://www.inflearn.com/courses/lecture?courseId=336905&unitId=279456)
- [Spring Initializr 공식 저장소](https://github.com/spring-io/initializr)
- Spring Initializr [`ProjectDescription`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectDescription.java)
- Spring Initializr [`ProjectGenerationContext`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/project/ProjectGenerationContext.java)
- Spring Initializr [`Build`](https://github.com/spring-io/initializr/blob/main/initializr-generator/src/main/java/io/spring/initializr/generator/buildsystem/Build.java)
- Spring Initializr [`initializr-generator-test`](https://github.com/spring-io/initializr/tree/main/initializr-generator-test)
- [Kotlin visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html)
- [IntelliJ Platform Testing FAQ](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)
