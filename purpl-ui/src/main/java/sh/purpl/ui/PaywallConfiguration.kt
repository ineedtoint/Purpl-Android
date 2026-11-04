package sh.purpl.ui

import sh.purpl.PaywallProductDisplayContent
import sh.purpl.PurchaseCatalog
import sh.purpl.ResolvedPaywallConfiguration

/// 앱에 정의하거나 원격 응답에서 해석한 구매 화면 구성
public data class PaywallConfiguration(
    val catalog: PurchaseCatalog,
    val defaultPurchaseOptionIdentifier: String? = null,
    val productDisplayContents: List<PaywallProductDisplayContent> = emptyList(),
    val autoRenewalNotice: String? = null,
    val privacyPolicyUrl: String? = null,
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
            autoRenewalNotice = configuration.autoRenewalNotice,
            privacyPolicyUrl = configuration.privacyPolicyUrl,
            termsOfServiceUrl = configuration.termsOfServiceUrl,
        )
    }
}
