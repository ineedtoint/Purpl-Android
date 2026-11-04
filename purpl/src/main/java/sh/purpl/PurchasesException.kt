package sh.purpl

/// 구매 실패를 구분하는 오류 종류
public enum class PurchasesErrorCode {
    Configuration, ProductUnavailable, BillingUnavailable, Network, PurchaseInProgress,
    PurchaseInvalid, PurchasePending, PurchaseNotCompleted, ServerRejected, InvalidServerResponse,
}

/// 내부 진단과 호출부의 오류 분기를 위한 구매 예외
public class PurchasesException(
    /// 호출부가 구분할 SDK 오류 종류
    val code: PurchasesErrorCode,
    message: String,
    cause: Throwable? = null,
    /// 서버가 반환한 안정적인 오류 식별자
    val serverErrorCode: String? = null,
    /// 동일 요청의 일시적인 실패 여부
    val retryable: Boolean = false,
    /// 정상적인 오류 응답에서 서버가 구매 무효·대기·미완료를 명시했는지 여부
    val purchaseRejected: Boolean = false,
) : Exception(message, cause)
