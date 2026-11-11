package sh.purpl.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.purpl.*
import sh.purpl.infrastructure.*
import java.time.Instant
import java.util.UUID

/// 스토어 재조회·권한 확인·서버 동기화·구매 확인 처리의 단일 실행 경계
internal class PurchasesCoordinator(
    private val packageName: String,
    private val configuration: PurchasesConfiguration,
    private val store: PurchasesStore,
    private val server: PurchasesServer,
    private val persistence: PurchasesPersistence,
    val customerInfo: MutableStateFlow<CustomerInfo?> = MutableStateFlow(null),
    val errors: MutableSharedFlow<PurchasesException> = MutableSharedFlow(extraBufferCapacity = 32),
) {
    private val synchronizationMutex = Mutex()
    @Volatile var applicationAccountIdentifier: String? = configuration.applicationAccountIdentifier
        private set
    private val lastTokenInformation = mutableMapOf<String, CustomerInfo>()
    private val verifier = configuration.googlePlayPublicKey?.takeIf { it.isNotBlank() }?.let(::PurchaseSignatureVerifier)

    /// 현재 스토어에서 확인한 구매만 이용한 고객 권한 갱신
    suspend fun refresh(): CustomerInfo = refreshCustomer().customerInfo

    /// 구매 갱신과 해당 요청의 재전송 결과를 같은 잠금 안에서 반환
    private suspend fun refreshCustomer(republicationRequestIdentifier: String? = null, loginAccountIdentifier: String? = null): EntitlementSynchronizationResult = synchronizationMutex.withLock {
        var republications = emptyList<EntitlementRepublication>()
        if (loginAccountIdentifier != null && applicationAccountIdentifier != loginAccountIdentifier) {
            applicationAccountIdentifier = loginAccountIdentifier
            lastTokenInformation.clear()
        }
        // 조회 실패를 빈 구매로 바꾸지 않아 기존 화면을 무료 상태로 덮어쓰지 않는다.
        val purchases = store.queryPurchases().distinctBy { it.purchaseToken }
        val completed = purchases.filter { it.state == StorePurchaseState.Purchased }
        val resolved = mutableListOf<CustomerInfo>()
        val cached = mutableListOf<CustomerInfo>()
        lastTokenInformation.keys.retainAll(completed.map { it.purchaseToken }.toSet())
        var firstFailure: PurchasesException? = null
        for (purchase in completed) {
            try {
                val information = resolve(purchase)
                lastTokenInformation[purchase.purchaseToken] = information
                resolved += information
                val granted = information.entitlements.any { it.active && it.productIdentifier in purchase.productIdentifiers }
                if (granted && !purchase.acknowledged) {
                    // 권한 부여 뒤 확인 처리 작업을 별도로 기록한다.
                    persistence.pendingAcknowledgements = persistence.pendingAcknowledgements + purchase.purchaseToken
                    acknowledge(purchase)
                } else if (purchase.acknowledged) {
                    persistence.pendingAcknowledgements = persistence.pendingAcknowledgements - purchase.purchaseToken
                }
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                val failure = error.asPurchasesException()
                errors.tryEmit(failure)
                if (!failure.purchaseRejected) {
                    // 서버 연결 실패에도 이미 권한을 확인한 구매의 확인 처리는 따로 복구한다.
                    try { acknowledgePendingPurchase(purchase) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errors.tryEmit(error.asPurchasesException()) }
                }
                if (failure.retryable) lastTokenInformation[purchase.purchaseToken]?.let { cached += it }
                else lastTokenInformation.remove(purchase.purchaseToken)
                if (firstFailure == null || !failure.retryable) firstFailure = failure
            }
        }
        // 이번 서버 응답이 있으면 같은 고객·권한의 이전 메모리 값을 버린다.
        val freshlyVerified = resolved.filter { it.source == CustomerInfoSource.Server }.flatMap { information ->
            information.entitlements.map { information.customerIdentifiers to it.identifier }
        }.toSet()
        resolved += cached.map { information -> information.copy(entitlements = information.entitlements.filterNot { (information.customerIdentifiers to it.identifier) in freshlyVerified }) }
        // 더 이상 보유하지 않은 토큰은 동기화하되 결과를 현재 권한에 합치지 않는다.
        val currentTokens = purchases.map { it.purchaseToken }.toSet()
        for (request in persistence.pendingSynchronizations.filter { it.purchaseToken !in currentTokens }) {
            try {
                val information = server.synchronize(request)
                persistence.serverInactivePurchaseTokens = if (information.entitlements.any { it.active && it.productIdentifier == request.productIdentifier }) persistence.serverInactivePurchaseTokens - request.purchaseToken else persistence.serverInactivePurchaseTokens + request.purchaseToken
                removeSynchronization(request.purchaseToken)
            } catch (error: CancellationException) { throw error } catch (error: Exception) {
                val failure = error.asPurchasesException()
                if (failure.purchaseRejected) recordRejectedPurchase(request.purchaseToken)
                errors.tryEmit(failure)
            }
        }
        val deniedProducts = authoritativeDeniedProducts(resolved)
        persistence.serverInactivePurchaseTokens = persistence.serverInactivePurchaseTokens + completed.filter { purchase -> purchase.productIdentifiers.any { it in deniedProducts } }.map { it.purchaseToken }
        persistence.pendingAcknowledgements = persistence.pendingAcknowledgements.intersect(currentTokens) - persistence.serverInactivePurchaseTokens
        firstFailure?.let { failure ->
            customerInfo.value = mergeCustomerInformation(resolved, defaultSource())
            throw failure
        }
        if (republicationRequestIdentifier != null && configuration.entitlementMode != EntitlementMode.GooglePlay) {
            val results = mutableListOf<EntitlementRepublication>()
            val requestedCustomers = mutableSetOf<String>()
            for (purchase in completed) {
                val information = lastTokenInformation[purchase.purchaseToken]
                val customerKey = information?.customerIdentifiers?.sorted()?.joinToString(":")
                    ?.takeIf { it.isNotEmpty() } ?: purchase.purchaseToken
                if (!requestedCustomers.add(customerKey)) continue
                val request = SynchronizationRequest(purchase.purchaseToken, purchase.productIdentifiers.first(), purchase.kind,
                    applicationAccountIdentifier, republicationRequestIdentifier)
                // 통신 실패 시 같은 요청 식별자와 계정으로 다음 갱신에서 복구한다.
                persistence.pendingSynchronizations = persistence.pendingSynchronizations.filterNot { it.purchaseToken == request.purchaseToken } + request
                val result = server.republishEntitlements(request, republicationRequestIdentifier)
                results += result.republications
                removeSynchronization(request.purchaseToken)
            }
            republications = results
        }
        EntitlementSynchronizationResult(mergeCustomerInformation(resolved, defaultSource()).also { customerInfo.value = it }, republications)
    }

    /// 로그인 계정 연결과 현재 권한 재전송
    suspend fun logIn(accountIdentifier: String): CustomerInfo {
        require(accountIdentifier.isNotBlank() && accountIdentifier.length <= 512)
        return refreshCustomer(UUID.randomUUID().toString(), accountIdentifier).customerInfo
    }

    /// 이후 구매와 조회에서 사용할 앱 계정 연결 해제
    suspend fun logOut() = synchronizationMutex.withLock {
        applicationAccountIdentifier = null
        lastTokenInformation.clear()
    }

    /// 현재 권한 재전송과 전달 결과 조회
    suspend fun synchronizeEntitlements(requestIdentifier: String): EntitlementSynchronizationResult {
        UUID.fromString(requestIdentifier)
        return refreshCustomer(requestIdentifier)
    }

    /// 구독 변경에 필요한 현재 구매 토큰 조회
    suspend fun activePurchases(): List<ActivePurchase> = synchronizationMutex.withLock {
        store.queryPurchases().filter { it.state == StorePurchaseState.Purchased && !it.suspended }
            .distinctBy { it.purchaseToken }.map { ActivePurchase(it.purchaseToken, it.productIdentifiers, it.kind) }
    }

    /// 완료되지 않은 독립 작업의 존재 여부
    fun hasPendingWork(): Boolean = persistence.pendingAcknowledgements.isNotEmpty() || persistence.pendingSynchronizations.isNotEmpty()

    /// 지정 구매를 권위 있는 서버 또는 현재 기기의 서명으로 확인
    private suspend fun resolve(purchase: StorePurchase): CustomerInfo {
        if (configuration.entitlementMode == EntitlementMode.GooglePlay) return localInformation(purchase)
        val request = SynchronizationRequest(purchase.purchaseToken, purchase.productIdentifiers.firstOrNull()
            ?: throw PurchasesException(PurchasesErrorCode.PurchaseInvalid, "구매 상품이 없습니다."), purchase.kind,
            applicationAccountIdentifier, persistence.pendingSynchronizations.firstOrNull {
                it.purchaseToken == purchase.purchaseToken && it.applicationAccountIdentifier == applicationAccountIdentifier
            }?.republicationRequestIdentifier)
        persistence.pendingSynchronizations = (persistence.pendingSynchronizations.filterNot { it.purchaseToken == request.purchaseToken } + request)
        try {
            return server.synchronize(request).also { information ->
                val active = information.entitlements.any { it.active && it.productIdentifier in purchase.productIdentifiers }
                persistence.serverInactivePurchaseTokens = if (active) persistence.serverInactivePurchaseTokens - purchase.purchaseToken else persistence.serverInactivePurchaseTokens + purchase.purchaseToken
                removeSynchronization(purchase.purchaseToken)
            }
        } catch (error: CancellationException) { throw error } catch (error: Exception) {
            val failure = error.asPurchasesException()
            if (failure.purchaseRejected) {
                recordRejectedPurchase(purchase.purchaseToken)
                throw failure
            }
            if (configuration.entitlementMode != EntitlementMode.ServerWithGooglePlayFallback) throw failure
            errors.tryEmit(failure)
            // 서버 오류는 비활성 판정이 아니므로 동기화를 남기고 현재 기기 구매로 복구한다.
            if (purchase.purchaseToken in persistence.serverInactivePurchaseTokens) return CustomerInfo(CustomerInfoSource.Server)
            return localInformation(purchase)
        }
    }

    /// 서버가 명시적으로 거부한 구매의 폴백과 확인 처리 차단
    private fun recordRejectedPurchase(purchaseToken: String) {
        persistence.serverInactivePurchaseTokens = persistence.serverInactivePurchaseTokens + purchaseToken
        persistence.pendingAcknowledgements = persistence.pendingAcknowledgements - purchaseToken
    }

    /// 서명이 확인된 현재 구매를 로컬 권한 구성에 연결
    private fun localInformation(purchase: StorePurchase): CustomerInfo {
        val mapping = configuration.purchaseConfiguration ?: throw PurchasesException(PurchasesErrorCode.Configuration, "기기 권한 구성이 필요합니다.")
        if (purchase.suspended) return CustomerInfo(CustomerInfoSource.GooglePlay)
        val signatureVerifier = verifier ?: throw PurchasesException(PurchasesErrorCode.Configuration, "Google Play 앱 공개 키가 필요합니다.")
        signatureVerifier.verify(purchase, packageName, mapping.products.map { it.productIdentifier }.toSet())
        val now = Instant.now()
        val entitlements = mapping.products.filter { it.productIdentifier in purchase.productIdentifiers }.flatMap { product ->
            product.entitlementIdentifiers.map { identifier ->
                CustomerEntitlement(identifier, mapping.entitlements.first { it.identifier == identifier }.displayName,
                    product.productIdentifier, active = true, startsAt = Instant.ofEpochMilli(purchase.purchasedAtMilliseconds), lastVerifiedAt = now)
            }
        }
        // 기기 응답에 없는 만료일·환경·기본 요금제를 추측하지 않는다.
        return CustomerInfo(CustomerInfoSource.GooglePlay, entitlements = entitlements)
    }

    /// 이전에 권한을 확인한 구매의 확인 처리를 서버 연결과 별도로 복구
    private suspend fun acknowledgePendingPurchase(purchase: StorePurchase): Boolean {
        if (purchase.purchaseToken !in persistence.pendingAcknowledgements || purchase.acknowledged || purchase.suspended || purchase.purchaseToken in persistence.serverInactivePurchaseTokens) return false
        // 공개 키가 있으면 재시도에서도 현재 구매 원본의 변조 여부를 먼저 확인한다.
        verifier?.verify(purchase, packageName, configuration.purchaseConfiguration?.products?.map { it.productIdentifier }?.toSet() ?: purchase.productIdentifiers)
        return acknowledge(purchase)
    }

    /// 권한 확인이 끝난 구매의 확인 처리와 실패 재시도 보존
    private suspend fun acknowledge(purchase: StorePurchase): Boolean {
        try {
            store.acknowledge(purchase.purchaseToken)
            persistence.pendingAcknowledgements = persistence.pendingAcknowledgements - purchase.purchaseToken
            return true
        } catch (error: CancellationException) { throw error } catch (error: Exception) {
            errors.tryEmit(error.asPurchasesException())
            return false
        }
    }

    /// 정상 서버 응답을 받은 동기화 작업 제거
    private fun removeSynchronization(token: String) {
        persistence.pendingSynchronizations = persistence.pendingSynchronizations.filterNot { it.purchaseToken == token }
    }

    /// 구매가 없을 때 사용할 구성 출처
    private fun defaultSource(): CustomerInfoSource = if (configuration.entitlementMode == EntitlementMode.GooglePlay) CustomerInfoSource.GooglePlay else CustomerInfoSource.Server
}

