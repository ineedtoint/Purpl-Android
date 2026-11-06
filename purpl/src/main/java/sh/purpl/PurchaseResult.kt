package sh.purpl

// 구매 처리 결과
/** The result of a purchase. */
public enum class PurchaseResult {
    // 구매 완료와 권한 검증 완료
    /** The purchase completed and its entitlement was verified. */
    Completed,
    // 현금 결제 등 결제 완료 대기
    /** The purchase is waiting for payment to complete, such as a cash payment. */
    Pending,
    // 사용자의 구매 취소
    /** The user cancelled the purchase. */
    Cancelled,
}

// 구독 변경 시 과금과 권한 적용 시점
/** How billing and entitlement timing apply when a subscription changes. */
public enum class SubscriptionReplacementMode {
    // 즉시 변경하고 남은 기간을 비례 계산해 적용
    /** Changes the subscription immediately and credits the remaining time on a prorated basis. */
    WithTimeProration,
    // 즉시 변경하고 결제 주기를 유지하며 남은 기간의 차액 청구
    /** Changes the subscription immediately, keeps the billing cycle, and charges the prorated price difference. */
    ChargeProratedPrice,
    // 즉시 변경하고 다음 결제일부터 새 가격 청구
    /** Changes the subscription immediately and charges the new price on the next renewal date. */
    WithoutProration,
    // 즉시 변경하고 새 요금제의 전체 가격 청구
    /** Changes the subscription immediately and charges the full price of the new plan. */
    ChargeFullPrice,
    // 현재 구독이 만료될 때 변경 적용
    /** Applies the change when the current subscription expires. */
    Deferred,
}

// 기존 구독 구매를 교체하는 요청
/** A request to replace an existing subscription purchase. */
public data class SubscriptionReplacement(
    // 교체할 기존 구매 또는 현재 구매 토큰
    /** The purchase token of the existing purchase to replace. */
    val purchaseToken: String,
    // 앱에서 명시한 구독 교체 방식
    /** The replacement mode chosen by the app. */
    val mode: SubscriptionReplacementMode,
) {
    init { require(purchaseToken.isNotBlank()) }
}

// 앱에서 구독 교체에 사용할 현재 구매 식별 정보
/** A current purchase that the app can replace when changing subscriptions. */
public data class ActivePurchase(
    // 교체할 기존 구매 또는 현재 구매 토큰
    /** The purchase token of the current purchase. */
    val purchaseToken: String,
    // 현재 구매에 포함된 상품 식별자
    /** The product identifiers included in the current purchase. */
    val productIdentifiers: Set<String>,
    // 현재 구매의 상품 유형
    /** The product type of the current purchase. */
    val kind: ProductKind,
)
