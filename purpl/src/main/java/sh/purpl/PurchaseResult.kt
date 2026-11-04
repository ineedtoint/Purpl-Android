package sh.purpl

/// 구매 처리 결과
public enum class PurchaseResult { Completed, Pending, Cancelled }

/// 구독 변경 시 과금과 권한 적용 시점
public enum class SubscriptionReplacementMode { WithTimeProration, ChargeProratedPrice, WithoutProration, ChargeFullPrice, Deferred }

/// 기존 구독 구매를 교체하는 요청
public data class SubscriptionReplacement(
    /// 교체할 기존 구매 또는 현재 구매 토큰
    val purchaseToken: String,
    /// 앱에서 명시한 구독 교체 방식
    val mode: SubscriptionReplacementMode,
) {
    init { require(purchaseToken.isNotBlank()) }
}

/// 앱에서 구독 교체에 사용할 현재 구매 식별 정보
public data class ActivePurchase(
    /// 교체할 기존 구매 또는 현재 구매 토큰
    val purchaseToken: String,
    /// 현재 구매에 포함된 상품 식별자
    val productIdentifiers: Set<String>,
    /// 현재 구매의 상품 유형
    val kind: ProductKind,
)
