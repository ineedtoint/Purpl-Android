package sh.purpl

import java.time.Instant

/// 고객 정보의 권한 확인 출처
public enum class CustomerInfoSource { GooglePlay, Server, Mixed }

/// 스토어 구매의 실행 환경
public enum class StoreEnvironment { Production, Sandbox, Unknown }

/// 고객 권한의 현재 상태
public enum class CustomerEntitlementStatus { Active, GracePeriod, BillingRetry, Paused, Expired, Revoked }

/// 스토어가 제공한 사실을 바탕으로 해석한 이용 권한
public data class CustomerEntitlement(
    /// 앱 권한 식별자
    val identifier: String,
    /// 권한 표시 이름
    val displayName: String = identifier,
    /// 해당 권한을 제공한 스토어 상품
    val productIdentifier: String? = null,
    /// 서버에서 확인한 스토어 환경
    val environment: StoreEnvironment = StoreEnvironment.Unknown,
    /// 권한의 현재 상태
    val status: CustomerEntitlementStatus = CustomerEntitlementStatus.Active,
    /// 현재 이용 가능 여부
    val active: Boolean,
    /// 스토어에서 확인한 시작 시각
    val startsAt: Instant? = null,
    /// 스토어에서 확인한 만료 시각, 기기 조회는 알 수 없음
    val expiresAt: Instant? = null,
    /// 서버에서 확인한 회수 시각
    val revokedAt: Instant? = null,
    /// 마지막 권한 확인 시각
    val lastVerifiedAt: Instant,
    /// 서버에서 확인한 Google 기본 요금제, 기기 조회는 알 수 없음
    val basePlanIdentifier: String? = null,
    /// 서버에서 확인한 Google 혜택, 기본 요금제 또는 기기 조회는 null
    val offerIdentifier: String? = null,
)

/// 현재 스토어 계정에서 확인한 구매 전체의 권한
public data class CustomerInfo(
    /// 현재 결과의 확인 출처
    val source: CustomerInfoSource,
    /// 구매 토큰별 서버 고객 식별자 전체
    val customerIdentifiers: Set<String> = emptySet(),
    /// 여러 구매의 중복을 제거한 권한 목록
    val entitlements: List<CustomerEntitlement> = emptyList(),
) {
    /// 현재 활성인 권한 식별자 전체
    val activeEntitlementIdentifiers: Set<String> get() = entitlements.filter { it.active }.map { it.identifier }.toSet()

    /// 특정 이용 권한의 현재 활성 여부
    public fun isEntitlementActive(identifier: String): Boolean = identifier in activeEntitlementIdentifiers
}