/// 여러 구매 토큰의 고객 권한을 중복 없이 합치기
internal fun mergeCustomerInformation(values: List<CustomerInfo>, defaultSource: CustomerInfoSource): CustomerInfo {
    val sources = values.map { it.source }.toSet()
    val deniedProducts = authoritativeDeniedProducts(values)
    val serverEntitlements = latestServerEntitlements(values)
    val verifiedEntitlements = serverEntitlements.map { it.identifier to it.productIdentifier }.toSet()
    val eligibleEntitlements = serverEntitlements + values.filter { it.source != CustomerInfoSource.Server }.flatMap { information ->
        information.entitlements.filterNot { it.productIdentifier in deniedProducts || (it.identifier to it.productIdentifier) in verifiedEntitlements }
    }
    return CustomerInfo(
        source = if (sources.size > 1) CustomerInfoSource.Mixed else sources.firstOrNull() ?: defaultSource,
        customerIdentifiers = values.flatMap { it.customerIdentifiers }.toSet(),
        entitlements = eligibleEntitlements.groupBy { it.identifier }.values.map { entries ->
            entries.maxWith(compareBy<CustomerEntitlement> { it.active }.thenBy { if (it.active && it.expiresAt == null) Instant.MAX else it.expiresAt ?: Instant.MIN }.thenBy { it.lastVerifiedAt })
        },
    )
}

