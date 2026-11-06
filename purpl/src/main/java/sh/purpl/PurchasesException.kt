package sh.purpl

// 구매 실패를 구분하는 오류 종류
/** The kinds of errors that distinguish purchase failures. */
public enum class PurchasesErrorCode {
    // SDK 구성이 없거나 올바르지 않음
    /** The SDK configuration is missing or invalid. */
    Configuration,
    // 구매할 수 있는 상품이 없음
    /** The product is not available for purchase. */
    ProductUnavailable,
    // Google Play 결제를 사용할 수 없음
    /** Google Play Billing is unavailable. */
    BillingUnavailable,
    // 다시 시도할 수 있는 네트워크 또는 서비스 오류
    /** A network or service error occurred that may succeed when retried. */
    Network,
    // 이미 다른 구매가 진행 중
    /** Another purchase is already in progress. */
    PurchaseInProgress,
    // 구매 검증 실패
    /** The purchase failed verification. */
    PurchaseInvalid,
    // 아직 권한을 부여할 수 없는 대기 구매
    /** The purchase is pending and does not grant entitlements yet. */
    PurchasePending,
    // 완료되지 않았거나 권한이 아직 확인되지 않은 구매
    /** The purchase did not complete, or its entitlement has not been verified yet. */
    PurchaseNotCompleted,
    // Purpl 서버가 요청을 거부함
    /** The Purpl server rejected the request. */
    ServerRejected,
    // Purpl 서버 응답을 해석할 수 없음
    /** The Purpl server returned a response that could not be decoded. */
    InvalidServerResponse,
}

// 내부 진단과 호출부의 오류 분기를 위한 구매 예외
/** An error thrown by Purpl purchase and entitlement operations. */
public class PurchasesException(
    // 호출부가 구분할 SDK 오류 종류
    /** The kind of SDK error for the caller to handle. */
    val code: PurchasesErrorCode,
    message: String,
    cause: Throwable? = null,
    // 서버가 반환한 안정적인 오류 식별자
    /** The stable error identifier returned by the server. */
    val serverErrorCode: String? = null,
    // 동일 요청의 일시적인 실패 여부
    /** Whether retrying the same request may succeed. */
    val retryable: Boolean = false,
    // 정상적인 오류 응답에서 서버가 구매 무효·대기·미완료를 명시했는지 여부
    /** Whether the server explicitly reported the purchase as invalid, pending, or not completed. */
    val purchaseRejected: Boolean = false,
) : Exception(message, cause)
