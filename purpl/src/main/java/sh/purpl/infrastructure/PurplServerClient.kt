package sh.purpl.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.purpl.*
import sh.purpl.internal.SynchronizationRequest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

/// 앱에 비밀 키를 넣지 않는 Purpl 공개 API 클라이언트
internal class PurplServerClient(
    private val configuration: PurchasesConfiguration,
    private val packageName: String,
) : PurchasesServer {
    /// 구매 토큰으로 Google 서버 검증 결과 요청 또는 미완료 복구 재시도
    override suspend fun synchronize(request: SynchronizationRequest): CustomerInfo {
        request.republicationRequestIdentifier?.let {
            return republishEntitlements(request, it).customerInfo
        }
        return decodeCustomerInfo(request("/v1/customers/google-play/synchronize", synchronizationBody(request)))
    }

    /// 별도 복구 API로 현재 권한 웹훅 재전송 접수 또는 상태 확인
    override suspend fun republishEntitlements(request: SynchronizationRequest, requestIdentifier: String): EntitlementSynchronizationResult {
        val body = synchronizationBody(request).put("provider", "google").put("requestIdentifier", requestIdentifier)
        val response = request("/v1/customers/entitlements/republish", body)
        return decodeEntitlementSynchronizationResult(response)
    }

    /// 호출 시점의 앱 계정과 검증할 구매 토큰으로 요청 구성
    private fun synchronizationBody(request: SynchronizationRequest): JSONObject {
        val body = JSONObject().put("packageName", packageName).put("purchaseToken", request.purchaseToken)
            .put("productKind", if (request.kind == ProductKind.Subscription) "subscription" else "one_time_product")
            .put("productIdentifier", request.productIdentifier)
        request.applicationAccountIdentifier?.let { body.put("applicationAccountIdentifier", it) }
        return body
    }

    /// Google Play용 공개 페이월 구성 요청
    override suspend fun paywall(identifier: String, localeIdentifier: String): ResolvedPaywallConfiguration {
        val query = mapOf("packageName" to packageName, "paywallIdentifier" to identifier, "localeIdentifier" to localeIdentifier)
            .entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
        return decodePaywall(request("/v1/paywalls/google-play?$query"))
    }

    /// 제한된 연결 시간으로 공개 API 호출
    private suspend fun request(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(configuration.serverBaseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            decodeServerResponse(status, content)
        } catch (error: IOException) {
            throw PurchasesException(PurchasesErrorCode.Network, "Purpl 서버에 연결하지 못했습니다.", error, retryable = true)
        } finally { connection.disconnect() }
    }
}

/// HTTP 상태와 JSON 본문을 분리하여 비정형 서버 오류도 재시도 대상으로 분류
internal fun decodeServerResponse(status: Int, content: String): JSONObject {
    val response = try { JSONObject(content) } catch (error: Exception) {
        if (status !in 200..299) throw serverException(status, "INVALID_RESPONSE")
        throw PurchasesException(PurchasesErrorCode.InvalidServerResponse, "Purpl 응답 형식이 올바르지 않습니다.", error, retryable = true)
    }
    if (status !in 200..299) throw serverException(status, response.optString("error"))
    return response
}

/// 서버 오류의 종류와 재시도 여부 분류
internal fun serverException(status: Int, serverCode: String): PurchasesException {
    val retryable = status >= 500 || status == 408 || status == 429
    val code = when (serverCode) {
        "GOOGLE_PLAY_PURCHASE_PENDING" -> PurchasesErrorCode.PurchasePending
        "GOOGLE_PLAY_PURCHASE_NOT_COMPLETED" -> PurchasesErrorCode.PurchaseNotCompleted
        "GOOGLE_PLAY_PURCHASE_INVALID", "GOOGLE_PLAY_PRODUCT_MISMATCH" -> PurchasesErrorCode.PurchaseInvalid
        else -> if (retryable) PurchasesErrorCode.Network else PurchasesErrorCode.ServerRejected
    }
    val purchaseRejected = status in 400..499 && code in setOf(PurchasesErrorCode.PurchaseInvalid, PurchasesErrorCode.PurchasePending, PurchasesErrorCode.PurchaseNotCompleted)
    return PurchasesException(code, "Purpl 구매 확인 실패: $serverCode ($status)", serverErrorCode = serverCode, retryable = retryable, purchaseRejected = purchaseRejected)
}

