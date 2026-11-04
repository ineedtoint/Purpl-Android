package sh.purpl.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.purpl.*
import java.time.Period

/// 앱에서 조정하는 페이월 색상과 카드 강조
public data class PaywallStyle(
    val tintColor: Color = Color.Unspecified,
    val containerColor: Color = Color.Unspecified,
    val productCardColor: Color = Color.Unspecified,
)

/// 사용자 정의 구매 카드에 전달하는 실제 스토어 옵션
public data class PaywallProductContext(
    val option: StorePurchaseOption,
    val selected: Boolean,
    val enabled: Boolean,
    val title: String,
    val description: String? = null,
    val isOwned: Boolean = false,
)

/// 로컬 구성 또는 원격 페이월과 선택 폴백을 사용하는 구매 화면
@Composable
public fun PaywallView(
    purchases: Purchases,
    configuration: PaywallConfiguration? = null,
    paywallIdentifier: String? = null,
    modifier: Modifier = Modifier,
    isIncluded: (StorePurchaseOption) -> Boolean = { true },
    style: PaywallStyle = PaywallStyle(),
    replacement: suspend (StorePurchaseOption) -> SubscriptionReplacement? = { null },
    onPurchaseCompleted: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val model = remember(purchases, configuration, paywallIdentifier) { PaywallModel(purchases, configuration, paywallIdentifier) }
    PaywallView(model, modifier, isIncluded, style, replacement, onPurchaseCompleted, content = content)
}

/// 상품 선택·구매·복원과 사용자 정의 콘텐츠를 표시하는 페이월
@Composable
public fun PaywallView(
    model: PaywallModel,
    modifier: Modifier = Modifier,
    isIncluded: (StorePurchaseOption) -> Boolean = { true },
    style: PaywallStyle = PaywallStyle(),
    replacement: suspend (StorePurchaseOption) -> SubscriptionReplacement? = { null },
    onPurchaseCompleted: () -> Unit = {},
    productCard: (@Composable (PaywallProductContext, () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val options = state.options.filter(isIncluded)
    val selected = options.firstOrNull { it.identifier == state.selectedOptionIdentifier } ?: options.firstOrNull()
    val ownership = selected?.let { purchaseOwnership(it, state.customerInfo) } ?: PaywallPurchaseOwnership.Available
    val tint = style.tintColor.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.primary
    val background = style.containerColor.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.background
    val cardColor = style.productCardColor.takeUnless { it == Color.Unspecified } ?: MaterialTheme.colorScheme.surfaceContainerLow
    LaunchedEffect(model) { model.observeConfigurationUpdates() }
    LaunchedEffect(model) { model.observeCustomerInfo() }
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(selected?.identifier) { selected?.let { model.select(it.identifier) } }

    Surface(modifier, color = background) {
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content) }
            if (state.loading) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tint) }
            }
            if (!state.loading && options.isEmpty() && state.error == null) item {
                Text(stringResource(R.string.purpl_no_products), Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { model.load() } }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text(stringResource(R.string.purpl_retry)) }
            }
            items(options, key = { it.identifier }) { option ->
                val display = state.configuration?.productDisplayContents?.firstOrNull { it.productIdentifier == option.option.productIdentifier }
                val productTitle = display?.title ?: option.title
                val title = if (option.kind == ProductKind.Subscription) "$productTitle · ${billingPeriodText(option.billingPeriod)}" else productTitle
                val context = PaywallProductContext(option, option.identifier == selected?.identifier, !state.busy, title, display?.description,
                    isOwned = purchaseOwnership(option, state.customerInfo) == PaywallPurchaseOwnership.Owned)
                if (productCard != null) productCard(context) { model.select(option.identifier) }
                else DefaultPaywallProductCard(context, tint, cardColor) { model.select(option.identifier) }
            }
            state.error?.let { error -> item {
                Text(purchaseErrorText(error), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { model.load() } }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text(stringResource(R.string.purpl_retry)) }
            } }
            state.notice?.let { notice -> item {
                val message = when (notice) {
                    PaywallNotice.Purchased -> R.string.purpl_purchased
                    PaywallNotice.Pending -> R.string.purpl_pending
                    PaywallNotice.Restored -> R.string.purpl_restored
                    PaywallNotice.NoPurchases -> R.string.purpl_no_purchases
                }
                Text(stringResource(message), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } }
            if (ownership == PaywallPurchaseOwnership.UnknownBasePlan) item {
                Text(stringResource(R.string.purpl_current_period_unknown), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = {
                    val purchaseActivity = activity
                    val option = selected
                    if (purchaseActivity != null && option != null) scope.launch {
                        if (model.purchase(purchaseActivity, option, replacement) == PurchaseResult.Completed) onPurchaseCompleted()
                    }
                }, enabled = selected != null && activity != null && !state.busy && !state.loading && ownership == PaywallPurchaseOwnership.Available,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = tint)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text(stringResource(if (ownership == PaywallPurchaseOwnership.Available) R.string.purpl_continue else R.string.purpl_current_plan))
                }
            }
            item {
                if (selected?.kind == ProductKind.Subscription || state.configuration?.catalog?.options?.any { it.basePlanIdentifier != null } == true) Text(state.configuration?.autoRenewalNotice ?: stringResource(R.string.purpl_renewal_notice),
                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { model.restore() } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.purpl_restore)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    state.configuration?.privacyPolicyUrl?.let { url -> TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.purpl_privacy)) } }
                    state.configuration?.termsOfServiceUrl?.let { url -> TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.purpl_terms)) } }
                }
            }
        }
    }
}

