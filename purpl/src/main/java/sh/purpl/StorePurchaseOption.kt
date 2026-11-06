package sh.purpl

// 스토어가 반환한 가격 단계
/** A pricing phase returned by the store. */
public data class PurchasePricingPhase(
    // Google Play가 현지화한 가격
    /** The price formatted by Google Play for the customer's locale. */
    val formattedPrice: String,
    // 가격의 백만분의 일 단위 정수
    /** The price in micro-units, where 1,000,000 micro-units equal one unit of the currency. */
    val priceAmountMicros: Long,
    // 가격 통화 코드
    /** The ISO 4217 currency code of the price. */
    val currencyCode: String,
    // Google Play의 ISO 결제 주기
    /** The ISO 8601 billing period from Google Play, such as `P1M`. */
    val billingPeriod: String,
    // 해당 단계의 반복 횟수
    /** The number of billing cycles in this phase. */
    val billingCycleCount: Int,
    // 무한 자동 갱신 단계 여부
    /** Whether this phase renews indefinitely. */
    val recurring: Boolean,
)

// 현재 구매 가능한 스토어 옵션과 가격
/** A purchase option currently available from the store, with its price. */
public data class StorePurchaseOption(
    // 상품·기본 요금제·혜택 선택 정보
    /** The selected product, base plan, and offer. */
    val option: PurchaseOption,
    // 스토어 또는 구성의 상품 제목
    /** The product title from the store or configuration. */
    val title: String,
    // 스토어 또는 구성의 상품 설명
    /** The product description from the store or configuration. */
    val description: String,
    // Google Play가 현지화한 가격
    /** The price formatted by Google Play for the customer's locale. */
    val formattedPrice: String,
    // 무료 체험과 도입 가격을 포함한 결제 단계
    /** The pricing phases, including free trials and introductory prices. */
    val pricingPhases: List<PurchasePricingPhase>,
    // 구독 또는 비소모성 상품 유형
    /** Whether the product is a subscription or a non-consumable product. */
    val kind: ProductKind,
) {
    // 안정적인 구매 옵션 식별자
    /** The stable purchase option identifier. */
    val identifier: String get() = option.identifier
    // Google Play의 ISO 결제 주기
    /** The ISO 8601 billing period of the last pricing phase, or `null` for one-time products. */
    val billingPeriod: String? get() = pricingPhases.lastOrNull()?.billingPeriod?.takeIf { it.isNotBlank() }
}

// 원격 페이월의 상품별 표시 문구
/** Display text for a product on a remote paywall. */
public data class PaywallProductDisplayContent(
    // Google Play 상품 식별자
    /** The Google Play product identifier. */
    val productIdentifier: String,
    // 스토어 또는 구성의 상품 제목
    /** The product title from the store or configuration. */
    val title: String? = null,
    // 스토어 또는 구성의 상품 설명
    /** The product description from the store or configuration. */
    val description: String? = null,
)

// 로컬 또는 원격에서 해석한 공통 페이월 구성
/** A paywall configuration resolved from local or remote sources. */
public data class ResolvedPaywallConfiguration(
    // 표시할 구매 옵션과 순서
    /** The purchase options to display and their order. */
    val catalog: PurchaseCatalog,
    // 원격으로 제공한 상품 권한 매핑
    /** The product and entitlement mappings provided remotely. */
    val purchaseConfiguration: PurchaseConfiguration,
    // 처음 선택할 구매 옵션
    /** The purchase option selected initially. */
    val defaultPurchaseOptionIdentifier: String? = null,
    // 상품별 선택 표시 문구
    /** Optional display text for each product. */
    val productDisplayContents: List<PaywallProductDisplayContent> = emptyList(),
    // 선택 자동 갱신 안내 문구
    /** An optional auto-renewal notice. */
    val autoRenewalNotice: String? = null,
    // 개인정보 처리방침 주소
    /** The privacy policy URL. */
    val privacyPolicyUrl: String? = null,
    // 이용 약관 주소
    /** The terms of service URL. */
    val termsOfServiceUrl: String? = null,
)

// 식별자와 언어별 원격 페이월 갱신 결과
/** An update to a remote paywall for an identifier and locale. */
public data class PaywallConfigurationUpdate(
    // 갱신된 페이월 식별자
    /** The identifier of the updated paywall. */
    val identifier: String,
    // 요청한 로케일 식별자
    /** The requested locale identifier. */
    val localeIdentifier: String,
    // 새로 저장된 원격 구성
    /** The newly stored remote configuration. */
    val configuration: ResolvedPaywallConfiguration,
)
