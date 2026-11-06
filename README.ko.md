# Purpl Android

[English](README.md) | 한국어 | [문서](https://purpl.sh/ko/docs)

Purpl은 Google Play 구매, 권한 확인, 복원과 Jetpack Compose 페이월을 제공하는 Kotlin SDK입니다.

Purpl 관리 서비스를 사용하지 않아도 로컬 구성으로 구매, 복원, 권한 확인과 페이월을 사용할 수 있습니다. 관리 서비스를 연결하면 웹에서 구성한 카탈로그를 같은 페이월 UI에 표시하고, 서버에서 고객 권한을 확인할 수 있습니다. 실제 상품 정보, 가격과 구매 가능 여부는 항상 Google Play를 기준으로 합니다.

## 요구 사항

- Android 8.0(API 26)+
- Kotlin Android 프로젝트, `purpl-ui` 사용 시 Jetpack Compose
- 구매 테스트를 위한 Google Play 상품과 테스트 계정

이 저장소는 Android Gradle Plugin 9.3.3, Kotlin 2.4.20, Gradle 9.5.0과 Android SDK 37을 사용합니다.

## 설치

앱 프로젝트 옆에 저장소를 복제합니다.

```sh
git clone https://github.com/ineedtoint/Purpl-Android.git
```

앱의 `settings.gradle.kts`에서 composite build를 연결합니다. 경로는 복제한 저장소 위치에 맞춥니다.

```kotlin
includeBuild("../Purpl-Android")
```

앱의 `build.gradle.kts`에 사용할 모듈을 추가합니다.

```kotlin
dependencies {
    implementation("sh.purpl:purpl:0.1.0")
    implementation("sh.purpl:purpl-ui:0.1.0")
}
```

구매와 권한 관리만 필요하면 `purpl`을, 기본 Compose 페이월도 사용하면 `purpl-ui`를 함께 연결합니다. Google Play 결제 라이브러리는 core 모듈의 의존성으로 포함되므로 앱에서 별도로 추가할 필요가 없습니다.

아직 Maven Central에는 게시하지 않았습니다. 위 의존성은 로컬 composite build로 연결되며, GitHub 저장소를 만들거나 복제하는 것만으로 Maven 저장소에 배포되지는 않습니다.

## 예제

[예제 앱](sample)에는 월간·연간 구매 옵션을 표시하는 Compose 페이월이 포함되어 있습니다. 이 저장소를 Android Studio에서 열고 `sample` 실행 구성을 선택합니다.

구매를 테스트하기 전에 예제의 패키지 이름, 상품 ID와 기본 요금제 ID를 실제 Play Console 설정으로 바꿉니다. 예제의 기본 모드는 `EntitlementMode.Server`이므로 같은 앱과 상품을 Purpl에도 설정해야 합니다. Google Play만 사용하려면 `EntitlementMode.GooglePlay`를 선택하고 Play Console의 앱 공개 키를 넣습니다.

예제는 가상의 로컬 상품 대신 Google Play를 사용합니다. [Google Play 테스트 안내](https://developer.android.com/google/play/billing/test)에 따라 테스트 트랙과 라이선스 테스터를 설정합니다.

## 로컬 구성

앱 전체에서 사용하는 상품과 각 상품이 제공하는 권한을 `PurchaseConfiguration`에 등록합니다. 판매가 종료된 과거 상품도 기존 구매자의 권한 확인과 복원을 위해 이 구성에는 유지합니다. `Application` 시작 시 `Purchases`를 한 번 구성하고 반환된 인스턴스를 앱 UI에서 공유합니다. 아래 초기화 예제들은 각각 선택할 수 있는 모드를 보여 줍니다.

```kotlin
import sh.purpl.*

val plusEntitlement = PurchaseEntitlement(identifier = "plus")
val subscriptionProduct = PurchaseProduct(
    productIdentifier = "plus.v1",
    entitlementIdentifiers = setOf(plusEntitlement.identifier),
)
val legacyLifetimeProduct = PurchaseProduct(
    productIdentifier = "plus.lifetime.legacy",
    entitlementIdentifiers = setOf(plusEntitlement.identifier),
    kind = ProductKind.NonConsumable,
)
val purchaseConfiguration = PurchaseConfiguration(
    entitlements = listOf(plusEntitlement),
    products = listOf(subscriptionProduct, legacyLifetimeProduct),
)

val purchases = Purchases.configure(
    context = context,
    configuration = PurchasesConfiguration(
        purchaseConfiguration = purchaseConfiguration,
        entitlementMode = EntitlementMode.GooglePlay,
        googlePlayPublicKey = "YOUR_GOOGLE_PLAY_PUBLIC_KEY",
    ),
)
```

공개 키 자리에는 Play Console의 Base64 RSA 앱 공개 키를 넣습니다. Google Play 모드는 이 키와 로컬 상품·권한 매핑으로 현재 구매를 검증하며, Purpl 계정이나 서버가 필요하지 않습니다. 소모성 상품과 외부 결제는 지원하지 않습니다.

페이월에서 판매할 구매 옵션과 표시 순서는 별도의 `PurchaseCatalog`로 정의합니다. Google 구독 상품 하나에는 월간·연간과 같은 여러 기본 요금제를 둘 수 있습니다. 과거 상품을 카탈로그에서 제외하면 기존 권한 매핑은 유지하면서 새 판매 화면에서는 표시하지 않을 수 있습니다.

```kotlin
import androidx.compose.material3.Text
import sh.purpl.*
import sh.purpl.ui.*

val standardCatalog = PurchaseCatalog(
    identifier = "standard",
    options = listOf(
        PurchaseOption("plus-monthly", "plus.v1", basePlanIdentifier = "monthly"),
        PurchaseOption("plus-yearly", "plus.v1", basePlanIdentifier = "yearly"),
    ),
)

PaywallView(
    purchases = purchases,
    configuration = PaywallConfiguration(
        catalog = standardCatalog,
        defaultPurchaseOptionIdentifier = "plus-yearly",
    ),
) {
    Text("Plus")
}
```

`PaywallView`는 Composable 안에서 사용합니다. `offerIdentifier = null`이면 혜택 없는 기본 요금제를 선택하고, 혜택을 판매하려면 해당 ID를 지정합니다. Google Play가 현재 사용자에게 제공하는 옵션만 반환합니다. 비소모성 상품의 구매 옵션에는 기본 요금제와 혜택 ID를 넣지 않습니다.

고객 권한 확인과 구매 복원은 코루틴에서 호출합니다.

```kotlin
val customerInfo = purchases.customerInfo()
val hasPlusAccess = customerInfo.isEntitlementActive("plus")
val restoredCustomerInfo = purchases.restorePurchases()
```

`customerInfoFlow`로 권한 변경을 관찰할 수 있으며 최초 조회 성공 전 값은 `null`입니다. `errors`는 백그라운드 동기화와 확인 처리 오류를 전달합니다. 조회 실패를 구매 내역 없음으로 처리하지 않습니다.

## Purpl 관리 구성

Purpl 관리 서비스에서 고객 권한과 페이월 카탈로그를 관리하려면 서버 모드로 구성하고 웹에서 정의한 페이월 식별자를 전달합니다.

```kotlin
val purchases = Purchases.configure(
    context = context,
    configuration = PurchasesConfiguration(entitlementMode = EntitlementMode.Server),
)

PaywallView(purchases = purchases, paywallIdentifier = "standard") {
    Text("Plus")
}
```

원격 구성은 앱의 패키지 이름, 페이월 식별자와 현재 언어로 조회합니다. Purpl은 카탈로그, 상품·권한 매핑, 현지화된 내용, 자동 갱신 안내와 정책 링크를 제공합니다. 실제 상품 조회, 가격, 구매 가능 여부와 구매는 Google Play가 처리합니다. Google 서비스 계정 자격 증명은 앱에 넣지 않습니다.

원격 구성이 로딩 중이거나 사용할 수 없을 때 로컬 페이월을 제공하려면 `paywallIdentifier`와 함께 앱의 카탈로그를 `configuration`으로 전달합니다.

```kotlin
PaywallView(
    purchases = purchases,
    paywallIdentifier = "standard",
    configuration = PaywallConfiguration(
        catalog = standardCatalog,
        defaultPurchaseOptionIdentifier = "plus-yearly",
    ),
) {
    Text("Plus")
}
```

`PaywallModel`은 로컬 카탈로그를 먼저 조회한 뒤 캐시 또는 새로 받은 원격 구성을 적용합니다. 원격 갱신은 `paywallConfigurationUpdates`로 전달되고 기본 UI에도 반영됩니다. 페이월 폴백은 표시 구성에만 적용되며, 권한 검증은 초기화 시 선택한 모드를 따릅니다.

| 모드 | 권한 확인 | 필요한 구성 |
| --- | --- | --- |
| `GooglePlay` | 현재 Play 구매 조회와 구매 서명 검증 | 로컬 구매 구성과 앱 공개 키 |
| `Server` | Purpl이 구매 토큰으로 Google에 검증 | Purpl 앱·스토어·상품·권한 설정 |
| `ServerWithGooglePlayFallback` | 서버 우선, 서버 장애 시 기기 검증 | 위 두 구성 모두 |

권한도 기기로 폴백하려면 초기화 시 `ServerWithGooglePlayFallback`, `purchaseConfiguration`과 `googlePlayPublicKey`를 지정합니다. 서버 오류, 네트워크 실패와 응답 해석 실패는 기기 검증으로 폴백할 수 있습니다. 서버가 확정한 비활성·무효·대기·미완료 구매는 폴백으로 권한을 되살리지 않습니다.

기기 검증만으로는 신뢰할 만한 구독 만료일과 현재 기본 요금제·혜택 ID를 알 수 없어 해당 필드는 `null`입니다. 서버로 검증한 권한에는 `expiresAt`, `basePlanIdentifier`와 `offerIdentifier`가 포함될 수 있습니다. 현재 구매 토큰이 여러 개면 각각의 권한을 합칩니다.

## 구매와 구독 변경

구매 화면을 직접 만들 때는 코루틴에서 옵션을 조회하고 구매를 요청합니다. 앱 UI에서 `PurchasesException`을 처리합니다.

```kotlin
val options = purchases.products(standardCatalog)
val selectedOption = options.firstOrNull()
if (selectedOption != null) {
    val result = purchases.purchase(activity, selectedOption)
}
```

결과는 `Completed`, `Pending`, `Cancelled`입니다. 대기 구매는 권한 부여와 확인 처리(acknowledge) 대상이 아닙니다. 완료된 구매는 SDK가 권한을 검증한 뒤 Google Play에 확인 처리를 보냅니다. 실패한 서버 동기화와 확인 처리는 앱의 `noBackupFilesDir`에 각각 보존하고, 앱 실행 중과 다음 실행·포그라운드 복귀 시 재처리합니다. 앱 프로세스가 종료된 동안 SDK는 실행되지 않으므로 [Google 구매 처리 요구 사항](https://developer.android.com/google/play/billing/integrate#process)을 함께 확인합니다.

구독을 변경할 때는 `activePurchases()`에서 기존 구매를 선택하고 구매 토큰과 앱의 변경 정책을 전달합니다. SDK는 어떤 권한이 업그레이드나 다운그레이드인지 추측하지 않습니다.

```kotlin
val previousPurchase = purchases.activePurchases().singleOrNull()
if (selectedOption != null && previousPurchase != null) {
    val replacement = SubscriptionReplacement(
        purchaseToken = previousPurchase.purchaseToken,
        mode = SubscriptionReplacementMode.WithTimeProration,
    )
    val result = purchases.purchase(activity, selectedOption, replacement)
}
```

상품과 기본 요금제 조합에서 지원하는 변경 방식을 선택합니다. 앱 설정에서는 `purchases.subscriptionManagementUrl(productIdentifier)`로 Google Play 구독 관리 화면을 엽니다. 앱 계정 로그인과 연결은 앱의 책임이며, `applicationAccountIdentifier`는 선택 사항입니다.

## 표시 영역의 책임

`PaywallView`는 상품 표시, 구매, 복원, 로딩과 재시도 상태를 담당합니다. 내비게이션, 탭, 툴바와 닫기 버튼은 앱이 구성합니다. `isIncluded`로 카탈로그 일부만 표시할 수 있으며 권한이나 복원 동작에는 영향을 주지 않습니다.

```kotlin
PaywallView(
    purchases = purchases,
    configuration = PaywallConfiguration(catalog = standardCatalog),
    isIncluded = { option -> option.option.productIdentifier == "plus.v1" },
) {
    Text("Plus")
}
```

`PaywallStyle`과 콘텐츠 슬롯으로 앱 디자인을 맞춥니다. 상품 카드를 직접 만들 때는 `PaywallModel`을 생성하고 `PaywallView(model = ..., productCard = ...)`를 사용합니다. 기존 구독을 변경하는 페이월에는 `replacement` 콜백을 연결합니다.

기본 UI는 고객 권한을 관찰해 현재 이용 중인 옵션을 표시하고 중복 구매를 막습니다. 기기 폴백으로 현재 기본 요금제를 확인할 수 없으면 월간·연간을 추측하지 않으며, 주기를 확인할 때까지 같은 상품의 재구매를 막습니다. 구매 가능한 상품이 없으면 안내와 재시도를 표시하고 구매 버튼을 비활성화합니다. 구독 관리는 기본 페이월 대신 앱 설정에서 연결합니다.

Google 기본 선택에는 `defaultPurchaseOptionIdentifier`를 사용합니다. SDK는 이전 `defaultProductIdentifier`로 기본 요금제를 추정하지 않습니다. 기존 선택과 지정된 기본 옵션을 모두 사용할 수 없으면 UI가 구매 가능한 첫 옵션을 선택합니다. 정책 링크는 앱이나 Purpl에서 설정한 주소만 표시합니다.

UI는 영어, 한국어, 일본어, 독일어, 중국어 간체와 번체를 지원합니다.

## API 레퍼런스

SDK 진입점은 [Purchases.kt](purpl/src/main/java/sh/purpl/Purchases.kt), 공개 구성 타입은 [PurchaseConfiguration.kt](purpl/src/main/java/sh/purpl/PurchaseConfiguration.kt), Compose 진입점은 [PaywallView.kt](purpl-ui/src/main/java/sh/purpl/ui/PaywallView.kt)에서 확인할 수 있습니다.

Apple 플랫폼은 [Purpl Swift](https://github.com/ineedtoint/Purpl-Swift)를 참고하세요.

## 개발 확인

Git에서 제외되는 `local.properties` 파일에 `sdk.dir`을 설정하거나 `ANDROID_HOME`을 지정한 뒤 실행합니다.

```sh
./gradlew :purpl:testDebugUnitTest :purpl-ui:testDebugUnitTest :purpl:assembleRelease :purpl-ui:assembleRelease :sample:assembleDebug
```

Play Console 설정과 구매 테스트는 Google의 [결제 설정](https://developer.android.com/google/play/billing/getting-ready)과 [테스트 안내](https://developer.android.com/google/play/billing/test)를 참고하세요.
