package sh.purpl.ui

import org.junit.Assert.*
import org.junit.Test
import sh.purpl.PurchaseCatalog
import sh.purpl.PurchaseConfiguration
import sh.purpl.PurchaseOption
import sh.purpl.ResolvedPaywallConfiguration
import sh.purpl.PaywallProductDisplayContent
import sh.purpl.PaywallPurchaseOptionDisplayContent
import sh.purpl.StorePurchaseOption
import sh.purpl.ProductKind

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

    /// 기본 요금제는 카드별 문구를 사용하고 누락되면 스토어 제목 사용
    @Test fun basePlanCardsUseTheirOwnDisplayContents() {
        val monthly = PurchaseOption("plus:monthly", "plus", "monthly")
        val yearly = PurchaseOption("plus:yearly", "plus", "yearly")
        val configuration = PaywallConfiguration.from(ResolvedPaywallConfiguration(
            PurchaseCatalog("main", listOf(monthly, yearly)), PurchaseConfiguration(emptyList(), emptyList()),
            productDisplayContents = listOf(PaywallProductDisplayContent("plus", "이전 상품 제목", "이전 설명")),
            purchaseOptionDisplayContents = listOf(PaywallPurchaseOptionDisplayContent(monthly.identifier, "월간 카드", "월간 설명")),
        ))
        assertEquals("월간 카드", configuration.displayContent(storeOption(monthly)).title)
        assertEquals("월간 설명", configuration.displayContent(storeOption(monthly)).description)
        assertEquals("스토어 제목", configuration.displayContent(storeOption(yearly)).title)
        assertNull(configuration.displayContent(storeOption(yearly)).description)
    }

    /// 기본 요금제 없는 카드는 Google이 구독 요금제를 채워도 기존 상품 문구 사용
    @Test fun productCardsKeepProductContentAfterStoreResolution() {
        val product = PurchaseOption("plus", "plus")
        val configuration = PaywallConfiguration(
            PurchaseCatalog("main", listOf(product)),
            productDisplayContents = listOf(PaywallProductDisplayContent("plus", "상품 제목", "상품 설명")),
        )
        val resolvedOption = storeOption(product.copy(basePlanIdentifier = "monthly"))
        assertEquals("상품 제목", configuration.displayContent(resolvedOption).title)
        assertEquals("상품 설명", configuration.displayContent(resolvedOption).description)
    }

    /// 카드 표시 문구 검증용 스토어 조회 결과
    private fun storeOption(option: PurchaseOption) = StorePurchaseOption(
        option, "스토어 제목", "스토어 설명", "$1", emptyList(), ProductKind.Subscription,
    )
}
