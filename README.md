# Purpl-Android

Google Play 결제와 Purpl 권한을 연결하는 Kotlin SDK. Android 8.0(API 26) 이상을 지원한다.

- `purpl`: 상품 조회, 구매, 복원, 권한 관찰, 구독 변경, 서버 동기화, acknowledge 재처리
- `purpl-ui`: Compose 페이월, 원격 구성·캐시·로컬 폴백, 앱 콘텐츠·상품 카드·색상 사용자 정의
- `sample`: 실제 스토어 상품을 연결해 실행하는 Compose 예제

현재 로컬 개발 패키지이며 Maven Central에는 게시하지 않았다. 가격·구매 결과를 흉내 내는 테스트 결제 모드는 포함하지 않는다.

## 연결

앱의 `settings.gradle.kts`에서 패키지를 연결한다.

```kotlin
includeBuild("../Purpl-Android")
```

```kotlin
implementation("sh.purpl:purpl:0.1.0")
implementation("sh.purpl:purpl-ui:0.1.0") // Compose 페이월 사용 시
```

## 구매 구성

권한은 상품에 연결하고, 구매 옵션은 상품·기본 요금제·혜택을 구분한다. 구독 주기가 바뀌어도 권한 식별자는 유지한다.

```kotlin
val purchaseConfiguration = PurchaseConfiguration(
    entitlements = listOf(PurchaseEntitlement("plus", "Plus")),
    products = listOf(PurchaseProduct("plus", setOf("plus"))),
)
val catalog = PurchaseCatalog("standard", listOf(
    PurchaseOption("plus-monthly", "plus", basePlanIdentifier = "monthly"),
    PurchaseOption("plus-yearly", "plus", basePlanIdentifier = "yearly"),
))
```

`offerIdentifier = null`은 혜택이 없는 기본 요금제를 선택한다. 혜택을 판매하려면 정확한 혜택 ID를 지정한다. SDK는 해당 사용자에게 Google Play가 반환한 구매 가능한 옵션만 표시한다. 가격과 결제 단계는 `ProductDetails`에서 읽는다.

비소모성 상품은 `PurchaseProduct(kind = ProductKind.NonConsumable)`로 구성하고 기본 요금제·혜택 ID는 비운다. 소모성 상품과 외부 결제는 지원하지 않는다.

## 초기화와 권한

Application에서 한 번 구성한다. 같은 프로세스에서는 같은 구성으로 공유한다.

```kotlin
val purchases = Purchases.configure(context, PurchasesConfiguration(
    purchaseConfiguration = purchaseConfiguration,
    entitlementMode = EntitlementMode.ServerWithGooglePlayFallback,
    googlePlayPublicKey = "Play Console의 Base64 앱 공개 키",
))
```

| 모드 | 권한 확인 | 필요한 설정 |
| --- | --- | --- |
| `GooglePlay` | 현재 Play 구매 조회 + 원문 RSA 서명 검증 + 앱의 상품 권한 매핑 | 로컬 구매 구성, 앱 공개 키 |
| `Server` | 구매 토큰을 Purpl로 전송하고 서버가 Google에 조회 | Purpl 앱·스토어·상품·권한 설정 |
| `ServerWithGooglePlayFallback` | 서버 우선, 서버 오류 때 기기 검증 | 위 두 설정 모두 |

공개 키가 없거나 유효하지 않은 기기 모드는 초기화 단계에서 거부한다. 서버 모드는 로컬 구매 구성 없이 원격 페이월의 상품을 사용할 수 있다. 서비스 계정 자격 증명이나 앱 API 비밀 키를 SDK에 넣지 않는다.

```kotlin
val information = purchases.customerInfo()
val hasPlus = information.isEntitlementActive("plus")
val restored = purchases.restorePurchases()
```

`customerInfoFlow`는 검증 결과를 관찰하며 최초 성공 전에는 `null`이다. `errors`는 자동 재처리·폴백·확인 처리 오류를 전달한다. 조회 실패를 구매 없음으로 바꾸지 않는다. 여러 구매 토큰에서 얻은 권한을 합치며 서버의 정상 비활성 응답과 명시적인 구매 무효·대기·미완료 판정이 기기 폴백으로 되살아나지 않도록 보존한다.

