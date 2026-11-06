package sh.purpl

import java.time.Instant

// 고객 정보의 권한 확인 출처
/** The source used to resolve customer information. */
public enum class CustomerInfoSource {
    // 기기에서 검증한 Google Play 구매
    /** Google Play purchases verified on the device. */
    GooglePlay,
    // Purpl 서버
    /** The Purpl server. */
    Server,
    // 서버와 기기에서 검증한 결과의 조합
    /** A combination of results from the Purpl server and the device. */
    Mixed,
}

// 스토어 구매의 실행 환경
/** The store environment associated with a purchase or entitlement. */
public enum class StoreEnvironment {
    // 프로덕션 환경
    /** The production environment. */
    Production,
    // 라이선스 테스터 등의 테스트 환경
    /** The test environment, such as purchases made by license testers. */
    Sandbox,
    // 기기 검증 등으로 확인할 수 없는 환경
    /** The environment could not be determined, such as when resolved on the device. */
    Unknown,
}

// 고객 권한의 현재 상태
/** The current status of a customer entitlement. */
public enum class CustomerEntitlementStatus {
    // 이용 가능한 권한
    /** The entitlement is available for use. */
    Active,
    // 결제 유예 기간
    /** The entitlement is in a billing grace period. */
    GracePeriod,
    // 결제 재시도 기간
    /** The entitlement is in a billing retry period. */
    BillingRetry,
    // 일시 중지된 권한
    /** The entitlement is paused. */
    Paused,
    // 만료된 권한
    /** The entitlement has expired. */
    Expired,
    // 회수된 권한
    /** The entitlement has been revoked. */
    Revoked,
}

// 스토어가 제공한 사실을 바탕으로 해석한 이용 권한
/** An entitlement resolved from facts provided by the store. */
public data class CustomerEntitlement(
    // 앱 권한 식별자
    /** The entitlement identifier defined by the app. */
    val identifier: String,
    // 권한 표시 이름
    /** The entitlement display name. */
    val displayName: String = identifier,
    // 해당 권한을 제공한 스토어 상품
    /** The store product identifier that granted the entitlement. */
    val productIdentifier: String? = null,
    // 서버에서 확인한 스토어 환경
    /** The store environment verified by the server. */
    val environment: StoreEnvironment = StoreEnvironment.Unknown,
    // 권한의 현재 상태
    /** The current entitlement status. */
    val status: CustomerEntitlementStatus = CustomerEntitlementStatus.Active,
    // 현재 이용 가능 여부
    /** Whether the entitlement is currently available. */
    val active: Boolean,
    // 스토어에서 확인한 시작 시각
    /** The time when the entitlement started, as reported by the store. */
    val startsAt: Instant? = null,
    // 스토어에서 확인한 만료 시각, 기기 조회는 알 수 없음
    /** The time when the entitlement expires, as reported by the store. Always `null` when resolved on the device. */
    val expiresAt: Instant? = null,
    // 서버에서 확인한 회수 시각
    /** The time when the entitlement was revoked, as verified by the server. */
    val revokedAt: Instant? = null,
    // 마지막 권한 확인 시각
    /** The time when the entitlement was last verified. */
    val lastVerifiedAt: Instant,
    // 서버에서 확인한 Google 기본 요금제, 기기 조회는 알 수 없음
    /** The Google Play base plan identifier verified by the server. Always `null` when resolved on the device. */
    val basePlanIdentifier: String? = null,
    // 서버에서 확인한 Google 혜택, 기본 요금제 또는 기기 조회는 null
    /** The Google Play offer identifier verified by the server. `null` for a base plan without an offer or when resolved on the device. */
    val offerIdentifier: String? = null,
)

// 현재 스토어 계정에서 확인한 구매 전체의 권한
/** Entitlements resolved from every purchase of the current store account. */
public data class CustomerInfo(
    // 현재 결과의 확인 출처
    /** The source used to resolve this result. */
    val source: CustomerInfoSource,
    // 구매 토큰별 서버 고객 식별자 전체
    /** The Purpl customer identifiers resolved for each purchase token. */
    val customerIdentifiers: Set<String> = emptySet(),
    // 여러 구매의 중복을 제거한 권한 목록
    /** The entitlements across all purchases, without duplicates. */
    val entitlements: List<CustomerEntitlement> = emptyList(),
) {
    // 현재 활성인 권한 식별자 전체
    /** The identifiers of entitlements that are currently active. */
    val activeEntitlementIdentifiers: Set<String> get() = entitlements.filter { it.active }.map { it.identifier }.toSet()

    // 특정 이용 권한의 현재 활성 여부
    /**
     * Returns whether the specified entitlement is currently active.
     *
     * @param identifier The entitlement identifier to check.
     */
    public fun isEntitlementActive(identifier: String): Boolean = identifier in activeEntitlementIdentifiers
}
