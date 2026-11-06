package sh.purpl.ui

import android.app.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import sh.purpl.CustomerInfo
import sh.purpl.PurchaseResult
import sh.purpl.Purchases
import sh.purpl.StorePurchaseOption
import sh.purpl.SubscriptionReplacement

// 페이월에서 표시하는 구매와 복원 결과
/** Purchase and restoration results that the paywall displays. */
public enum class PaywallNotice {
    // 구매 완료
    /** The purchase completed. */
    Purchased,
    // 결제 완료 대기
    /** The purchase is waiting for payment to complete. */
    Pending,
    // 구매 복원 완료
    /** Purchases were restored. */
    Restored,
    // 복원할 구매 없음
    /** No purchases were found to restore. */
    NoPurchases,
}

// 구매 화면의 상품과 진행 상태
/** The products and progress state of a paywall. */
public data class PaywallState(
    // 현재 표시 중인 페이월 구성
    /** The paywall configuration currently displayed. */
    val configuration: PaywallConfiguration? = null,
    // 스토어에서 조회한 구매 가능한 옵션
    /** The purchase options currently available from the store. */
    val options: List<StorePurchaseOption> = emptyList(),
    // 선택한 구매 옵션 식별자
    /** The identifier of the selected purchase option. */
    val selectedOptionIdentifier: String? = null,
    // 상품 조회 진행 여부
    /** Whether products are loading. */
    val loading: Boolean = true,
    // 구매 또는 복원 진행 여부
    /** Whether a purchase or restoration is in progress. */
    val busy: Boolean = false,
    // 마지막으로 발생한 오류
    /** The most recent error, if any. */
    val error: Throwable? = null,
    // 표시할 마지막 구매 또는 복원 결과
    /** The most recent purchase or restoration result to display. */
    val notice: PaywallNotice? = null,
    // 최신 고객 정보
    /** The latest customer information. */
    val customerInfo: CustomerInfo? = null,
)

// 로컬·원격 구성과 구매·복원의 화면 흐름 조율
/**
 * Coordinates local and remote paywall configuration with the purchase and restoration flow.
 *
 * @param localConfiguration The local paywall configuration, or the fallback for a remote paywall.
 * @param paywallIdentifier The remote paywall identifier created in Purpl.
 */