서버 응답의 `basePlanIdentifier`와 `offerIdentifier`는 현재 권한을 제공하는 Google 기본 요금제와 혜택이다. 기기 응답에는 신뢰할 만한 구독 만료일·기본 요금제·전체 서버 상태가 없다. 기기 모드의 `expiresAt`, `basePlanIdentifier`, `offerIdentifier`는 `null`이고 환경은 추측하지 않는다. 기기 서명 확인은 구매 원문의 진위를 확인하며, 오래 저장한 구매 JSON만으로 계속 권한을 부여하지 않는다.

## 구매와 구독 변경

```kotlin
val option = purchases.products(catalog).first()
val result = purchases.purchase(activity, option)
```

결과는 `Completed`, `Pending`, `Cancelled`다. 대기 결제는 권한 부여와 acknowledge 대상이 아니다. `Completed`는 검증과 권한 반영이 끝났다는 뜻이며 acknowledge 통신 실패는 별도 보존 후 재처리한다.

서로 다른 구독 상품이나 기본 요금제로 변경할 때 앱이 `activePurchases()`에서 기존 구매를 선택하고 `SubscriptionReplacement`를 넘긴다. SDK가 요금제의 상하 관계를 추측하지 않는다.

```kotlin
val previous = purchases.activePurchases().single()
val replacement = SubscriptionReplacement(previous.purchaseToken, SubscriptionReplacementMode.WithTimeProration)
val result = purchases.purchase(activity, option, replacement)
```

Google Play에서 지원하는 변경 방식은 조합에 따라 다르므로 앱에서 정책을 정한다. 관리 화면은 `subscriptionManagementUrl(productIdentifier)`로 연다. `applicationAccountIdentifier`는 선택 사항이며 로그인 연결/해제 자체는 앱의 책임이다.

## 확인 처리와 복구

1. Play에서 `PURCHASED`인 현재 구매를 조회한다.
2. 서버 또는 기기 검증으로 권한을 계산한다.
3. 권한 부여가 확인되면 SDK가 Google Play에 acknowledge를 보낸다.
4. 실패한 서버 동기화와 acknowledge는 각각 보존하고 앱 실행 중 재시도 및 다음 실행·포그라운드 복귀 때 다시 처리한다.

Google의 일반 구매 확인 기한은 `PENDING`이 끝나 `PURCHASED`가 된 시점부터 3일이다. 테스트 구매의 기한은 더 짧다. SDK는 앱 프로세스가 종료된 동안 실행되지 않으므로 앱이 기한 내에 다시 실행되지 않거나 지속적으로 통신할 수 없으면 자동 환불될 수 있다. 서버 acknowledge는 사용하지 않는다.

작업 토큰은 앱 전용 `noBackupFilesDir`에 원자적으로 저장한다. 백업으로 다른 기기에 복원되지 않는다. 서버의 500·502·503, HTML 오류 페이지, 네트워크 실패와 응답 해석 실패는 폴백 대상이다. 서버 동기화는 성공할 때까지 별도로 재시도한다. 정상 비활성 응답과 구매 무효·대기·미완료 판정은 폴백하지 않는다. 취소는 호출자에게 전파하며, 서명이 틀리거나 `PURCHASED`가 아닌 기기 구매는 권한과 acknowledge 대상이 아니다.

## Compose 페이월

```kotlin
val fallback = PaywallConfiguration(catalog, defaultPurchaseOptionIdentifier = "plus-yearly")
PaywallView(
    purchases = purchases,
    configuration = fallback,
    paywallIdentifier = "standard",
) {
    Text("Plus")
}
```

`paywallIdentifier`를 생략하면 앱 내 구성만 사용한다. `configuration`을 생략하면 원격 구성이 필요하다. 권한 확인 모드와 화면의 로컬 폴백은 별개다. 기기 전용 모드에서는 앱 내 페이월을 사용한다.

