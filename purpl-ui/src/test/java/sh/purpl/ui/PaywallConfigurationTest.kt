package sh.purpl.ui

import org.junit.Assert.*
import org.junit.Test
import sh.purpl.PurchaseCatalog
import sh.purpl.PurchaseConfiguration
import sh.purpl.PurchaseOption
import sh.purpl.ResolvedPaywallConfiguration

/// 로컬 안내 기본값과 원격 안내 교체 계약
class PaywallConfigurationTest {
    /// 별도 안내를 지정하지 않으면 SDK 기본 문구를 제공하지 않음
    @Test fun localConfigurationHasNoDefaultNotice() {
        val configuration = PaywallConfiguration(PurchaseCatalog("main", listOf(PurchaseOption("lifetime", "lifetime"))))
        assertNull(configuration.purchaseNoticeResource)
        assertNull(configuration.purchaseNoticeText)
    }

    /// 구독 기본 요금제가 없는 상품에도 서버 안내를 그대로 전달
    @Test fun remoteNoticeDoesNotDependOnProductType() {
        val catalog = PurchaseCatalog("main", listOf(PurchaseOption("lifetime", "lifetime")))
        for (notice in listOf("결제 안내", "", null)) {
            val configuration = PaywallConfiguration.from(ResolvedPaywallConfiguration(
                catalog, PurchaseConfiguration(emptyList(), emptyList()), purchaseNotice = notice,
            ))
            assertNull(configuration.purchaseNoticeResource)
            assertEquals(notice, configuration.purchaseNoticeText)
        }
    }
}