/// 서버가 명시적으로 비활성으로 확인하고 활성으로 재확인하지 않은 상품
private fun authoritativeDeniedProducts(values: List<CustomerInfo>): Set<String> {
    val serverEntitlements = latestServerEntitlements(values)
    val activeProducts = serverEntitlements.filter { it.active }.mapNotNull { it.productIdentifier }.toSet()
    return serverEntitlements.filterNot { it.active }.mapNotNull { it.productIdentifier }.toSet() - activeProducts
}

/// 같은 서버 고객의 권한은 상품 변경과 관계없이 최신 대표 결과만 유지
private fun latestServerEntitlements(values: List<CustomerInfo>): List<CustomerEntitlement> = values.filter { it.source == CustomerInfoSource.Server }
    .flatMap { information -> information.entitlements.map { information.customerIdentifiers.sorted().joinToString("|") to it } }
    .withIndex().groupBy { (_, value) -> value.first to value.second.identifier }
    .values.map { entries -> entries.maxWith(compareBy<IndexedValue<Pair<String, CustomerEntitlement>>> { it.value.second.lastVerifiedAt }.thenBy { it.index }).value.second }

/// 외부 실패를 SDK 오류로 정규화
internal fun Exception.asPurchasesException(): PurchasesException = if (this is PurchasesException) this else PurchasesException(PurchasesErrorCode.BillingUnavailable, "구매 처리 중 오류가 발생했습니다.", this)
