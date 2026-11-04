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

/// 페이월에서 표시하는 구매와 복원 결과
public enum class PaywallNotice { Purchased, Pending, Restored, NoPurchases }

/// 구매 화면의 상품과 진행 상태
public data class PaywallState(
    val configuration: PaywallConfiguration? = null,
    val options: List<StorePurchaseOption> = emptyList(),
    val selectedOptionIdentifier: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: Throwable? = null,
    val notice: PaywallNotice? = null,
    val customerInfo: CustomerInfo? = null,
)

/// 로컬·원격 구성과 구매·복원의 화면 흐름 조율
public class PaywallModel(
    public val purchases: Purchases,
    private val localConfiguration: PaywallConfiguration? = null,
    private val paywallIdentifier: String? = null,
) {
    private val mutableState = MutableStateFlow(PaywallState(configuration = localConfiguration, customerInfo = purchases.customerInfoFlow.value))
    private var loadingRequest = false
    private val productLoadMutex = Mutex()
    public val state: StateFlow<PaywallState> = mutableState.asStateFlow()

    init { require(localConfiguration != null || paywallIdentifier != null) }

    /// 로컬 폴백을 먼저 표시하고 원격 구성과 실제 스토어 상품 조회
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

    /// 캐시를 표시한 뒤 도착한 원격 페이월 변경 반영
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

    /// 구매·복원·앱 복귀로 변경된 최신 고객 정보 반영
    public suspend fun observeCustomerInfo() {
        purchases.customerInfoFlow.collect { customerInfo ->
            mutableState.value = mutableState.value.copy(customerInfo = customerInfo)
        }
    }

    /// 현재 판매 가능한 구매 옵션 선택
    public fun select(identifier: String) {
        if (!state.value.busy && state.value.options.any { it.identifier == identifier }) {
            mutableState.value = state.value.copy(selectedOptionIdentifier = identifier, notice = null)
        }
    }

    /// 선택한 옵션의 구매와 앱에서 정한 구독 교체 정책 적용
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

    /// 보유 구매 복원과 최신 권한 결과 표시
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
