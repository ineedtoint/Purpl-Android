package sh.purpl.internal

import sh.purpl.ProductKind

/// Google Play에서 재조회한 구매 상태
internal enum class StorePurchaseState { Purchased, Pending, Unspecified }

/// 검증과 동기화에 사용하는 스토어 구매 원본
internal data class StorePurchase(
    val purchaseToken: String,
    val productIdentifiers: Set<String>,
    val packageName: String,
    val kind: ProductKind,
    val state: StorePurchaseState,
    val originalJson: String,
    val signature: String,
    val acknowledged: Boolean,
    val purchasedAtMilliseconds: Long,
    val suspended: Boolean = false,
)
