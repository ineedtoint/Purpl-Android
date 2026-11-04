package sh.purpl.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.purpl.CustomerEntitlement
import sh.purpl.CustomerInfo
import sh.purpl.CustomerInfoSource
import sh.purpl.ProductKind
import sh.purpl.PurchaseOption
import sh.purpl.StorePurchaseOption

/// 서버 요금제와 기기 구매 결과의 중복 구매 판별 검증
class PaywallPurchaseOwnershipTest {
    /// 현재 월간만 보유 표시하고 연간 변경 허용
    @Test fun monthlySubscriberCanSelectYearly() {
        val customerInfo = customerInfo(basePlanIdentifier = "monthly")
        assertEquals(PaywallPurchaseOwnership.Owned, purchaseOwnership(option("monthly"), customerInfo))
        assertEquals(PaywallPurchaseOwnership.Available, purchaseOwnership(option("yearly"), customerInfo))
    }

    /// 같은 기본 요금제의 혜택을 바꿔도 중복 구매로 판별
    @Test fun offerDoesNotBypassOwnedPlan() {
        assertEquals(PaywallPurchaseOwnership.Owned, purchaseOwnership(option("monthly", "intro"), customerInfo("monthly")))
    }

    /// 기기 폴백의 상품 보유를 특정 기간 보유로 추정하지 않음
    @Test fun localPurchaseBlocksDuplicatesWithoutInventingPeriod() {
        val customerInfo = customerInfo(basePlanIdentifier = null)
        assertEquals(PaywallPurchaseOwnership.UnknownBasePlan, purchaseOwnership(option("monthly"), customerInfo))
        assertEquals(PaywallPurchaseOwnership.UnknownBasePlan, purchaseOwnership(option("yearly"), customerInfo))
        assertEquals(PaywallPurchaseOwnership.Available, purchaseOwnership(option("monthly", productIdentifier = "pro"), customerInfo))
    }

    /// 만료되거나 아직 확인하지 않은 권한은 보유 옵션으로 표시하지 않음
    @Test fun inactiveAndMissingEntitlementsDoNotOwnOption() {
        assertEquals(PaywallPurchaseOwnership.Available, purchaseOwnership(option("monthly"), customerInfo("monthly", active = false)))
        assertEquals(PaywallPurchaseOwnership.Available, purchaseOwnership(option("monthly"), null))
    }

    /// 비소모성 상품은 주기 없이 재구매 차단
    @Test fun nonConsumableOwnershipDoesNotNeedBasePlan() {
        val option = option(null).copy(kind = ProductKind.NonConsumable)
        assertEquals(PaywallPurchaseOwnership.Owned, purchaseOwnership(option, customerInfo(null)))
    }

    /// 상품·기본 요금제·혜택 조합의 스토어 옵션 생성
    private fun option(basePlanIdentifier: String?, offerIdentifier: String? = null, productIdentifier: String = "plus") = StorePurchaseOption(
        option = PurchaseOption("$productIdentifier-$basePlanIdentifier", productIdentifier, basePlanIdentifier, offerIdentifier),
        title = productIdentifier,
        description = "",
        formattedPrice = "$1",
        pricingPhases = emptyList(),
        kind = ProductKind.Subscription,
    )

    /// 활성 여부와 현재 기본 요금제가 있는 고객 정보 생성
    private fun customerInfo(basePlanIdentifier: String?, active: Boolean = true) = CustomerInfo(
        source = if (basePlanIdentifier == null) CustomerInfoSource.GooglePlay else CustomerInfoSource.Server,
        entitlements = listOf(CustomerEntitlement(
            identifier = "plus",
            productIdentifier = "plus",
            active = active,
            lastVerifiedAt = Instant.parse("2026-11-04T00:00:00Z"),
            basePlanIdentifier = basePlanIdentifier,
        )),
    )
}
