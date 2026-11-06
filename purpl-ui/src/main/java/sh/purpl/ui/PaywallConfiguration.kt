package sh.purpl.ui

import androidx.annotation.StringRes
import sh.purpl.PaywallProductDisplayContent
import sh.purpl.PurchaseCatalog
import sh.purpl.ResolvedPaywallConfiguration

// 앱에 정의하거나 원격 응답에서 해석한 구매 화면 구성
/** A paywall configuration defined in the app or resolved from a remote response. */
public data class PaywallConfiguration(
    // 표시할 구매 옵션과 순서
    /** The purchase options to display and their order. */
    val catalog: PurchaseCatalog,
    // 처음 선택할 구매 옵션
    /** The purchase option selected initially. */
    val defaultPurchaseOptionIdentifier: String? = null,
    // 상품별 선택 표시 문구
    /** Optional display text for each product. */
    val productDisplayContents: List<PaywallProductDisplayContent> = emptyList(),
    /// 직접 지정한 문자열보다 우선하는 결제 안내 리소스
    @StringRes val purchaseNoticeResource: Int? = null,
    /// 현지화가 완료된 결제 안내 문구
    val purchaseNoticeText: String? = null,
    // 개인정보 처리방침 주소
    /** The privacy policy URL. */
    val privacyPolicyUrl: String? = null,
    // 이용 약관 주소
    /** The terms of service URL. */
    val termsOfServiceUrl: String? = null,
) {
    init {
        require(defaultPurchaseOptionIdentifier == null || catalog.options.any { it.identifier == defaultPurchaseOptionIdentifier })
    }

    public companion object {
        /// 원격 페이월에서 화면 표시 구성 생성
        public fun from(configuration: ResolvedPaywallConfiguration): PaywallConfiguration = PaywallConfiguration(
            catalog = configuration.catalog,
            defaultPurchaseOptionIdentifier = configuration.defaultPurchaseOptionIdentifier,
            productDisplayContents = configuration.productDisplayContents,
            purchaseNoticeText = configuration.purchaseNotice,
            privacyPolicyUrl = configuration.privacyPolicyUrl,
            termsOfServiceUrl = configuration.termsOfServiceUrl,
        )
    }
}
