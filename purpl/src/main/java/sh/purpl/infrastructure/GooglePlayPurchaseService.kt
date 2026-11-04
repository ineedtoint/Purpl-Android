package sh.purpl.infrastructure

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.purpl.*
import sh.purpl.internal.*
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/// Google Play Billing 연결·조회·결제·확인 처리
internal class GooglePlayPurchaseService(context: Context) : PurchasesStore {
    override val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val connectionMutex = Mutex()
    private val purchaseMutex = Mutex()
    @Volatile private var pendingPurchase: PendingPurchase? = null
    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .setListener { result, purchases ->
            val pending = pendingPurchase
            val completion = pending?.completion
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    if (pending != null) {
                        try {
                            val outcome = resolvePurchaseUpdate(pending.productIdentifier, purchases.orEmpty().map { it.snapshot(pending.kind) }, pending.replacedPurchaseToken, pending.deferredProductIdentifiers)
                            if (outcome != null) pending.completion.complete(outcome)
                        } catch (error: PurchasesException) { pending.completion.completeExceptionally(error) }
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> completion?.complete(PurchaseResult.Cancelled)
                else -> completion?.completeExceptionally(billingException(result))
            }
            updates.tryEmit(Unit)
        }.build()

    /// 결제 서비스 연결 완료 대기
    private suspend fun connect() = connectionMutex.withLock {
        if (billingClient.isReady) return@withLock
        withContext(Dispatchers.Main.immediate) {
            billingRequest {
                suspendCancellableCoroutine { continuation ->
                    billingClient.startConnection(object : BillingClientStateListener {
                        /// 결제 서비스 초기 연결 결과 전달
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (!continuation.isActive) return
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                            else continuation.resumeWithException(billingException(result))
                        }
                        /// 연결 종료 후 다음 조회에서 재연결
                        override fun onBillingServiceDisconnected() { updates.tryEmit(Unit) }
                    })
                }
            }
        }
    }

    /// 보유 구매 전체를 유형별 조회 후 합치기
    override suspend fun queryPurchases(): List<StorePurchase> {
        connect()
        return queryPurchases(ProductKind.Subscription) + queryPurchases(ProductKind.NonConsumable)
    }

    /// 지정 유형의 현재 보유 구매 조회
    private suspend fun queryPurchases(kind: ProductKind): List<StorePurchase> = billingRequest {
        suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(kind.billingType()).build()) { result, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(purchases.map { it.snapshot(kind) })
                else continuation.resumeWithException(billingException(result))
            }
        }
    }

    /// 구성한 구매 옵션에 맞는 현재 상품과 가격 조회
    override suspend fun products(options: List<PurchaseOption>, configuration: PurchaseConfiguration?): List<StorePurchaseOption> {
        if (options.isEmpty()) return emptyList()
        connect()
        val details = mutableListOf<ProductDetails>()
        for (kind in ProductKind.entries) {
            val identifiers = options.filter { option ->
                val mappedKind = configuration?.products?.find { it.productIdentifier == option.productIdentifier }?.kind
                mappedKind == kind || mappedKind == null
            }.map { it.productIdentifier }.distinct()
            if (identifiers.isNotEmpty()) details += queryProductDetails(identifiers, kind)
        }
        return options.mapNotNull { option -> details.firstNotNullOfOrNull { detail -> if (detail.productId == option.productIdentifier) detail.resolve(option) else null } }
    }

    /// Google Play 상품 상세 조회
    private suspend fun queryProductDetails(identifiers: List<String>, kind: ProductKind): List<ProductDetails> = billingRequest {
        suspendCancellableCoroutine { continuation ->
            val parameters = QueryProductDetailsParams.newBuilder().setProductList(identifiers.map { identifier ->
                QueryProductDetailsParams.Product.newBuilder().setProductId(identifier).setProductType(kind.billingType()).build()
            }).build()
            billingClient.queryProductDetailsAsync(parameters) { result, response ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(response.productDetailsList)
                else continuation.resumeWithException(billingException(result))
            }
        }
    }

    /// 최신 상품 정보로 결제창을 열고 완료·대기·취소 결과 수신
    override suspend fun purchase(activity: Activity, option: StorePurchaseOption, replacement: SubscriptionReplacement?, accountIdentifier: String?): PurchaseResult {
        if (!purchaseMutex.tryLock()) throw PurchasesException(PurchasesErrorCode.PurchaseInProgress, "이미 구매가 진행 중입니다.")
        try {
            connect()
            // 오래된 상품 정보와 혜택 토큰을 재사용하지 않고 결제 직전에 다시 조회한다.
            val details = queryProductDetails(listOf(option.option.productIdentifier), option.kind).firstOrNull()
                ?: throw PurchasesException(PurchasesErrorCode.ProductUnavailable, "현재 구매할 수 없는 상품입니다.")
            val productParameters = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            if (option.kind == ProductKind.Subscription) {
                val offer = details.subscriptionOfferDetails.orEmpty().firstOrNull { it.basePlanId == option.option.basePlanIdentifier && it.offerId == option.option.offerIdentifier }
                    ?: throw PurchasesException(PurchasesErrorCode.ProductUnavailable, "현재 구매할 수 없는 기본 요금제 또는 혜택입니다.")
                productParameters.setOfferToken(offer.offerToken)
            }
            val parameters = BillingFlowParams.newBuilder()
            var deferredProductIdentifiers = emptySet<String>()
            if (replacement != null) {
                require(option.kind == ProductKind.Subscription)
                val existing = queryPurchases(ProductKind.Subscription).firstOrNull { it.purchaseToken == replacement.purchaseToken && it.state == StorePurchaseState.Purchased }
                    ?: throw PurchasesException(PurchasesErrorCode.PurchaseInvalid, "교체할 기존 구독을 확인하지 못했습니다.")
                val oldProductIdentifier = existing.productIdentifiers.singleOrNull()
                    ?: throw PurchasesException(PurchasesErrorCode.Configuration, "여러 상품으로 구성된 구독 교체는 지원하지 않습니다.")
                // 지연 변경의 새 토큰은 다음 갱신일까지 기존 상품을 가리킬 수 있다.
                if (replacement.mode == SubscriptionReplacementMode.Deferred) deferredProductIdentifiers = existing.productIdentifiers
                productParameters.setSubscriptionProductReplacementParams(BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.newBuilder()
                    .setOldProductId(oldProductIdentifier).setReplacementMode(replacement.mode.billingValue()).build())
                parameters.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(replacement.purchaseToken).build())
            }
            parameters.setProductDetailsParamsList(listOf(productParameters.build()))
            if (!accountIdentifier.isNullOrBlank()) parameters.setObfuscatedAccountId(MessageDigest.getInstance("SHA-256").digest(accountIdentifier.toByteArray()).joinToString("") { "%02x".format(it) })
            val completion = CompletableDeferred<PurchaseResult>()
            pendingPurchase = PendingPurchase(completion, option.option.productIdentifier, option.kind, replacement?.purchaseToken, deferredProductIdentifiers)
            val result = withContext(Dispatchers.Main.immediate) { billingClient.launchBillingFlow(activity, parameters.build()) }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) throw billingException(result)
            return completion.await()
        } finally {
            pendingPurchase = null
            purchaseMutex.unlock()
        }
    }

    /// 구매 처리 완료를 Google Play에 전달
    override suspend fun acknowledge(purchaseToken: String) {
        connect()
        billingRequest {
            suspendCancellableCoroutine { continuation ->
                billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()) { result ->
                    if (!continuation.isActive) return@acknowledgePurchase
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                    else continuation.resumeWithException(billingException(result))
                }
            }
        }
    }

    /// 진행 중 요청과 Google Play 연결 종료
    override fun close() { pendingPurchase?.completion?.cancel(); billingClient.endConnection() }
}

