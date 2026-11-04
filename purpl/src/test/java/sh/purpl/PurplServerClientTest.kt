package sh.purpl

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import sh.purpl.infrastructure.*
import java.time.Instant

/// 서버 응답 해석과 원격 구성 캐시의 계약 테스트
class PurplServerClientTest {
    /// Google 오류와 Purpl 자체 장애를 모두 재시도 대상으로 분류
    @Test fun serverFailuresRemainRetryableRegardlessOfErrorCode() {
        assertTrue(serverException(503, "GOOGLE_PLAY_API_REQUEST_FAILED").retryable)
        assertTrue(serverException(500, "INTERNAL_SERVER_ERROR").retryable)
        assertTrue(serverException(502, "GOOGLE_PLAY_CONFIGURATION_ERROR").retryable)
        assertTrue(serverException(502, "GOOGLE_PLAY_API_RESPONSE_INVALID").retryable)
        assertFalse(serverException(409, "GOOGLE_PLAY_PURCHASE_PENDING").retryable)
        assertTrue(serverException(409, "GOOGLE_PLAY_PURCHASE_PENDING").purchaseRejected)
        assertTrue(serverException(400, "GOOGLE_PLAY_PURCHASE_INVALID").purchaseRejected)
        assertFalse(serverException(502, "GOOGLE_PLAY_CONFIGURATION_ERROR").purchaseRejected)
        assertFalse(serverException(500, "GOOGLE_PLAY_PURCHASE_INVALID").purchaseRejected)
        assertTrue(serverException(503, "UNKNOWN_ERROR").retryable)
    }

    /// Cloud Run HTML 오류와 잘못된 성공 응답도 오류로 전달
    @Test fun nonJsonServerResponsesRemainRetryable() {
        val cloudRunFailure = assertThrows(PurchasesException::class.java) { decodeServerResponse(503, "<html>Service Unavailable</html>") }
        assertTrue(cloudRunFailure.retryable)
        val invalidResponse = assertThrows(PurchasesException::class.java) { decodeServerResponse(200, "invalid json") }
        assertEquals(PurchasesErrorCode.InvalidServerResponse, invalidResponse.code)
        assertTrue(invalidResponse.retryable)
    }

    /// 현재 요금제와 혜택 보존 및 이전 응답과의 호환
    @Test fun currentBasePlanAndOfferAreOptionalServerFacts() {
        val response = JSONObject("""{"customerIdentifier":"customer","entitlements":[{"identifier":"pro","displayName":"Pro","productIdentifier":"pro","environment":"production","status":"active","active":true,"startsAt":null,"expiresAt":null,"revokedAt":null,"lastVerifiedAt":"2026-01-01T00:00:00Z","basePlanIdentifier":"yearly","offerIdentifier":"welcome"}]}""")
        val entitlement = decodeCustomerInfo(response).entitlements.single()
        assertEquals("yearly", entitlement.basePlanIdentifier)
        assertEquals("welcome", entitlement.offerIdentifier)
        response.getJSONArray("entitlements").getJSONObject(0).apply { remove("basePlanIdentifier"); put("offerIdentifier", JSONObject.NULL) }
        val previousContract = decodeCustomerInfo(response).entitlements.single()
        assertNull(previousContract.basePlanIdentifier)
        assertNull(previousContract.offerIdentifier)
    }

    /// 서버가 알 수 없는 권한 상태를 보내면 활성으로 추측하지 않음
    @Test fun unknownServerStatusIsRejected() {
        val response = JSONObject("""{"customerIdentifier":"customer","entitlements":[{"identifier":"pro","displayName":"Pro","productIdentifier":"pro","environment":"production","status":"unknown","active":true,"startsAt":null,"expiresAt":null,"revokedAt":null,"lastVerifiedAt":"2026-01-01T00:00:00Z"}]}""")
        assertThrows(PurchasesException::class.java) { decodeCustomerInfo(response) }
    }

