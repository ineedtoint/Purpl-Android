package sh.purpl

import android.app.Activity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import sh.purpl.infrastructure.*
import sh.purpl.internal.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

/// 서명 검증과 권한·서버 동기화·확인 처리 경계의 회귀 테스트
class PurchasesCoordinatorTest {
    private val signingKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val encodedPublicKey = Base64.getEncoder().encodeToString(signingKey.public.encoded)
    private val mapping = PurchaseConfiguration(listOf(PurchaseEntitlement("plus"), PurchaseEntitlement("pro")), listOf(
        PurchaseProduct("plus", setOf("plus")), PurchaseProduct("pro", setOf("plus", "pro")), PurchaseProduct("retired", setOf("plus"))))

    /// 서명·패키지·상품·토큰·완료 상태를 모두 검증
    @Test fun signatureChecksSignedIdentityAndTampering() {
        val verifier = PurchaseSignatureVerifier(encodedPublicKey)
        val purchase = purchase("pro")
        verifier.verify(purchase, "test.application", setOf("pro"))
        assertThrows(PurchasesException::class.java) { verifier.verify(purchase.copy(originalJson = purchase.originalJson.replace("test.application", "other.application")), "other.application", setOf("pro")) }
        assertThrows(PurchasesException::class.java) { verifier.verify(purchase.copy(purchaseToken = "different"), "test.application", setOf("pro")) }
        assertThrows(PurchasesException::class.java) { verifier.verify(purchase, "other.application", setOf("pro")) }
        assertThrows(PurchasesException::class.java) { verifier.verify(purchase, "test.application", setOf("unknown")) }
        assertThrows(PurchasesException::class.java) { verifier.verify(purchase.copy(state = StorePurchaseState.Pending), "test.application", setOf("pro")) }
    }

    /// 판매 종료 상품의 권한 유지와 한 상품의 여러 권한 제공
    @Test fun localMappingIncludesRetiredProductsWithoutInventedExpiry() = runTest {
        val store = FakeStore(listOf(purchase("pro"), purchase("retired")))
        val coordinator = coordinator(store, FakeServer(), MemoryPersistence(), EntitlementMode.GooglePlay)
        val result = coordinator.refresh()
        assertEquals(setOf("plus", "pro"), result.activeEntitlementIdentifiers)
        assertEquals(2, result.entitlements.size)
        assertTrue(result.entitlements.all { it.expiresAt == null && it.environment == StoreEnvironment.Unknown && it.basePlanIdentifier == null && it.offerIdentifier == null })
        assertEquals(setOf("token-pro", "token-retired"), store.acknowledged.toSet())
    }

    /// 결제 대기에는 서버 검증·권한·확인 처리를 하지 않음
    @Test fun pendingPurchaseNeverGrantsSynchronizesOrAcknowledges() = runTest {
        val store = FakeStore(listOf(purchase("pro").copy(state = StorePurchaseState.Pending)))
        val server = FakeServer()
        val result = coordinator(store, server, MemoryPersistence()).refresh()
        assertTrue(result.activeEntitlementIdentifiers.isEmpty())
        assertTrue(server.requests.isEmpty())
        assertTrue(store.acknowledged.isEmpty())
    }