public class PaywallModel(
    // 구매와 권한 확인에 사용하는 Purpl 인스턴스
    /** The Purpl instance used for purchases and entitlement checks. */
    public val purchases: Purchases,
    private val localConfiguration: PaywallConfiguration? = null,
    private val paywallIdentifier: String? = null,
) {
    private val mutableState = MutableStateFlow(PaywallState(configuration = localConfiguration, customerInfo = purchases.customerInfoFlow.value))
    private var loadingRequest = false
    private val productLoadMutex = Mutex()
    // 현재 페이월 상태
    /** The current paywall state. */
    public val state: StateFlow<PaywallState> = mutableState.asStateFlow()

    init { require(localConfiguration != null || paywallIdentifier != null) }

    // 로컬 폴백을 먼저 표시하고 원격 구성과 실제 스토어 상품 조회
    /** Shows the local fallback first, then loads the remote configuration and actual store products. */
    public suspend fun load() {
        if (loadingRequest || state.value.busy) return
        loadingRequest = true
        try {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            var configuration = localConfiguration
            if (configuration != null) loadProducts(configuration)
            if (paywallIdentifier != null) {
                try {
                    // 가격은 원격 구성에 저장하지 않고 스토어에서 다시 조회한다.
                    configuration = PaywallConfiguration.from(purchases.paywallConfiguration(paywallIdentifier))
                    loadProducts(configuration, preservePreviousOnFailure = state.value.options.isNotEmpty())
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (configuration == null) mutableState.value = mutableState.value.copy(loading = false, error = error)
                }
            }
        } finally { loadingRequest = false }
    }

    /// 구성 순서와 기존 선택을 유지하는 스토어 상품 갱신
    private suspend fun loadProducts(configuration: PaywallConfiguration, preservePreviousOnFailure: Boolean = false) = productLoadMutex.withLock {
        try {
            val options = purchases.products(configuration.catalog)
            val previousSelection = mutableState.value.selectedOptionIdentifier
            val selectedIdentifier = previousSelection?.takeIf { identifier -> options.any { it.identifier == identifier } }
                ?: configuration.defaultPurchaseOptionIdentifier?.takeIf { identifier -> options.any { it.identifier == identifier } }
                ?: options.firstOrNull()?.identifier
            mutableState.value = mutableState.value.copy(configuration = configuration, options = options, selectedOptionIdentifier = selectedIdentifier, loading = false, error = null)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (!preservePreviousOnFailure) mutableState.value = mutableState.value.copy(loading = false, error = error)
        }
    }

    // 캐시를 표시한 뒤 도착한 원격 페이월 변경 반영
    /** Applies remote paywall changes that arrive after a cached configuration is shown. */
    public suspend fun observeConfigurationUpdates() {
        val identifier = paywallIdentifier ?: return
        purchases.paywallConfigurationUpdates.collect { update ->
            if (update.identifier == identifier && update.localeIdentifier == Locale.getDefault().toLanguageTag()) {
                state.filter { !it.busy }.first()
                val configuration = PaywallConfiguration.from(update.configuration)
                if (configuration != state.value.configuration) loadProducts(configuration, preservePreviousOnFailure = state.value.options.isNotEmpty())
            }
        }
    }

    // 구매·복원·앱 복귀로 변경된 최신 고객 정보 반영
    /** Applies the latest customer information after purchases, restoration, or returning to the app. */
    public suspend fun observeCustomerInfo() {
        purchases.customerInfoFlow.collect { customerInfo ->
            mutableState.value = mutableState.value.copy(customerInfo = customerInfo)
        }
    }

    // 현재 판매 가능한 구매 옵션 선택
    /** Selects a purchase option that is currently available. */
    public fun select(identifier: String) {
        if (!state.value.busy && state.value.options.any { it.identifier == identifier }) {
            mutableState.value = state.value.copy(selectedOptionIdentifier = identifier, notice = null)
        }
    }

    // 선택한 옵션의 구매와 앱에서 정한 구독 교체 정책 적용
    /**
     * Purchases an option, applying the subscription replacement policy chosen by the app.
     *
     * @param activity The activity that presents the purchase flow.
     * @param option The purchase option to buy.
     * @param replacement Returns the existing subscription to replace, or `null` for a new purchase.
     * @return The purchase result, or `null` when the purchase did not start or failed. Failures are reported in [state].
     */
    public suspend fun purchase(
        activity: Activity,
        option: StorePurchaseOption,
        replacement: suspend (StorePurchaseOption) -> SubscriptionReplacement? = { null },
    ): PurchaseResult? {
        if (state.value.busy || purchaseOwnership(option, purchases.customerInfoFlow.value) != PaywallPurchaseOwnership.Available) return null
        mutableState.value = state.value.copy(busy = true, error = null, notice = null)
        try {
            // 실제 결제와 권한 반영 및 확인 처리는 핵심 SDK가 담당한다.
            val subscriptionReplacement = replacement(option)
            if (purchaseOwnership(option, purchases.customerInfoFlow.value) != PaywallPurchaseOwnership.Available) return null
            val result = purchases.purchase(activity, option, subscriptionReplacement)
            mutableState.value = state.value.copy(customerInfo = purchases.customerInfoFlow.value, notice = when (result) {
                PurchaseResult.Completed -> PaywallNotice.Purchased
                PurchaseResult.Pending -> PaywallNotice.Pending
                PurchaseResult.Cancelled -> null
            })
            return result
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableState.value = state.value.copy(error = error); return null }
        finally { mutableState.value = state.value.copy(busy = false) }
    }

    // 보유 구매 복원과 최신 권한 결과 표시
    /**
     * Restores owned purchases and shows the latest entitlement result.
     *
     * @return The restored customer information, or `null` when restoration did not start or failed. Failures are reported in [state].
     */
    public suspend fun restore(): CustomerInfo? {
        if (state.value.busy) return null
        mutableState.value = state.value.copy(busy = true, error = null, notice = null)
        try {
            val customerInfo = purchases.restorePurchases()
            mutableState.value = state.value.copy(customerInfo = customerInfo, notice = if (customerInfo.activeEntitlementIdentifiers.isEmpty()) PaywallNotice.NoPurchases else PaywallNotice.Restored)
            return customerInfo
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableState.value = state.value.copy(error = error); return null }
        finally { mutableState.value = state.value.copy(busy = false) }
    }
}
