package sh.purpl

/// 현재 권한 웹훅 전달 상태
public enum class EntitlementRepublicationStatus { Queued, Delivered, Failed, NotRequired }

/// 같은 요청 식별자로 재확인할 수 있는 웹훅 전달 결과
public data class EntitlementRepublication(
    /// 원래 또는 진행 중 요청으로 병합된 복구 식별자
    val requestIdentifier: String,
    /// 전송 접수와 수신 완료를 구분하는 상태
    val status: EntitlementRepublicationStatus,
    /// 생성된 전달 작업 수
    val deliveryCount: Int,
)

/// 현재 고객 정보와 구매 고객별 웹훅 재전송 결과
public data class EntitlementSynchronizationResult(
    /// 검증된 최신 고객 정보
    val customerInfo: CustomerInfo,
    /// 스토어 증빙으로 확인한 고객별 결과, 서버 미사용 또는 구매 증빙이 없으면 빈 목록
    val republications: List<EntitlementRepublication>,
)
