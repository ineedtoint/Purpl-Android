package sh.purpl

import android.app.Activity
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import sh.purpl.infrastructure.*
import sh.purpl.internal.*
import java.net.URLEncoder
import java.util.Locale

/// Google Play 구매와 Purpl 권한에 접근하는 앱 단위 진입점
public class Purchases private constructor(context: Context, private val configuration: PurchasesConfiguration) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = GooglePlayPurchaseService(applicationContext)
    private val server = PurplServerClient(configuration, applicationContext.packageName)
    private val persistence = NoBackupPurchasesPersistence(applicationContext)
    private val coordinator = PurchasesCoordinator(applicationContext.packageName, configuration, store, server, persistence)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val paywallMutex = Mutex()
    private val mutablePaywallConfigurationUpdates = MutableSharedFlow<PaywallConfigurationUpdate>(extraBufferCapacity = 16)
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        /// 앱 활성화마다 결제 대기 해제와 미완료 확인 처리 재조회
        override fun onStart(owner: LifecycleOwner) { refreshRequests.trySend(Unit) }
    }

    /// 첫 스토어 조회 전에는 null인 현재 구매 기준 고객 정보
    public val customerInfoFlow: StateFlow<CustomerInfo?> = coordinator.customerInfo
    /// 자동 갱신·확인 처리·폴백 과정의 진단 오류
    public val errors: SharedFlow<PurchasesException> = coordinator.errors
    /// 열린 페이월에도 반영할 수 있는 원격 구성 갱신
    public val paywallConfigurationUpdates: SharedFlow<PaywallConfigurationUpdate> = mutablePaywallConfigurationUpdates.asSharedFlow()
    /// 판매 종료 상품을 포함한 앱의 로컬 권한 구성
    public val purchaseConfiguration: PurchaseConfiguration? get() = configuration.purchaseConfiguration

    init {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            store.updates.collect { refreshRequests.trySend(Unit) }
        }
        scope.launch {
            for (ignored in refreshRequests) {
                var retryDelay = 2_000L
                do {
                    var failed = false
                    try { refreshCustomerInfo() } catch (error: CancellationException) { throw error } catch (error: Exception) {
                        val failure = error.asPurchasesException()
                        coordinator.errors.emit(failure)
                        failed = failure.retryable
                    }
                    val retry = failed || coordinator.hasPendingWork()
                    if (retry) {
                        // 앱 실행 중에는 재시도하고 종료 후에는 저장된 작업을 다음 실행에서 복구한다.
                        withTimeoutOrNull(retryDelay) { refreshRequests.receive() }
                        retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                    }
                } while (retry)
            }
        }
        refreshRequests.trySend(Unit)
    }

    /// 지정 카탈로그의 현재 구매 가능한 옵션과 Google Play 가격 조회
    public suspend fun products(catalog: PurchaseCatalog): List<StorePurchaseOption> = store.products(catalog.options, configuration.purchaseConfiguration)

    /// 로컬 권한 구성의 상품 목록 조회
    public suspend fun products(): List<StorePurchaseOption> {
        val products = configuration.purchaseConfiguration?.products
            ?: throw PurchasesException(PurchasesErrorCode.Configuration, "상품 카탈로그 또는 로컬 구성이 필요합니다.")
        return store.products(products.map { PurchaseOption(it.productIdentifier, it.productIdentifier) }, configuration.purchaseConfiguration)
    }

    /// 현재 스토어 구매와 서버를 다시 확인한 고객 정보
    public suspend fun customerInfo(): CustomerInfo = refreshCustomerInfo()

    /// 현재 Google 계정의 구매 복원과 미완료 처리 복구
    public suspend fun restorePurchases(): CustomerInfo = refreshCustomerInfo()

    /// 현재 스토어 구매를 권한 제공자와 동기화
    public suspend fun synchronizePurchases(): CustomerInfo = refreshCustomerInfo()

    /// 구독 변경 시 기존 토큰 선택에 사용하는 현재 완료 구매
    public suspend fun activePurchases(): List<ActivePurchase> = coordinator.activePurchases()

    /// 결제창 표시 후 권한 검증과 Google 확인 처리
    public suspend fun purchase(activity: Activity, option: StorePurchaseOption, replacement: SubscriptionReplacement? = null): PurchaseResult {
        val acceptedProducts = if (replacement?.mode == SubscriptionReplacementMode.Deferred) {
            coordinator.activePurchases().firstOrNull { it.purchaseToken == replacement.purchaseToken }?.productIdentifiers.orEmpty() + option.option.productIdentifier
        } else setOf(option.option.productIdentifier)
        val result = store.purchase(activity, option, replacement, configuration.applicationAccountIdentifier)
        if (result == PurchaseResult.Completed) {
            try {
                val information = refreshCustomerInfo()
                if (information.entitlements.none { it.active && it.productIdentifier in acceptedProducts }) throw PurchasesException(PurchasesErrorCode.PurchaseNotCompleted, "구매 권한이 아직 확인되지 않았습니다.")
            } finally { refreshRequests.trySend(Unit) }
        }
        return result
    }

    /// 캐시 또는 로컬 구성을 먼저 제공하고 원격 구성 갱신
    public suspend fun paywallConfiguration(
        identifier: String,
        localeIdentifier: String = Locale.getDefault().toLanguageTag(),
        fallback: ResolvedPaywallConfiguration? = null,
    ): ResolvedPaywallConfiguration {
        if (configuration.entitlementMode == EntitlementMode.GooglePlay) return fallback
            ?: throw PurchasesException(PurchasesErrorCode.Configuration, "기기 모드에는 로컬 페이월 구성이 필요합니다.")
        val key = "${configuration.serverBaseUrl}|${applicationContext.packageName}|$identifier|$localeIdentifier"
        val cached = persistence.cachedPaywall(key)?.let { raw -> try { decodePaywall(JSONObject(raw)) } catch (_: Exception) { null } }
        if (cached != null) {
            scope.launch { try { fetchPaywall(identifier, localeIdentifier, key) } catch (error: CancellationException) { throw error } catch (error: Exception) { coordinator.errors.emit(error.asPurchasesException()) } }
            return cached
        }
        return try { fetchPaywall(identifier, localeIdentifier, key) } catch (error: CancellationException) { throw error } catch (error: Exception) {
            coordinator.errors.emit(error.asPurchasesException())
            fallback ?: throw error
        }
    }

    /// Google Play 구독 관리 화면 주소
    public fun subscriptionManagementUrl(productIdentifier: String? = null): String {
        val packageName = URLEncoder.encode(applicationContext.packageName, "UTF-8")
        val product = productIdentifier?.let { "&sku=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return "https://play.google.com/store/account/subscriptions?package=$packageName$product"
    }

    /// 명시적인 SDK 연결 종료
    public fun close() {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
            store.close()
            scope.cancel()
        }
        synchronized(instanceLock) { if (sharedInstance === this) sharedInstance = null }
    }

    /// 디스크 작업과 서버 검증이 화면 스레드를 막지 않도록 고객 정보 갱신
    private suspend fun refreshCustomerInfo(): CustomerInfo = withContext(Dispatchers.IO) { coordinator.refresh() }

    /// 중복 원격 요청을 직렬화하고 성공한 페이월 저장
    private suspend fun fetchPaywall(identifier: String, localeIdentifier: String, key: String): ResolvedPaywallConfiguration = paywallMutex.withLock {
        server.paywall(identifier, localeIdentifier).also { configuration ->
            persistence.cachePaywall(key, encodePaywall(configuration))
            mutablePaywallConfigurationUpdates.emit(PaywallConfigurationUpdate(identifier, localeIdentifier, configuration))
        }
    }

    /// 앱 프로세스에서 공유하는 SDK 생성 경계
    public companion object {
        private val instanceLock = Any()
        private var sharedInstance: Purchases? = null

        /// 앱 컨텍스트로 한 번 구성하고 자동 구매 복구 시작
        @JvmStatic
        public fun configure(context: Context, configuration: PurchasesConfiguration): Purchases = synchronized(instanceLock) {
            sharedInstance?.also { require(it.configuration == configuration) { "Purpl이 이미 다른 구성으로 초기화되어 있습니다." } }
                ?: Purchases(context, configuration).also { sharedInstance = it }
        }
    }
}
