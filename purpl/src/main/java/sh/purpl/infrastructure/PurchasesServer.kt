package sh.purpl.infrastructure

import sh.purpl.EntitlementSynchronizationResult
import sh.purpl.CustomerInfo
import sh.purpl.ResolvedPaywallConfiguration
import sh.purpl.internal.SynchronizationRequest

/// 기기 구매를 확인하는 Purpl 서버 경계
internal interface PurchasesServer {
    /// 로그인한 앱 계정의 관리자 부여 권한 조회
    suspend fun identifyAccount(identifier: String, email: String?): CustomerInfo

    /// 구매 토큰의 최신 권한 조회
    suspend fun synchronize(request: SynchronizationRequest): CustomerInfo
    /// 검증된 구매 고객의 현재 권한 웹훅 재전송
    suspend fun republishEntitlements(request: SynchronizationRequest, requestIdentifier: String): EntitlementSynchronizationResult
    /// 원격 페이월 구성 조회
    suspend fun paywall(identifier: String, localeIdentifier: String): ResolvedPaywallConfiguration
}
