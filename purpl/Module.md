# Module purpl

Google Play purchases, customer entitlement checks, restoration, and Purpl service integration.

Call `Purchases.configure` once when the app starts and share the returned instance across the app. Choose an `EntitlementMode` to verify entitlements on the device with Google Play, on the Purpl server, or on the server with a device fallback.

# Package sh.purpl

The SDK entry point, purchase and entitlement configuration, customer information, and purchase results.

Start with [Purchases], then define products and entitlements with [PurchaseConfiguration] and the options to sell with [PurchaseCatalog]. Read access from [CustomerInfo].
