package sh.purpl

// 고객 권한 확인 방식
/** The method used to resolve customer entitlements. */
public enum class EntitlementMode {
    // Purpl 서버 없이 기기에서 검증한 Google Play 권한만 사용
    /** Uses only Google Play purchases verified on the device, without the Purpl server. */
    GooglePlay,
    // Purpl 서버 권한 사용
    /** Uses entitlements from the Purpl server. */
    Server,
    // Purpl 서버를 우선하고 실패하면 기기의 Google Play 검증 사용
    /** Uses the Purpl server first and falls back to Google Play verification on the device when the server request fails. */
    ServerWithGooglePlayFallback,
}

// SDK에서 지원하는 상품 유형
/** The product types supported by the SDK. */
public enum class ProductKind {
    // 자동 갱신 구독
    /** An auto-renewing subscription. */
    Subscription,
    // 한 번 구매하고 소모하지 않는 일회성 상품
    /** A one-time product that is purchased once and never consumed. */
    NonConsumable,
}

// 앱에서 제공하는 이용 권한
/** An entitlement provided by the app. */
public data class PurchaseEntitlement(
    // 안정적인 공개 식별자
    /** A stable public identifier. */
    val identifier: String,
    // 앱에서 표시할 권한 이름
    /** The entitlement name displayed in the app. */
    val displayName: String = identifier,
)

// 스토어 상품과 제공 권한 매핑
/** A mapping between a store product and the entitlements it grants. */
public data class PurchaseProduct(
    // Google Play 상품 식별자
    /** The Google Play product identifier. */
    val productIdentifier: String,
    // 상품이 제공하는 권한 식별자 전체
    /** The identifiers of all entitlements granted by the product. */
    val entitlementIdentifiers: Set<String>,
    // 구독 또는 비소모성 상품 유형
    /** Whether the product is a subscription or a non-consumable product. */
    val kind: ProductKind = ProductKind.Subscription,
) {
    init { require(productIdentifier.isNotBlank()) }
}

// 페이월에서 선택하는 상품·기본 요금제·혜택 조합
/** A combination of a product, base plan, and offer that customers choose on a paywall. */
public data class PurchaseOption(
    // 안정적인 공개 식별자
    /** A stable public identifier. */
    val identifier: String,
    // Google Play 상품 식별자
    /** The Google Play product identifier. */
    val productIdentifier: String,
    // 구독 기본 요금제 식별자
    /** The subscription base plan identifier. `null` for non-consumable products. */
    val basePlanIdentifier: String? = null,
    // 구독 혜택 식별자, 없으면 기본 요금제
    /** The subscription offer identifier. When `null`, the base plan is purchased without an offer. */
    val offerIdentifier: String? = null,
) {
    init { require(identifier.isNotBlank()); require(productIdentifier.isNotBlank()) }
}

// 판매할 구매 옵션과 표시 순서
/** The purchase options to sell and their display order. */
public data class PurchaseCatalog(
    // 안정적인 공개 식별자
    /** A stable public identifier. */
    val identifier: String,
    // 표시 순서를 유지하는 구매 옵션
    /** The purchase options in display order. */
    val options: List<PurchaseOption>,
) {
    init { require(identifier.isNotBlank()); require(options.map { it.identifier }.distinct().size == options.size) }
}

// 판매가 종료된 상품을 포함하는 전체 권한 구성
/**
 * The complete entitlement configuration of the app.
 *
 * Keep discontinued products so that existing purchases can still be restored and recognized.
 */
public data class PurchaseConfiguration(
    // 앱에서 정의한 권한 목록
    /** The entitlements defined by the app. */
    val entitlements: List<PurchaseEntitlement>,
    // 판매 종료 상품을 포함한 전체 상품 매핑
    /** Every product mapping, including discontinued products. */
    val products: List<PurchaseProduct>,
) {
    init {
        require(entitlements.map { it.identifier }.distinct().size == entitlements.size)
        require(products.map { it.productIdentifier }.distinct().size == products.size)
        require(products.all { product -> product.entitlementIdentifiers.all { identifier -> entitlements.any { it.identifier == identifier } } })
    }
}

// SDK의 서버 연결과 기기 검증 구성
/** Configuration for server connections and device verification. */
public data class PurchasesConfiguration(
    // 기기 검증에 사용할 로컬 권한 구성
    /** The local entitlement configuration used for device verification. Required unless [entitlementMode] is [EntitlementMode.Server]. */
    val purchaseConfiguration: PurchaseConfiguration? = null,
    // 권한을 확인할 제공자와 폴백 정책
    /** The entitlement provider and fallback policy. */
    val entitlementMode: EntitlementMode = EntitlementMode.GooglePlay,
    // Play Console의 앱 RSA 공개 키
    /** The app's Base64-encoded RSA public key from Play Console. Required unless [entitlementMode] is [EntitlementMode.Server]. */
    val googlePlayPublicKey: String? = null,
    // Purpl 공개 API의 HTTPS 서버 주소
    /** The HTTPS base URL of the Purpl public API. */
    val serverBaseUrl: String = "https://api.purpl.sh",
    // 앱 자체 계정 연결에 사용할 선택 식별자
    /** An optional identifier used to link the app's own account. */
    val applicationAccountIdentifier: String? = null,
) {
    init {
        require(entitlementMode == EntitlementMode.Server || purchaseConfiguration != null)
        require(entitlementMode == EntitlementMode.Server || !googlePlayPublicKey.isNullOrBlank())
        require(serverBaseUrl.startsWith("https://"))
    }
}