/// 한 결제 요청의 콜백 대상과 완료 상태를 함께 보관
private data class PendingPurchase(
    val completion: CompletableDeferred<PurchaseResult>,
    val productIdentifier: String,
    val kind: ProductKind,
    val replacedPurchaseToken: String?,
    val deferredProductIdentifiers: Set<String>,
)

/// 상품 유형의 Google Play 값
private fun ProductKind.billingType(): String = if (this == ProductKind.Subscription) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP

/// 구독 교체 방식의 Google Play 값
private fun SubscriptionReplacementMode.billingValue(): Int = when (this) {
    SubscriptionReplacementMode.WithTimeProration -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION
    SubscriptionReplacementMode.ChargeProratedPrice -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
    SubscriptionReplacementMode.WithoutProration -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.WITHOUT_PRORATION
    SubscriptionReplacementMode.ChargeFullPrice -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE
    SubscriptionReplacementMode.Deferred -> BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
}

/// 스토어 결과를 호출부가 구분할 수 있는 예외로 변환
private fun billingException(result: BillingResult): PurchasesException {
    val retryable = result.responseCode in setOf(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.NETWORK_ERROR, BillingClient.BillingResponseCode.ERROR)
    return PurchasesException(if (retryable) PurchasesErrorCode.Network else PurchasesErrorCode.BillingUnavailable, "Google Play 요청 실패: ${result.responseCode}, ${result.debugMessage}", retryable = retryable)
}

