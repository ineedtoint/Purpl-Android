package sh.purpl

/// 고객 권한 확인 방식
public enum class EntitlementMode { GooglePlay, Server, ServerWithGooglePlayFallback }

/// SDK에서 지원하는 상품 유형
public enum class ProductKind { Subscription, NonConsumable }

/// 앱에서 제공하는 이용 권한
public data class PurchaseEntitlement(
    /// 안정적인 공개 식별자
    val identifier: String,
    /// 앱에서 표시할 권한 이름
    val displayName: String = identifier,
)

/// 스토어 상품과 제공 권한 매핑
public data class PurchaseProduct(
    /// Google Play 상품 식별자
    val productIdentifier: String,
    /// 상품이 제공하는 권한 식별자 전체
    val entitlementIdentifiers: Set<String>,
    /// 구독 또는 비소모성 상품 유형
    val kind: ProductKind = ProductKind.Subscription,
) {
    init { require(productIdentifier.isNotBlank()) }
}

/// 페이월에서 선택하는 상품·기본 요금제·혜택 조합
public data class PurchaseOption(
    /// 안정적인 공개 식별자
    val identifier: String,
    /// Google Play 상품 식별자
    val productIdentifier: String,
    /// 구독 기본 요금제 식별자
    val basePlanIdentifier: String? = null,
    /// 구독 혜택 식별자, 없으면 기본 요금제
    val offerIdentifier: String? = null,
) {
    init { require(identifier.isNotBlank()); require(productIdentifier.isNotBlank()) }
}

/// 판매할 구매 옵션과 표시 순서
public data class PurchaseCatalog(
    /// 안정적인 공개 식별자
    val identifier: String,
    /// 표시 순서를 유지하는 구매 옵션
    val options: List<PurchaseOption>,
) {
    init { require(identifier.isNotBlank()); require(options.map { it.identifier }.distinct().size == options.size) }
}

/// 판매가 종료된 상품을 포함하는 전체 권한 구성
public data class PurchaseConfiguration(
    /// 앱에서 정의한 권한 목록
    val entitlements: List<PurchaseEntitlement>,
    /// 판매 종료 상품을 포함한 전체 상품 매핑
    val products: List<PurchaseProduct>,
) {
    init {
        require(entitlements.map { it.identifier }.distinct().size == entitlements.size)
        require(products.map { it.productIdentifier }.distinct().size == products.size)
        require(products.all { product -> product.entitlementIdentifiers.all { identifier -> entitlements.any { it.identifier == identifier } } })
    }
}

/// SDK의 서버 연결과 기기 검증 구성
public data class PurchasesConfiguration(
    /// 기기 검증에 사용할 로컬 권한 구성
    val purchaseConfiguration: PurchaseConfiguration? = null,
    /// 권한을 확인할 제공자와 폴백 정책
    val entitlementMode: EntitlementMode = EntitlementMode.GooglePlay,
    /// Play Console의 앱 RSA 공개 키
    val googlePlayPublicKey: String? = null,
    /// Purpl 공개 API의 HTTPS 서버 주소
    val serverBaseUrl: String = "https://api.purpl.sh",
    /// 앱 자체 계정 연결에 사용할 선택 식별자
    val applicationAccountIdentifier: String? = null,
) {
    init {
        require(entitlementMode == EntitlementMode.Server || purchaseConfiguration != null)
        require(entitlementMode == EntitlementMode.Server || !googlePlayPublicKey.isNullOrBlank())
        require(serverBaseUrl.startsWith("https://"))
    }
}