Google 원격 페이월의 기본 선택은 `defaultPurchaseOptionIdentifier`만 사용한다. 이전 `defaultProductIdentifier`에서 월간·연간을 추정하지 않는다. 기존 선택과 지정된 기본 옵션이 없으면 기본 UI는 현재 구매 가능한 첫 옵션을 선택한다.

`PaywallModel`을 직접 만들면 상태를 관찰하고 `PaywallView(model = ...)`에 넘길 수 있다. `isIncluded`는 표시할 옵션을 필터링하며 권한 판정에는 영향을 주지 않는다. `PaywallStyle`, 상단 `content`, 모델 오버로드의 `productCard`로 앱 디자인을 맞춘다. 구독 변경은 `replacement` 콜백에서 기존 토큰을 지정한다.

기본 페이월은 `customerInfoFlow`를 관찰해 현재 상품·기본 요금제의 이용 중 표시와 중복 구매 차단을 갱신한다. 같은 기본 요금제의 다른 혜택도 중복 구매로 판별한다. 기기 폴백으로 기본 요금제를 알 수 없으면 특정 월간·연간 카드를 보유했다고 표시하지 않고, 주기 확인 안내와 함께 같은 상품의 재구매를 막는다. 복원으로 서버 확인을 다시 시도할 수 있다. 다른 상품으로의 변경은 앱의 교체 정책을 따른다. 구독 관리 버튼은 기본 페이월에 두지 않으며 앱 설정에서 `subscriptionManagementUrl()`로 연결한다.

원격 경로는 `GET /v1/paywalls/google-play?packageName=...&paywallIdentifier=...&localeIdentifier=...`다. 앱·서버·페이월·언어별로 캐시한다. 캐시 이후 조회된 갱신은 `paywallConfigurationUpdates`로 전달되고 기본 UI에 반영된다. 개인정보처리방침/이용약관은 앱 또는 Purpl에 설정한 URL만 표시하며 Apple EULA를 대체값으로 쓰지 않는다.

UI는 영어·한국어·일본어·독일어·중국어 간체/번체를 지원한다. 상품이 없거나 Play 연결에 실패하면 안내와 재시도를 표시하고 구매 버튼을 비활성화한다.

## Purpl-Swift와의 대응

| 역할 | Swift | Android |
| --- | --- | --- |
| 스토어 결제 | StoreKit | Play Billing |
| 독립 사용 / 서버 / 폴백 | StoreKit / 서버 / StoreKit 폴백 | GooglePlay / Server / ServerWithGooglePlayFallback |
| 기기 검증 | StoreKit 검증 결과 | 현재 구매 조회 + 앱 공개 키 서명 검증 |
| 서버 검증 | Apple JWS | 토큰으로 Google Developer API 조회 |
| 완료 처리 | SDK `finish()` | SDK acknowledge |
| 권한 관찰 | 고객 정보 관찰 | `customerInfoFlow` |
| 화면 패키지 | PurplUI | purpl-ui |
| 요금제 구성 | 개별 상품 | 상품 + base plan + offer |

양쪽의 공개 타입 이름과 역할을 맞추되, Google 구매 토큰·확인 기한·요금제 변경은 Android API로 분리했다. Apple 고객 식별자나 Apple 페이월 경로를 Android에 재사용하지 않는다.

## 개발 확인

```sh
./gradlew :purpl:testDebugUnitTest :purpl-ui:testDebugUnitTest :purpl:assembleRelease :purpl-ui:assembleRelease :sample:assembleDebug
```

`local.properties`에 `sdk.dir`을 설정하거나 `ANDROID_HOME`을 지정한다. sample의 패키지·상품과 Purpl 또는 공개 키 설정을 실제 테스트 앱에 맞춰 바꾼다. 실제 결제는 Play Console 상품 활성화, 같은 패키지 이름, 테스트 계정 및 Play 배포/라이선스 테스트 설정이 필요하다.

공식 문서: [결제 통합](https://developer.android.com/google/play/billing/integrate), [구독 변경](https://developer.android.com/google/play/billing/subscriptions), [결제 테스트](https://developer.android.com/google/play/billing/test).