/// Google Play 구매 원본의 내부 스냅샷 생성
private fun Purchase.snapshot(kind: ProductKind): StorePurchase = StorePurchase(
    purchaseToken, products.toSet(), packageName, kind,
    when (purchaseState) { Purchase.PurchaseState.PURCHASED -> StorePurchaseState.Purchased; Purchase.PurchaseState.PENDING -> StorePurchaseState.Pending; else -> StorePurchaseState.Unspecified },
    originalJson, signature, isAcknowledged, purchaseTime, isSuspended,
)

/// 상품 상세에서 선택 가능한 구매 옵션 해석
private fun ProductDetails.resolve(option: PurchaseOption): StorePurchaseOption? {
    if (productType == BillingClient.ProductType.SUBS) {
        val offer = subscriptionOfferDetails.orEmpty().firstOrNull { (option.basePlanIdentifier == null || it.basePlanId == option.basePlanIdentifier) && it.offerId == option.offerIdentifier } ?: return null
        val phases = offer.pricingPhases.pricingPhaseList.map { phase -> PurchasePricingPhase(phase.formattedPrice, phase.priceAmountMicros, phase.priceCurrencyCode, phase.billingPeriod, phase.billingCycleCount, phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING) }
        return StorePurchaseOption(option.copy(basePlanIdentifier = offer.basePlanId), title, description, phases.lastOrNull()?.formattedPrice.orEmpty(), phases, ProductKind.Subscription)
    }
    val offer = oneTimePurchaseOfferDetails ?: return null
    if (option.basePlanIdentifier != null || option.offerIdentifier != null) return null
    return StorePurchaseOption(option, title, description, offer.formattedPrice, listOf(PurchasePricingPhase(offer.formattedPrice, offer.priceAmountMicros, offer.priceCurrencyCode, "", 1, false)), ProductKind.NonConsumable)
}

/// 결제 서비스 자체 시간 초과만 재시도 가능한 오류로 변환
private suspend fun <Value : Any> billingRequest(block: suspend () -> Value): Value = withTimeoutOrNull(30_000) { block() }
    ?: throw PurchasesException(PurchasesErrorCode.Network, "Google Play 응답을 기다리는 시간이 초과되었습니다.", retryable = true)

/// 진행 중인 상품의 명시적인 구매 완료·대기 결과만 결제창 결과로 해석
internal fun resolvePurchaseUpdate(
    productIdentifier: String,
    purchases: List<StorePurchase>,
    replacedPurchaseToken: String? = null,
    deferredProductIdentifiers: Set<String> = emptySet(),
): PurchaseResult? {
    if (purchases.isEmpty()) throw PurchasesException(PurchasesErrorCode.PurchaseNotCompleted, "구매 결과가 비어 있습니다.")
    // 기존 토큰의 갱신 알림은 결제 완료로 삼지 않고 새 교체 구매만 수신한다.
    val matching = purchases.filter { purchase ->
        purchase.purchaseToken != replacedPurchaseToken && (productIdentifier in purchase.productIdentifiers || purchase.productIdentifiers.any { it in deferredProductIdentifiers })
    }
    if (matching.isEmpty()) return null
    return when {
        matching.any { it.state == StorePurchaseState.Purchased && !it.suspended } -> PurchaseResult.Completed
        matching.any { it.state == StorePurchaseState.Pending } -> PurchaseResult.Pending
        else -> throw PurchasesException(PurchasesErrorCode.PurchaseNotCompleted, "구매가 아직 완료되지 않았습니다.")
    }
}