/// 실제 가격 단계와 선택 상태를 표시하는 기본 상품 카드
@Composable
public fun DefaultPaywallProductCard(context: PaywallProductContext, tintColor: Color, backgroundColor: Color, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = context.enabled, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(if (context.selected) 2.dp else 1.dp, if (context.selected) tintColor else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(context.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(context.option.formattedPrice, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                RadioButton(context.selected, null, enabled = context.enabled, colors = RadioButtonDefaults.colors(selectedColor = tintColor))
            }
            if (context.isOwned) Text(stringResource(R.string.purpl_current_plan), style = MaterialTheme.typography.labelMedium, color = tintColor)
            if (context.option.kind == ProductKind.Subscription) context.option.pricingPhases.forEach { phase ->
                val period = billingPeriodText(phase.billingPeriod)
                val phaseText = if (phase.recurring) stringResource(R.string.purpl_recurring_price, phase.formattedPrice, period)
                    else if (phase.billingCycleCount > 0) stringResource(R.string.purpl_intro_price, phase.formattedPrice, period, phase.billingCycleCount)
                    else phase.formattedPrice
                Text(phaseText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            context.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/// 스토어 결제 주기의 현지화된 표시
@Composable
private fun billingPeriodText(value: String?): String {
    val period = value?.let { runCatching { Period.parse(it) }.getOrNull() }
    return when {
        period == null -> stringResource(R.string.purpl_one_time)
        period.years > 0 -> stringResource(R.string.purpl_years, period.years)
        period.months > 0 -> stringResource(R.string.purpl_months, period.months)
        period.days > 0 && period.days % 7 == 0 -> stringResource(R.string.purpl_weeks, period.days / 7)
        else -> stringResource(R.string.purpl_days, period.days)
    }
}

/// 내부 오류 상세를 노출하지 않는 구매 안내 문구
@Composable
private fun purchaseErrorText(error: Throwable): String = stringResource(when ((error as? PurchasesException)?.code) {
    PurchasesErrorCode.ProductUnavailable -> R.string.purpl_no_products
    PurchasesErrorCode.Network -> R.string.purpl_network_error
    PurchasesErrorCode.BillingUnavailable -> R.string.purpl_billing_error
    PurchasesErrorCode.PurchasePending -> R.string.purpl_pending
    PurchasesErrorCode.PurchaseInProgress -> R.string.purpl_in_progress
    else -> R.string.purpl_purchase_error
})
