package sh.purpl.sample

import android.app.Application
import sh.purpl.*

/// 실제 상품 식별자로 바꿔 사용하는 결제 예제 구성
class SampleApplication : Application() {
    lateinit var purchases: Purchases
        private set

    /// 앱 수명에 맞춘 결제 SDK 초기화
    override fun onCreate() {
        super.onCreate()
        purchases = Purchases.configure(this, PurchasesConfiguration(
            purchaseConfiguration = PurchaseConfiguration(
                entitlements = listOf(PurchaseEntitlement("premium", "Premium")),
                products = listOf(PurchaseProduct("premium", setOf("premium"))),
            ),
            entitlementMode = EntitlementMode.Server,
        ))
    }
}