/// 고객 권한과 웹훅 전달 상태를 구분하는 응답 해석
internal fun decodeEntitlementSynchronizationResult(response: JSONObject): EntitlementSynchronizationResult = decodeResponse {
        val republication = response.getJSONObject("republication")
        val status = when (republication.getString("status")) {
            "queued" -> EntitlementRepublicationStatus.Queued
            "delivered" -> EntitlementRepublicationStatus.Delivered
            "failed" -> EntitlementRepublicationStatus.Failed
            "not_required" -> EntitlementRepublicationStatus.NotRequired
            else -> throw PurchasesException(PurchasesErrorCode.InvalidServerResponse, "권한 재전송 상태가 올바르지 않습니다.")
        }
        EntitlementSynchronizationResult(decodeCustomerInfo(response), listOf(EntitlementRepublication(
            republication.getString("requestIdentifier"), status, republication.getInt("deliveryCount"))))
}

/// 서버 고객 응답의 엄격한 권한 해석
internal fun decodeCustomerInfo(response: JSONObject): CustomerInfo = decodeResponse {
    val entitlements = response.getJSONArray("entitlements").objects().map { item ->
        CustomerEntitlement(
            identifier = item.getString("identifier"), displayName = item.getString("displayName"),
            productIdentifier = item.optionalString("productIdentifier"),
            environment = when (item.getString("environment")) { "production" -> StoreEnvironment.Production; "sandbox" -> StoreEnvironment.Sandbox; else -> StoreEnvironment.Unknown },
            status = when (item.getString("status")) {
                "active" -> CustomerEntitlementStatus.Active; "grace_period" -> CustomerEntitlementStatus.GracePeriod
                "billing_retry" -> CustomerEntitlementStatus.BillingRetry; "paused" -> CustomerEntitlementStatus.Paused
                "expired" -> CustomerEntitlementStatus.Expired; "revoked" -> CustomerEntitlementStatus.Revoked
                else -> throw IllegalArgumentException("알 수 없는 권한 상태")
            },
            active = item.getBoolean("active"), startsAt = item.optionalInstant("startsAt"),
            expiresAt = item.optionalInstant("expiresAt"), revokedAt = item.optionalInstant("revokedAt"),
            lastVerifiedAt = Instant.parse(item.getString("lastVerifiedAt")),
            basePlanIdentifier = item.optionalString("basePlanIdentifier"),
            offerIdentifier = item.optionalString("offerIdentifier"),
        )
    }
    CustomerInfo(CustomerInfoSource.Server, setOfNotNull(response.optionalString("customerIdentifier")), entitlements)
}

