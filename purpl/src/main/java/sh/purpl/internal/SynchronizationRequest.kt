package sh.purpl.internal

import sh.purpl.ProductKind

/// 재실행 후 서버 동기화를 재시도하는 데 필요한 최소 정보
internal data class SynchronizationRequest(
    val purchaseToken: String,
    val productIdentifier: String,
    val kind: ProductKind,
    val applicationAccountIdentifier: String? = null,
    val republicationRequestIdentifier: String? = null,
)
