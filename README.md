# Purpl Android

English | [한국어](README.ko.md) | [Docs](https://purpl.sh/en/docs) | [Android API Reference](https://android.purpl.sh/)

Purpl is a Kotlin SDK for Google Play purchases, entitlement checks, restoration, and Jetpack Compose paywalls.

You can use purchases, restoration, entitlement checks, and local paywalls without adopting the Purpl management service. When you connect the service, the same paywall UI can present catalogs configured on the web and customer entitlements can be verified on the server. Google Play remains the source of truth for product information, pricing, and purchase availability.

## Requirements

- Android 8.0 (API 26)+
- A Kotlin Android project; Jetpack Compose when using `purpl-ui`
- Google Play products and test accounts for purchase testing

This repository uses Android Gradle Plugin 9.3.3, Kotlin 2.4.20, Gradle 9.5.0, and Android SDK 37.

## Installation

Clone the repository next to your app project.

```sh
git clone https://github.com/ineedtoint/Purpl-Android.git
```

Add a composite build in your app's `settings.gradle.kts`. Adjust the path to the cloned repository.

```kotlin
includeBuild("../Purpl-Android")
```

Add the modules to your app's `build.gradle.kts`.

```kotlin
dependencies {
    implementation("sh.purpl:purpl:0.1.0")
    implementation("sh.purpl:purpl-ui:0.1.0")
}
```

Use `purpl` for purchases and entitlement management. Add `purpl-ui` for the default Compose paywall. Google Play Billing is included through the core module, so your app does not need to declare it separately.

The SDK has not been published to Maven Central. The coordinates above resolve through the local composite build; creating or cloning the GitHub repository does not make them available from a Maven repository.

## Example

The [sample app](sample) includes a Compose paywall with monthly and yearly purchase options. Open this repository in Android Studio and select the `sample` run configuration.

Before testing purchases, replace the sample package name, product IDs, and base plan IDs with your Play Console configuration. The sample defaults to `EntitlementMode.Server`, which requires a matching Purpl application and product configuration. To use Google Play directly, select `EntitlementMode.GooglePlay` and provide your app's Play Console public key.

The sample uses Google Play rather than simulated local products. Configure a Play test track and license testers as described in the [Google Play testing guide](https://developer.android.com/google/play/billing/test).

## Local configuration

Register every product used by your app and the entitlements granted by each product in `PurchaseConfiguration`. Keep discontinued products in this configuration so existing purchases can still be restored and recognized. Configure `Purchases` once when your `Application` starts, and keep the returned instance for your app's UI. The initialization examples below show alternative modes.

```kotlin
import sh.purpl.*

val plusEntitlement = PurchaseEntitlement(identifier = "plus")
val subscriptionProduct = PurchaseProduct(
    productIdentifier = "plus.v1",
    entitlementIdentifiers = setOf(plusEntitlement.identifier),
)
val legacyLifetimeProduct = PurchaseProduct(
    productIdentifier = "plus.lifetime.legacy",
    entitlementIdentifiers = setOf(plusEntitlement.identifier),
    kind = ProductKind.NonConsumable,
)
val purchaseConfiguration = PurchaseConfiguration(
    entitlements = listOf(plusEntitlement),
    products = listOf(subscriptionProduct, legacyLifetimeProduct),
)

val purchases = Purchases.configure(
    context = context,
    configuration = PurchasesConfiguration(
        purchaseConfiguration = purchaseConfiguration,
        entitlementMode = EntitlementMode.GooglePlay,
        googlePlayPublicKey = "YOUR_GOOGLE_PLAY_PUBLIC_KEY",
    ),
)
```

Replace the public key placeholder with the Base64 RSA app public key from Play Console. Google Play mode validates current purchases using that key and the local product-to-entitlement mapping. It does not require a Purpl account or server. Consumable products and external payments are not supported.

Define the purchase options shown on a paywall and their display order in a separate `PurchaseCatalog`. A Google subscription product can have multiple base plans, such as monthly and yearly. Removing a discontinued product from the catalog keeps its entitlement mapping while excluding it from new sales.

```kotlin
import androidx.compose.material3.Text
import sh.purpl.*
import sh.purpl.ui.*

val standardCatalog = PurchaseCatalog(
    identifier = "standard",
    options = listOf(
        PurchaseOption("plus-monthly", "plus.v1", basePlanIdentifier = "monthly"),
        PurchaseOption("plus-yearly", "plus.v1", basePlanIdentifier = "yearly"),
    ),
)

PaywallView(
    purchases = purchases,
    configuration = PaywallConfiguration(
        catalog = standardCatalog,
        defaultPurchaseOptionIdentifier = "plus-yearly",
    ),
) {
    Text("Plus")
}
```

Use `PaywallView` inside a composable. `offerIdentifier = null` selects the base plan without an offer; specify an offer ID when you want to sell that offer. Only options available to the current Google Play user are returned. A non-consumable purchase option has no base plan or offer ID.

Read entitlements and restore purchases from a coroutine.

```kotlin
val customerInfo = purchases.customerInfo()
val hasPlusAccess = customerInfo.isEntitlementActive("plus")
val restoredCustomerInfo = purchases.restorePurchases()
```

Observe `customerInfoFlow` for entitlement changes; its value is `null` before the first successful refresh. The `errors` flow reports background synchronization and acknowledgement failures. A failed query is not treated as an empty purchase history.

Set `purchaseNoticeResource` or `purchaseNoticeText` on `PaywallConfiguration` to show a purchase notice. The localized resource takes precedence when both are supplied. There is no SDK default text: a nonblank notice appears above the purchase button in the fixed bottom area for any product type, followed by the restore and policy links. Remote `purchaseNotice` replaces the local notice, including when it is empty or absent.

## Purpl managed configuration

To manage customer entitlements and paywall catalogs with the Purpl service, configure server mode and pass the paywall identifier defined on the web.

```kotlin
val purchases = Purchases.configure(
    context = context,
    configuration = PurchasesConfiguration(entitlementMode = EntitlementMode.Server),
)

PaywallView(purchases = purchases, paywallIdentifier = "standard") {
    Text("Plus")
}
```

Remote configuration is resolved using the app's package name, paywall identifier, and locale. Purpl supplies the catalog, product-to-entitlement mapping, localized content, purchase notices, and policy links. Google Play still handles product loading, pricing, availability, and purchases. Do not put Google service account credentials in the app.

To provide a local paywall while remote configuration is loading or unavailable, pass the app's catalog as `configuration` alongside `paywallIdentifier`.

```kotlin
PaywallView(
    purchases = purchases,
    paywallIdentifier = "standard",
    configuration = PaywallConfiguration(
        catalog = standardCatalog,
        defaultPurchaseOptionIdentifier = "plus-yearly",
    ),
) {
    Text("Plus")
}
```

`PaywallModel` loads the local catalog first, then applies cached or fetched remote configuration. Remote refreshes are delivered through `paywallConfigurationUpdates` and reflected in the default UI. The paywall fallback controls presentation; entitlement verification uses the mode selected at initialization.

| Mode | Entitlement verification | Required configuration |
| --- | --- | --- |
| `GooglePlay` | Current Play purchases and purchase signature verification | Local purchase configuration and app public key |
| `Server` | Purpl verifies purchase tokens with Google | Purpl application, store, products, and entitlements |
| `ServerWithGooglePlayFallback` | Server first, device verification on server failure | Both configurations above |

For entitlement fallback, initialize with `ServerWithGooglePlayFallback`, `purchaseConfiguration`, and `googlePlayPublicKey`. Server errors, network failures, and malformed responses can fall back to device verification. Definitive inactive, invalid, pending, or incomplete purchase results do not regain access through fallback.

Device verification does not provide a reliable subscription expiry date or current base plan and offer IDs; those fields remain `null`. Server-verified entitlements can include `expiresAt`, `basePlanIdentifier`, and `offerIdentifier`. Entitlements from multiple current purchase tokens are combined.

## Purchases and subscription changes

For a custom purchase screen, load options and purchase from a coroutine. Handle `PurchasesException` in your app's UI.

```kotlin
val options = purchases.products(standardCatalog)
val selectedOption = options.firstOrNull()
if (selectedOption != null) {
    val result = purchases.purchase(activity, selectedOption)
}
```

The result is `Completed`, `Pending`, or `Cancelled`. A pending purchase does not grant access or get acknowledged. For completed purchases, the SDK verifies entitlement and sends acknowledgement to Google Play. Failed server synchronization and acknowledgement are stored independently in the app's `noBackupFilesDir` and retried while the app is running or when it resumes. The SDK does not execute while the app process is stopped; see Google's [purchase processing requirements](https://developer.android.com/google/play/billing/integrate#process).

For subscription changes, select the existing purchase from `activePurchases()` and pass its token and your app's replacement policy. The SDK does not infer which entitlement is an upgrade or downgrade.

```kotlin
val previousPurchase = purchases.activePurchases().singleOrNull()
if (selectedOption != null && previousPurchase != null) {
    val replacement = SubscriptionReplacement(
        purchaseToken = previousPurchase.purchaseToken,
        mode = SubscriptionReplacementMode.WithTimeProration,
    )
    val result = purchases.purchase(activity, selectedOption, replacement)
}
```

Choose a replacement mode supported by the product and base plan combination. Open Google Play subscription management from your app's settings with `purchases.subscriptionManagementUrl(productIdentifier)`. App account sign-in and linking remain the app's responsibility; `applicationAccountIdentifier` is optional.

## Presentation ownership

`PaywallView` owns product presentation, purchase actions, restoration, loading, and retry states. Your app owns navigation, tabs, toolbars, and close buttons. Use `isIncluded` to show a subset of the catalog without changing entitlement or restoration behavior.

```kotlin
PaywallView(
    purchases = purchases,
    configuration = PaywallConfiguration(catalog = standardCatalog),
    isIncluded = { option -> option.option.productIdentifier == "plus.v1" },
) {
    Text("Plus")
}
```

Use `PaywallStyle` and the content slot to match your app's design. For custom product cards, create a `PaywallModel` and use `PaywallView(model = ..., productCard = ...)`. Supply the `replacement` callback when the paywall needs to change an existing subscription.

The default UI observes customer entitlements, marks the current option, and blocks duplicate purchases. If device fallback cannot identify the current base plan, it does not guess monthly or yearly ownership and blocks repurchasing that same product until the period is resolved. When no products are available, it shows a message and retry action and disables purchasing. Subscription management belongs in your app's settings rather than the default paywall.

Google's default selection uses `defaultPurchaseOptionIdentifier`. The SDK does not infer a base plan from the legacy `defaultProductIdentifier`. If neither the previous selection nor the configured default is available, the UI selects the first available option. Policy links are displayed only when configured by the app or Purpl.

The UI includes English, Korean, Japanese, German, Simplified Chinese, and Traditional Chinese.

## API reference

Browse the [Purpl Android API Reference](https://android.purpl.sh/) online. It is generated with Dokka from the KDoc in `purpl` and `purpl-ui`. Run `./gradlew :dokkaGenerate` to build the same reference locally in `build/dokka/html`.

For Apple platforms, see [Purpl Swift](https://github.com/ineedtoint/Purpl-Swift).

## Development

Set `sdk.dir` in an untracked `local.properties` file or configure `ANDROID_HOME`, then run:

```sh
./gradlew :purpl:testDebugUnitTest :purpl-ui:testDebugUnitTest :purpl:assembleRelease :purpl-ui:assembleRelease :sample:assembleDebug
```

For Play Console setup and purchase testing, see Google's [billing setup](https://developer.android.com/google/play/billing/getting-ready) and [testing guide](https://developer.android.com/google/play/billing/test).