/// 서버 페이월 응답의 상품과 구매 옵션 해석
internal fun decodePaywall(response: JSONObject): ResolvedPaywallConfiguration = decodeResponse {
    val configuration = response.getJSONObject("purchaseConfiguration")
    val catalog = response.getJSONObject("catalog")
    val paywall = response.getJSONObject("paywallConfiguration")
    val localization = response.optJSONObject("localization")
    val policy = response.optJSONObject("policy")
    val options = catalog.getJSONArray("purchaseOptions").objects().map { item ->
        PurchaseOption(item.getString("identifier"), item.getString("productIdentifier"), item.optionalString("basePlanIdentifier"), item.optionalString("offerIdentifier"))
    }
    ResolvedPaywallConfiguration(
        catalog = PurchaseCatalog(catalog.getString("identifier"), options),
        purchaseConfiguration = PurchaseConfiguration(
            configuration.getJSONArray("entitlements").objects().map { PurchaseEntitlement(it.getString("identifier")) },
            configuration.getJSONArray("products").objects().map { PurchaseProduct(it.getString("productIdentifier"), it.getJSONArray("entitlementIdentifiers").strings().toSet(), if (it.optionalString("productKind") == "one_time_product") ProductKind.NonConsumable else ProductKind.Subscription) },
        ),
        defaultPurchaseOptionIdentifier = paywall.optionalString("defaultPurchaseOptionIdentifier"),
        productDisplayContents = localization?.optJSONArray("products")?.objects().orEmpty().map { PaywallProductDisplayContent(it.getString("productIdentifier"), it.optionalString("title"), it.optionalString("description")) },
        purchaseOptionDisplayContents = localization?.optJSONArray("purchaseOptions")?.objects().orEmpty().map {
            PaywallPurchaseOptionDisplayContent(it.getString("purchaseOptionIdentifier"), it.optionalString("title"), it.optionalString("description"))
        },
        purchaseNotice = localization?.optionalString("purchaseNotice"),
        privacyPolicyUrl = policy?.optionalString("privacyPolicyURL"), termsOfServiceUrl = policy?.optionalString("termsOfServiceURL"),
    )
}

/// 페이월 캐시의 손실 없는 JSON 표현
internal fun encodePaywall(value: ResolvedPaywallConfiguration): String = JSONObject().apply {
    put("purchaseConfiguration", JSONObject().put("entitlements", JSONArray(value.purchaseConfiguration.entitlements.map { JSONObject().put("identifier", it.identifier) }))
        .put("products", JSONArray(value.purchaseConfiguration.products.map { JSONObject().put("productIdentifier", it.productIdentifier).put("entitlementIdentifiers", JSONArray(it.entitlementIdentifiers.toList())).put("productKind", if (it.kind == ProductKind.Subscription) "subscription" else "one_time_product") })))
    put("catalog", JSONObject().put("identifier", value.catalog.identifier).put("purchaseOptions", JSONArray(value.catalog.options.map { JSONObject().put("identifier", it.identifier).put("productIdentifier", it.productIdentifier).put("basePlanIdentifier", it.basePlanIdentifier).put("offerIdentifier", it.offerIdentifier) })))
    put("paywallConfiguration", JSONObject().put("defaultPurchaseOptionIdentifier", value.defaultPurchaseOptionIdentifier))
    put("localization", JSONObject().put("purchaseNotice", value.purchaseNotice)
        .put("products", JSONArray(value.productDisplayContents.map { JSONObject().put("productIdentifier", it.productIdentifier).put("title", it.title).put("description", it.description) }))
        .put("purchaseOptions", JSONArray(value.purchaseOptionDisplayContents.map { JSONObject().put("purchaseOptionIdentifier", it.purchaseOptionIdentifier).put("title", it.title).put("description", it.description) })))
    put("policy", JSONObject().put("privacyPolicyURL", value.privacyPolicyUrl).put("termsOfServiceURL", value.termsOfServiceUrl))
}.toString()

/// 누락값과 JSON null을 구분하지 않는 선택 문자열 조회
private fun JSONObject.optionalString(name: String): String? = if (isNull(name)) null else getString(name)
/// 선택 ISO 시각 해석
private fun JSONObject.optionalInstant(name: String): Instant? = optionalString(name)?.let(Instant::parse)
/// JSON 객체 배열 변환
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
/// JSON 문자열 배열 변환
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
/// 응답 해석 오류의 공통 분류
private inline fun <T> decodeResponse(block: () -> T): T = try { block() } catch (error: PurchasesException) { throw error } catch (error: Exception) {
    throw PurchasesException(PurchasesErrorCode.InvalidServerResponse, "Purpl 응답을 해석하지 못했습니다.", error, retryable = true)
}