    /// 서버 비활성 결과는 기기 서명이 정상이어도 폴백하지 않음
    @Test fun authoritativeInactiveResultNeverFallsBackOrAcknowledges() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer().apply { response = information("pro", false) }
        val persistence = MemoryPersistence().apply { pendingAcknowledgements = setOf("token-pro") }
        val result = coordinator(store, server, persistence).refresh()
        assertTrue(result.activeEntitlementIdentifiers.isEmpty())
        assertEquals(CustomerInfoSource.Server, result.source)
        assertTrue(store.acknowledged.isEmpty())
        assertTrue(persistence.pendingAcknowledgements.isEmpty())
    }

    /// 서버 응답 실패는 정상 서명으로 확인한 현재 완료 구매로 폴백
    @Test fun serverFailuresFallBackWithoutBlockingCurrentValidPurchases() = runTest {
        val failures = listOf(
            networkFailure(), serverException(500, "INTERNAL_SERVER_ERROR"),
            serverException(502, "GOOGLE_PLAY_CONFIGURATION_ERROR"), serverException(502, "GOOGLE_PLAY_API_RESPONSE_INVALID"),
            runCatching { decodeServerResponse(503, "<html>Service Unavailable</html>") }.exceptionOrNull()!!,
            runCatching { decodeCustomerInfo(JSONObject("{}")) }.exceptionOrNull()!!,
            PurchasesException(PurchasesErrorCode.Configuration, "서버 설정이 없습니다."),
        )
        for (failure in failures) {
            val store = FakeStore(listOf(purchase("pro")))
            val server = FakeServer().apply { this.failure = failure }
            val persistence = MemoryPersistence()
            val result = coordinator(store, server, persistence).refresh()
            assertEquals(CustomerInfoSource.GooglePlay, result.source)
            assertTrue(result.isEntitlementActive("pro"))
            assertEquals(listOf("token-pro"), store.acknowledged)
            assertEquals(1, persistence.pendingSynchronizations.size)
            assertTrue(persistence.serverInactivePurchaseTokens.isEmpty())
        }
    }

    /// 취소는 로컬 폴백이나 확인 처리로 바꾸지 않음
    @Test fun synchronizationCancellationNeverFallsBack() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer().apply { failure = CancellationException("요청 취소") }
        val result = runCatching { coordinator(store, server, MemoryPersistence()).refresh() }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(store.acknowledged.isEmpty())
    }

    /// 폴백으로 확인 처리한 구매도 다음 실행에서 서버 동기화를 재시도
    @Test fun fallbackAcknowledgementDoesNotDiscardServerRetryAfterRestart() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer().apply { failure = networkFailure() }
        val persistence = MemoryPersistence()
        assertEquals(CustomerInfoSource.GooglePlay, coordinator(store, server, persistence).refresh().source)
        assertEquals(1, persistence.pendingSynchronizations.size)
        assertTrue(persistence.pendingAcknowledgements.isEmpty())
        assertTrue(store.purchases.single().acknowledged)
        server.failure = null
        val result = coordinator(store, server, persistence).refresh()
        assertEquals(CustomerInfoSource.Server, result.source)
        assertTrue(persistence.pendingSynchronizations.isEmpty())
        assertEquals(1, store.acknowledged.size)
        assertEquals(2, server.requests.size)
    }

    /// 서명 검증 실패는 서버 장애 폴백에서도 권한을 부여하지 않음
    @Test fun fallbackStillRequiresValidSignature() = runTest {
        for (pendingTokens in listOf(emptySet(), setOf("token-pro"))) {
            val store = FakeStore(listOf(purchase("pro").copy(signature = "invalid")))
            val server = FakeServer().apply { failure = networkFailure() }
            val persistence = MemoryPersistence().apply { pendingAcknowledgements = pendingTokens }
            val coordinator = coordinator(store, server, persistence)
            val result = runCatching { coordinator.refresh() }
            assertEquals(PurchasesErrorCode.PurchaseInvalid, (result.exceptionOrNull() as PurchasesException).code)
            assertTrue(coordinator.customerInfo.value!!.activeEntitlementIdentifiers.isEmpty())
            assertTrue(store.acknowledged.isEmpty())
        }
    }

    /// 서버 성공 후 확인 처리 실패는 권한을 유지하고 별도 작업으로 복구
    @Test fun acknowledgementRetriesIndependentlyOfServerAvailability() = runTest {
        val store = FakeStore(listOf(purchase("pro"))).apply { acknowledgeFailure = networkFailure() }
        val server = FakeServer()
        val persistence = MemoryPersistence()
        assertTrue(coordinator(store, server, persistence, EntitlementMode.Server).refresh().isEntitlementActive("pro"))
        assertTrue(persistence.pendingSynchronizations.isEmpty())
        assertEquals(setOf("token-pro"), persistence.pendingAcknowledgements)
        store.acknowledgeFailure = null
        server.failure = networkFailure()
        assertTrue(runCatching { coordinator(store, server, persistence, EntitlementMode.Server).refresh() }.isFailure)
        assertTrue(persistence.pendingAcknowledgements.isEmpty())
        assertEquals(1, store.acknowledged.size)
    }

    /// 서버 장애에서 기기 검증 후 확인 처리가 실패해도 두 작업을 따로 복구
    @Test fun fallbackKeepsBothRetriesUntilEachTaskCompletes() = runTest {
        val store = FakeStore(listOf(purchase("pro"))).apply { acknowledgeFailure = networkFailure() }
        val server = FakeServer().apply { failure = serverException(500, "INTERNAL_SERVER_ERROR") }
        val persistence = MemoryPersistence()
        assertTrue(coordinator(store, server, persistence).refresh().isEntitlementActive("pro"))
        assertEquals(setOf("token-pro"), persistence.pendingAcknowledgements)
        assertEquals(1, persistence.pendingSynchronizations.size)
        store.acknowledgeFailure = null
        assertTrue(coordinator(store, server, persistence).refresh().isEntitlementActive("pro"))
        assertTrue(persistence.pendingAcknowledgements.isEmpty())
        assertEquals(1, persistence.pendingSynchronizations.size)
        server.failure = null
        coordinator(store, server, persistence).refresh()
        assertTrue(persistence.pendingSynchronizations.isEmpty())
        assertEquals(1, store.acknowledged.size)
    }

    /// 스토어 조회 실패는 이전 권한을 무료로 덮어쓰지 않음
    @Test fun queryFailurePreservesLastSuccessfulState() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val coordinator = coordinator(store, FakeServer(), MemoryPersistence())
        val previous = coordinator.refresh()
        store.queryFailure = networkFailure()
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        assertEquals(previous, coordinator.customerInfo.value)
    }

    /// 정상 서버 응답의 비활성 판정은 이전 메모리 권한을 제거
    @Test fun authoritativeInactiveRemovesPreviousRights() = runTest {
        val server = FakeServer()
        val coordinator = coordinator(FakeStore(listOf(purchase("pro"))), server, MemoryPersistence())
        coordinator.refresh()
        server.response = information("pro", false)
        coordinator.refresh()
        assertTrue(coordinator.customerInfo.value!!.activeEntitlementIdentifiers.isEmpty())
    }

    /// 보유 목록에서 사라진 토큰의 재시도 응답은 현재 권한에 합치지 않음
    @Test fun removedPurchaseCannotGrantFromPendingServerQueue() = runTest {
        val persistence = MemoryPersistence().apply { pendingSynchronizations = listOf(SynchronizationRequest("old-token", "pro", ProductKind.Subscription)) }
        val server = FakeServer()
        val result = coordinator(FakeStore(emptyList()), server, persistence).refresh()
        assertTrue(result.activeEntitlementIdentifiers.isEmpty())
        assertEquals(1, server.requests.size)
        assertTrue(persistence.pendingSynchronizations.isEmpty())
    }

    /// 동시 복원 요청도 같은 토큰의 확인 처리를 중복 실행하지 않음
    @Test fun concurrentRefreshesHaveSingleInflightAcknowledgement() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val coordinator = coordinator(store, FakeServer(), MemoryPersistence())
        (1..5).map { async { coordinator.refresh() } }.awaitAll()
        assertEquals(1, store.acknowledged.size)
        assertEquals(1, store.maximumAcknowledgements)
    }

    /// 구매 토큰별 고객을 합쳐 권한 식별자 중복을 제거
    @Test fun multipleTokensMergeCustomersAndEntitlements() = runTest {
        val store = FakeStore(listOf(purchase("pro"), purchase("plus")))
        val result = coordinator(store, FakeServer(), MemoryPersistence()).refresh()
        assertEquals(setOf("customer-pro", "customer-plus"), result.customerIdentifiers)
        assertEquals(setOf("pro", "plus"), result.activeEntitlementIdentifiers)
    }

    /// 로컬 검증을 허용하지 않은 서버 모드는 장애에도 권한을 만들지 않음
    @Test fun serverOnlyNeverFallsBack() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer().apply { failure = networkFailure() }
        assertTrue(runCatching { coordinator(store, server, MemoryPersistence(), EntitlementMode.Server).refresh() }.isFailure)
        assertTrue(store.acknowledged.isEmpty())
    }

    /// 서버 비활성 기록은 재실행 후 장애 폴백에서도 권한을 되살리지 않음
    @Test fun authoritativeInactivePersistsAcrossRestartAndClearsOnlyAfterServerGrant() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer().apply { response = information("pro", false) }
        val persistence = MemoryPersistence()
        coordinator(store, server, persistence).refresh()
        assertEquals(setOf("token-pro"), persistence.serverInactivePurchaseTokens)
        server.failure = networkFailure()
        assertTrue(coordinator(store, server, persistence).refresh().activeEntitlementIdentifiers.isEmpty())
        assertTrue(store.acknowledged.isEmpty())
        server.failure = null
        server.response = information("pro", true)
        assertTrue(coordinator(store, server, persistence).refresh().isEntitlementActive("pro"))
        assertTrue(persistence.serverInactivePurchaseTokens.isEmpty())
    }

    /// 구매 무효·대기·미완료 판정은 재시작 후 장애에도 차단하고 서버 활성 확인으로 해제
    @Test fun explicitPurchaseRejectionBlocksFallbackAndAcknowledgementUntilServerGrant() = runTest {
        for ((status, code) in listOf(400 to "GOOGLE_PLAY_PURCHASE_INVALID", 400 to "GOOGLE_PLAY_PRODUCT_MISMATCH", 409 to "GOOGLE_PLAY_PURCHASE_PENDING", 409 to "GOOGLE_PLAY_PURCHASE_NOT_COMPLETED")) {
            val store = FakeStore(listOf(purchase("pro")))
            val server = FakeServer().apply { failure = serverException(status, code) }
            val persistence = MemoryPersistence().apply { pendingAcknowledgements = setOf("token-pro") }
            assertTrue(runCatching { coordinator(store, server, persistence).refresh() }.isFailure)
            assertTrue(store.acknowledged.isEmpty())
            assertTrue(persistence.pendingAcknowledgements.isEmpty())
            assertEquals(setOf("token-pro"), persistence.serverInactivePurchaseTokens)
            server.failure = networkFailure()
            assertTrue(coordinator(store, server, persistence).refresh().activeEntitlementIdentifiers.isEmpty())
            assertTrue(store.acknowledged.isEmpty())
            server.failure = null
            assertTrue(coordinator(store, server, persistence).refresh().isEntitlementActive("pro"))
            assertTrue(persistence.serverInactivePurchaseTokens.isEmpty())
            assertEquals(listOf("token-pro"), store.acknowledged)
        }
    }

    /// 일시 장애 다음의 다른 토큰 거부를 이전 고객 정보로 덮어쓰지 않음
    @Test fun permanentRejectionWinsOverEarlierTransientFailure() = runTest {
        val store = FakeStore(listOf(purchase("plus"), purchase("pro")))
        val server = FakeServer()
        val coordinator = coordinator(store, server, MemoryPersistence(), EntitlementMode.Server)
        coordinator.refresh()
        server.failuresByProduct = mapOf("plus" to networkFailure(), "pro" to serverException(400, "GOOGLE_PLAY_PURCHASE_INVALID"))
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        assertEquals(setOf("plus"), coordinator.customerInfo.value!!.activeEntitlementIdentifiers)
    }

    /// 서버가 폐기한 기존 상품은 같은 갱신의 기기 폴백에서 제외
    @Test fun serverRevocationOverridesOldProductFallbackDuringUpgrade() {
        val now = Instant.now()
        val fallback = CustomerInfo(CustomerInfoSource.GooglePlay, entitlements = listOf(CustomerEntitlement("plus", productIdentifier = "old-plus", active = true, lastVerifiedAt = now)))
        val server = CustomerInfo(CustomerInfoSource.Server, entitlements = listOf(
            CustomerEntitlement("plus", productIdentifier = "old-plus", active = false, lastVerifiedAt = now),
            CustomerEntitlement("plus", productIdentifier = "pro", active = true, lastVerifiedAt = now),
            CustomerEntitlement("pro", productIdentifier = "pro", active = true, lastVerifiedAt = now),
        ))
        val result = mergeCustomerInformation(listOf(fallback, server), CustomerInfoSource.Server)
        assertEquals(setOf("plus", "pro"), result.activeEntitlementIdentifiers)
        assertTrue(result.entitlements.all { it.productIdentifier == "pro" })
    }

    /// 이전 서버 성공 캐시보다 이번 서버의 비활성 결과를 우선
    @Test fun freshInactiveOverridesPreviousServerCacheOnPartialNetworkFailure() = runTest {
        val store = FakeStore(listOf(purchase("plus"), purchase("pro")))
        val server = FakeServer()
        server.response = CustomerInfo(CustomerInfoSource.Server, setOf("linked-customer"), listOf(
            CustomerEntitlement("plus", productIdentifier = "plus", active = true, lastVerifiedAt = Instant.now()),
            CustomerEntitlement("pro", productIdentifier = "pro", active = true, lastVerifiedAt = Instant.now()),
        ))
        val coordinator = coordinator(store, server, MemoryPersistence(), EntitlementMode.Server)
        coordinator.refresh()
        server.failuresByProduct = mapOf("plus" to networkFailure())
        server.response = CustomerInfo(CustomerInfoSource.Server, setOf("linked-customer"), listOf(
            CustomerEntitlement("plus", productIdentifier = "plus", active = false, lastVerifiedAt = Instant.now()),
            CustomerEntitlement("pro", productIdentifier = "pro", active = true, lastVerifiedAt = Instant.now()),
        ))
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        // 실패가 있어도 이번에 서버가 확정한 회수는 즉시 반영한다.
        assertFalse(coordinator.customerInfo.value!!.isEntitlementActive("plus"))
    }

    /// 같은 고객의 서로 다른 서버 응답에서는 최신 검증 결과를 우선
    @Test fun newestServerFactWinsWithinLinkedCustomer() {
        val older = information("plus", true).copy(customerIdentifiers = setOf("linked-customer"))
        val newer = older.copy(entitlements = older.entitlements.map { it.copy(active = false, lastVerifiedAt = it.lastVerifiedAt.plusSeconds(1)) })
        val result = mergeCustomerInformation(listOf(newer, older), CustomerInfoSource.Server)
        assertFalse(result.isEntitlementActive("plus"))
    }

    /// 같은 고객의 대표 상품이 바뀌어도 최신 비활성 결과로 이전 상품 권한 제거
    @Test fun newestServerFactReplacesOldProductWithinLinkedCustomer() {
        val older = information("plus", true).copy(customerIdentifiers = setOf("linked-customer"))
        val newer = older.copy(entitlements = older.entitlements.map { it.copy(productIdentifier = "pro", active = false, lastVerifiedAt = it.lastVerifiedAt.plusSeconds(1)) })
        val result = mergeCustomerInformation(listOf(newer, older), CustomerInfoSource.Server)
        assertFalse(result.isEntitlementActive("plus"))
        assertEquals("pro", result.entitlements.single().productIdentifier)
    }

    /// 상품이 달라진 최신 서버 판정은 연결된 고객의 이전 성공 캐시도 제거
    @Test fun inactiveReplacementOverridesPreviousProductCacheOnPartialNetworkFailure() = runTest {
        val store = FakeStore(listOf(purchase("plus"), purchase("pro")))
        val server = FakeServer().apply { response = information("plus", true).copy(customerIdentifiers = setOf("linked-customer")) }
        val coordinator = coordinator(store, server, MemoryPersistence(), EntitlementMode.Server)
        coordinator.refresh()
        server.failuresByProduct = mapOf("plus" to networkFailure())
        server.response = information("plus", false).let { information -> information.copy(customerIdentifiers = setOf("linked-customer"), entitlements = information.entitlements.map { it.copy(productIdentifier = "pro") }) }
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        assertFalse(coordinator.customerInfo.value!!.isEntitlementActive("plus"))
        assertEquals("pro", coordinator.customerInfo.value!!.entitlements.single().productIdentifier)
    }

    /// 같은 상품의 기기 폴백은 서버에서 확인한 현재 요금제를 지우지 않음
    @Test fun confirmedPlanSurvivesMergingCurrentDevicePurchase() {
        val server = information("pro", true).let { information -> information.copy(entitlements = information.entitlements.map { it.copy(basePlanIdentifier = "yearly", offerIdentifier = "welcome", expiresAt = Instant.now().plusSeconds(3600)) }) }
        val fallback = CustomerInfo(CustomerInfoSource.GooglePlay, entitlements = listOf(CustomerEntitlement("pro", productIdentifier = "pro", active = true, lastVerifiedAt = Instant.now())))
        val result = mergeCustomerInformation(listOf(server, fallback), CustomerInfoSource.Server)
        assertEquals("yearly", result.entitlements.single().basePlanIdentifier)
        assertEquals("welcome", result.entitlements.single().offerIdentifier)
    }

    /// 현재 보유 목록에서 사라진 이전 권한은 새 구매의 서버 장애와 무관하게 제거
    @Test fun missingOldPurchaseIsRemovedEvenWhenNewPurchaseVerificationFails() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer()
        val coordinator = coordinator(store, server, MemoryPersistence(), EntitlementMode.Server)
        coordinator.refresh()
        store.purchases = listOf(purchase("plus"))
        server.failure = networkFailure()
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        assertTrue(coordinator.customerInfo.value!!.activeEntitlementIdentifiers.isEmpty())
    }

    /// 다른 고객의 새 비활성 응답은 독립적인 기존 고객의 유효 구매를 덮어쓰지 않음
    @Test fun independentCustomerCacheSurvivesAnotherCustomerRevocation() = runTest {
        val store = FakeStore(listOf(purchase("plus"), purchase("pro")))
        val server = FakeServer()
        val coordinator = coordinator(store, server, MemoryPersistence(), EntitlementMode.Server)
        coordinator.refresh()
        server.failuresByProduct = mapOf("plus" to networkFailure())
        server.response = information("plus", false).copy(customerIdentifiers = setOf("different-customer"))
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        assertTrue(coordinator.customerInfo.value!!.isEntitlementActive("plus"))
    }

    /// 결제 콜백은 해당 상품의 명시적 완료만 성공으로 분류
    @Test fun purchaseCallbackRejectsUnspecifiedAndIgnoresOtherProducts() {
        assertEquals(PurchaseResult.Completed, resolvePurchaseUpdate("pro", listOf(purchase("pro"))))
        assertEquals(PurchaseResult.Pending, resolvePurchaseUpdate("pro", listOf(purchase("pro").copy(state = StorePurchaseState.Pending))))
        assertNull(resolvePurchaseUpdate("pro", listOf(purchase("plus"))))
        assertThrows(PurchasesException::class.java) { resolvePurchaseUpdate("pro", listOf(purchase("pro").copy(state = StorePurchaseState.Unspecified))) }
        assertThrows(PurchasesException::class.java) { resolvePurchaseUpdate("pro", emptyList()) }
    }

    /// 지연 변경의 기존 상품·새 토큰은 완료로 받고 이전 토큰 알림은 무시
    @Test fun deferredReplacementCallbackAcceptsNewTokenWithPreviousProduct() {
        val previous = purchase("pro")
        val deferred = previous.copy(purchaseToken = "new-plus-token")
        assertNull(resolvePurchaseUpdate("plus", listOf(previous), previous.purchaseToken, setOf("pro")))
        assertNull(resolvePurchaseUpdate("plus", listOf(deferred), previous.purchaseToken))
        assertEquals(PurchaseResult.Completed, resolvePurchaseUpdate("plus", listOf(previous, deferred), previous.purchaseToken, setOf("pro")))
        assertEquals(PurchaseResult.Pending, resolvePurchaseUpdate("plus", listOf(deferred.copy(state = StorePurchaseState.Pending)), previous.purchaseToken, setOf("pro")))
        assertThrows(PurchasesException::class.java) { resolvePurchaseUpdate("plus", listOf(deferred.copy(state = StorePurchaseState.Unspecified)), previous.purchaseToken, setOf("pro")) }
    }

    /// 같은 상품의 기간 변경도 이전 토큰 콜백을 새 결제 완료로 취급하지 않음
    @Test fun sameProductReplacementCallbackRequiresNewPurchaseToken() {
        val previous = purchase("pro")
        assertNull(resolvePurchaseUpdate("pro", listOf(previous), previous.purchaseToken))
        assertEquals(PurchaseResult.Completed, resolvePurchaseUpdate("pro", listOf(previous.copy(purchaseToken = "new-yearly-token")), previous.purchaseToken))
    }

    /// 일반 조회는 재전송하지 않고 로그인·명시적 복구는 현재 계정으로 재전송
    @Test fun explicitRecoveryRepublishesWithoutChangingOrdinaryQueries() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer()
        val coordinator = coordinator(store, server, MemoryPersistence())
        coordinator.refresh()
        assertTrue(server.republications.isEmpty())
        coordinator.logIn("flow-first-user")
        assertEquals("flow-first-user", server.republications.last().applicationAccountIdentifier)
        coordinator.refresh()
        assertEquals(1, server.republications.size)
        val requestIdentifier = "11111111-1111-4111-8111-111111111111"
        val result = coordinator.synchronizeEntitlements(requestIdentifier)
        assertEquals(requestIdentifier, result.republications.single().requestIdentifier)
        assertEquals(EntitlementRepublicationStatus.Queued, result.republications.single().status)
        coordinator.logOut()
        coordinator.refresh()
        assertNull(server.requests.last().applicationAccountIdentifier)
    }

    /// 복구 통신 실패는 성공으로 숨기지 않고 같은 요청을 재시도 목록에 보존
    @Test fun failedRepublicationRemainsPending() = runTest {
        val store = FakeStore(listOf(purchase("pro")))
        val server = FakeServer().apply { republicationFailure = PurchasesException(PurchasesErrorCode.Network, "테스트 실패", retryable = true) }
        val persistence = MemoryPersistence()
        val coordinator = coordinator(store, server, persistence)
        val requestIdentifier = "11111111-1111-4111-8111-111111111111"
        try { coordinator.synchronizeEntitlements(requestIdentifier); fail("복구 실패를 반환해야 함") }
        catch (_: PurchasesException) { }
        assertEquals(requestIdentifier, persistence.pendingSynchronizations.single().republicationRequestIdentifier)
    }

    /// 테스트용 코디네이터 구성
    private fun coordinator(store: FakeStore, server: FakeServer, persistence: MemoryPersistence, mode: EntitlementMode = EntitlementMode.ServerWithGooglePlayFallback): PurchasesCoordinator = PurchasesCoordinator("test.application", PurchasesConfiguration(mapping, mode, encodedPublicKey), store, server, persistence)

    /// 실제 RSA 서명이 있는 테스트 구매 원본 생성
    private fun purchase(product: String): StorePurchase {
        val original = JSONObject().put("packageName", "test.application").put("productId", product).put("purchaseToken", "token-$product").put("purchaseState", 0).toString()
        val signature = Signature.getInstance("SHA1withRSA").run { initSign(signingKey.private); update(original.toByteArray()); Base64.getEncoder().encodeToString(sign()) }
        return StorePurchase("token-$product", setOf(product), "test.application", ProductKind.Subscription, StorePurchaseState.Purchased, original, signature, false, 1000)
    }
}

