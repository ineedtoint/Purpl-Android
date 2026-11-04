package sh.purpl.infrastructure

import sh.purpl.CustomerInfo
import sh.purpl.ResolvedPaywallConfiguration
import sh.purpl.internal.SynchronizationRequest

/// 기기 구매를 확인하는 Purpl 서버 경계
internal interface PurchasesServer {
    /// 구매 토큰의 최신 권한 조회
    suspend fun synchronize(request: SynchronizationRequest): CustomerInfo
    /// 원격 페이월 구성 조회
    suspend fun paywall(identifier: String, localeIdentifier: String): ResolvedPaywallConfiguration
}
