package sh.purpl.ui

import sh.purpl.CustomerInfo
import sh.purpl.ProductKind
import sh.purpl.StorePurchaseOption

/// 선택한 옵션의 보유 여부와 기기 폴백의 주기 확인 한계
internal enum class PaywallPurchaseOwnership { Available, Owned, UnknownBasePlan }

/// 활성 권한의 상품과 서버가 확인한 기본 요금제로 중복 구매 판별
internal fun purchaseOwnership(option: StorePurchaseOption, customerInfo: CustomerInfo?): PaywallPurchaseOwnership {
    val entitlements = customerInfo?.entitlements.orEmpty().filter {
        it.active && it.productIdentifier == option.option.productIdentifier
    }
    if (entitlements.isEmpty()) return PaywallPurchaseOwnership.Available
    if (option.kind != ProductKind.Subscription) return PaywallPurchaseOwnership.Owned
    if (entitlements.any { it.basePlanIdentifier != null && it.basePlanIdentifier == option.option.basePlanIdentifier }) {
        return PaywallPurchaseOwnership.Owned
    }
    // 기기 구매에는 기본 요금제가 없으므로 특정 월간·연간 옵션을 보유했다고 표시하지 않는다.
    if (entitlements.any { it.basePlanIdentifier == null }) return PaywallPurchaseOwnership.UnknownBasePlan
    return PaywallPurchaseOwnership.Available
}