/// 네트워크 없이 스토어 동작과 동시 호출 수를 관찰하는 대역
private class FakeStore(var purchases: List<StorePurchase>) : PurchasesStore {
    override val updates = MutableSharedFlow<Unit>()
    val acknowledged = mutableListOf<String>()
    var acknowledgeFailure: PurchasesException? = null
    var queryFailure: PurchasesException? = null
    var maximumAcknowledgements = 0
    private var activeAcknowledgements = 0
    /// 설정된 보유 구매 또는 조회 실패 반환
    override suspend fun queryPurchases(): List<StorePurchase> { queryFailure?.let { throw it }; return purchases }
    /// 이 테스트에서 사용하지 않는 상품 목록
    override suspend fun products(options: List<PurchaseOption>, configuration: PurchaseConfiguration?): List<StorePurchaseOption> = emptyList()
    /// 이 테스트에서 결제창을 열지 않음
    override suspend fun purchase(activity: Activity, option: StorePurchaseOption, replacement: SubscriptionReplacement?, accountIdentifier: String?): PurchaseResult = error("사용하지 않음")
    /// 확인 처리 실패 또는 성공을 보유 구매에 반영
    override suspend fun acknowledge(purchaseToken: String) {
        acknowledgeFailure?.let { throw it }
        activeAcknowledgements += 1
        maximumAcknowledgements = maxOf(maximumAcknowledgements, activeAcknowledgements)
        delay(5)
        acknowledged += purchaseToken
        purchases = purchases.map { if (it.purchaseToken == purchaseToken) it.copy(acknowledged = true) else it }
        activeAcknowledgements -= 1
    }
    /// 테스트 연결 종료
    override fun close() = Unit
}

