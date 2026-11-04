package sh.purpl

/// 스토어가 반환한 가격 단계
public data class PurchasePricingPhase(
    /// Google Play가 현지화한 가격
    val formattedPrice: String,
    /// 가격의 백만분의 일 단위 정수
    val priceAmountMicros: Long,
    /// 가격 통화 코드
    val currencyCode: String,
    /// Google Play의 ISO 결제 주기
    val billingPeriod: String,
    /// 해당 단계의 반복 횟수
    val billingCycleCount: Int,
    /// 무한 자동 갱신 단계 여부
    val recurring: Boolean,
)

/// 현재 구매 가능한 스토어 옵션과 가격
public data class StorePurchaseOption(
    /// 상품·기본 요금제·혜택 선택 정보
    val option: PurchaseOption,
    /// 스토어 또는 구성의 상품 제목
    val title: String,
    /// 스토어 또는 구성의 상품 설명
    val description: String,
    /// Google Play가 현지화한 가격
    val formattedPrice: String,
    /// 무료 체험과 도입 가격을 포함한 결제 단계
    val pricingPhases: List<PurchasePricingPhase>,
    /// 구독 또는 비소모성 상품 유형
    val kind: ProductKind,
) {
    /// 안정적인 구매 옵션 식별자
    val identifier: String get() = option.identifier
    /// Google Play의 ISO 결제 주기
    val billingPeriod: String? get() = pricingPhases.lastOrNull()?.billingPeriod?.takeIf { it.isNotBlank() }
}

/// 원격 페이월의 상품별 표시 문구
public data class PaywallProductDisplayContent(
    /// Google Play 상품 식별자
    val productIdentifier: String,
    /// 스토어 또는 구성의 상품 제목
    val title: String? = null,
    /// 스토어 또는 구성의 상품 설명
    val description: String? = null,
)

/// 로컬 또는 원격에서 해석한 공통 페이월 구성
public data class ResolvedPaywallConfiguration(
    /// 표시할 구매 옵션과 순서
    val catalog: PurchaseCatalog,
    /// 원격으로 제공한 상품 권한 매핑
    val purchaseConfiguration: PurchaseConfiguration,
    /// 처음 선택할 구매 옵션
    val defaultPurchaseOptionIdentifier: String? = null,
    /// 상품별 선택 표시 문구
    val productDisplayContents: List<PaywallProductDisplayContent> = emptyList(),
    /// 선택 자동 갱신 안내 문구
    val autoRenewalNotice: String? = null,
    /// 개인정보 처리방침 주소
    val privacyPolicyUrl: String? = null,
    /// 이용 약관 주소
    val termsOfServiceUrl: String? = null,
)

/// 식별자와 언어별 원격 페이월 갱신 결과
public data class PaywallConfigurationUpdate(
    /// 갱신된 페이월 식별자
    val identifier: String,
    /// 요청한 로케일 식별자
    val localeIdentifier: String,
    /// 새로 저장된 원격 구성
    val configuration: ResolvedPaywallConfiguration,
)