    /// 서버 만료일과 비활성 상태를 그대로 보존
    @Test fun expiryAndInactiveServerFactsArePreserved() {
        val response = JSONObject("""{"customerIdentifier":"customer","entitlements":[{"identifier":"pro","displayName":"Pro","productIdentifier":"pro","environment":"production","status":"expired","active":false,"startsAt":null,"expiresAt":"2026-01-01T00:00:00Z","revokedAt":null,"lastVerifiedAt":"2026-01-02T00:00:00Z"}]}""")
        val information = decodeCustomerInfo(response)
        assertFalse(information.isEntitlementActive("pro"))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), information.entitlements.single().expiresAt)
    }

    /// 비소모성 상품과 빈 권한 매핑 및 월간·연간 옵션을 캐시에 보존
    @Test fun paywallCacheRoundTripPreservesKindsAndDistinctOptions() {
        val configuration = ResolvedPaywallConfiguration(
            PurchaseCatalog("main", listOf(PurchaseOption("pro.monthly", "pro", "monthly"), PurchaseOption("pro.yearly", "pro", "yearly"), PurchaseOption("lifetime", "lifetime"))),
            PurchaseConfiguration(listOf(PurchaseEntitlement("pro")), listOf(PurchaseProduct("pro", setOf("pro")), PurchaseProduct("lifetime", emptySet(), ProductKind.NonConsumable))),
            defaultPurchaseOptionIdentifier = "pro.yearly", privacyPolicyUrl = "https://example.com/privacy", termsOfServiceUrl = "https://example.com/terms",
        )
        assertEquals(configuration, decodePaywall(JSONObject(encodePaywall(configuration))))
    }

    /// 상품 기본값과 옵션 순서에 관계없이 명시한 연간 옵션 유지
    @Test fun explicitDefaultOptionDoesNotDependOnLegacyProductOrOptionOrder() {
        val configuration = paywallConfiguration()
        for (options in listOf(configuration.catalog.options, configuration.catalog.options.reversed())) {
            val response = JSONObject(encodePaywall(configuration.copy(catalog = configuration.catalog.copy(options = options))))
            response.getJSONObject("paywallConfiguration").put("defaultProductIdentifier", "lifetime")
            assertEquals("pro.yearly", decodePaywall(response).defaultPurchaseOptionIdentifier)
        }
    }

    /// 기본 옵션이 없으면 이전 상품 기본값에서 월간·연간을 추정하지 않음
    @Test fun missingDefaultOptionDoesNotInferBasePlanFromLegacyProduct() {
        val configuration = paywallConfiguration().copy(defaultPurchaseOptionIdentifier = null)
        for (options in listOf(configuration.catalog.options, configuration.catalog.options.reversed())) {
            val response = JSONObject(encodePaywall(configuration.copy(catalog = configuration.catalog.copy(options = options))))
            val paywall = response.getJSONObject("paywallConfiguration").put("defaultProductIdentifier", "pro")
            paywall.put("defaultPurchaseOptionIdentifier", JSONObject.NULL)
            assertNull(decodePaywall(response).defaultPurchaseOptionIdentifier)
            paywall.remove("defaultPurchaseOptionIdentifier")
            val decoded = decodePaywall(response)
            assertNull(decoded.defaultPurchaseOptionIdentifier)
            assertEquals(options, decoded.catalog.options)
        }
    }

    /// 같은 상품의 월간·연간과 별도 비소모성 상품이 있는 원격 페이월 구성
    private fun paywallConfiguration() = ResolvedPaywallConfiguration(
        PurchaseCatalog("main", listOf(PurchaseOption("pro.monthly", "pro", "monthly"), PurchaseOption("pro.yearly", "pro", "yearly"), PurchaseOption("lifetime", "lifetime"))),
        PurchaseConfiguration(listOf(PurchaseEntitlement("pro")), listOf(PurchaseProduct("pro", setOf("pro")), PurchaseProduct("lifetime", setOf("pro"), ProductKind.NonConsumable))),
        defaultPurchaseOptionIdentifier = "pro.yearly",
    )
}