/// 서버 응답과 요청 토큰을 제어하는 대역
private class FakeServer : PurchasesServer {
    val republications = mutableListOf<SynchronizationRequest>()
    var republicationFailure: Throwable? = null
    /// 별도 복구 API 호출과 멱등 식별자 기록
    override suspend fun republishEntitlements(request: SynchronizationRequest, requestIdentifier: String): EntitlementSynchronizationResult {
        republications += request
        republicationFailure?.let { throw it }
        return EntitlementSynchronizationResult(response ?: information(request.productIdentifier, true),
            listOf(EntitlementRepublication(requestIdentifier, EntitlementRepublicationStatus.Queued, 1)))
    }
    val requests = mutableListOf<SynchronizationRequest>()
    var response: CustomerInfo? = null
    var failure: Throwable? = null
    var failuresByProduct: Map<String, PurchasesException> = emptyMap()
    /// 설정한 서버 결과 반환
    override suspend fun synchronize(request: SynchronizationRequest): CustomerInfo { requests += request; failuresByProduct[request.productIdentifier]?.let { throw it }; failure?.let { throw it }; return response ?: information(request.productIdentifier, true) }
    /// 이 테스트에서 사용하지 않는 원격 페이월
    override suspend fun paywall(identifier: String, localeIdentifier: String): ResolvedPaywallConfiguration = error("사용하지 않음")
}

/// 프로세스 재생성 테스트에서 공유하는 영속 작업 대역
private class MemoryPersistence : PurchasesPersistence {
    override var pendingSynchronizations = emptyList<SynchronizationRequest>()
    override var pendingAcknowledgements = emptySet<String>()
    override var serverInactivePurchaseTokens = emptySet<String>()
    private val paywalls = mutableMapOf<String, String>()
    /// 저장한 테스트 페이월 조회
    override fun cachedPaywall(key: String): String? = paywalls[key]
    /// 테스트 페이월 저장
    override fun cachePaywall(key: String, value: String) { paywalls[key] = value }
}

/// 상품별 서버 권한 응답 생성
private fun information(product: String, active: Boolean): CustomerInfo = CustomerInfo(CustomerInfoSource.Server, setOf("customer-$product"), listOf(CustomerEntitlement(product, productIdentifier = product, active = active, lastVerifiedAt = Instant.now())))
/// 재시도 가능한 서버 네트워크 오류 생성
private fun networkFailure(): PurchasesException = serverException(503, "GOOGLE_PLAY_API_REQUEST_FAILED")
