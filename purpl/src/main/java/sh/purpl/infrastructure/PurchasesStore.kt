package sh.purpl.infrastructure

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import sh.purpl.*
import sh.purpl.internal.StorePurchase

/// Google Play 연결과 스토어 작업의 경계
internal interface PurchasesStore {
    val updates: Flow<Unit>
    /// 현재 계정의 구독과 일회성 보유 구매 조회
    suspend fun queryPurchases(): List<StorePurchase>
    /// 현재 구매 가능한 옵션과 가격 조회
    suspend fun products(options: List<PurchaseOption>, configuration: PurchaseConfiguration?): List<StorePurchaseOption>
    /// 스토어 결제창 실행과 구매 결과 수신
    suspend fun purchase(activity: Activity, option: StorePurchaseOption, replacement: SubscriptionReplacement?, accountIdentifier: String?): PurchaseResult
    /// 사용자에게 혜택을 제공한 구매의 확인 처리
    suspend fun acknowledge(purchaseToken: String)
    /// 스토어 연결 종료
    fun close()
}
