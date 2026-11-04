package sh.purpl.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import sh.purpl.PurchaseCatalog
import sh.purpl.PurchaseOption
import sh.purpl.ui.PaywallConfiguration
import sh.purpl.ui.PaywallView

/// 앱 콘텐츠를 넣은 Compose 페이월 예제
class MainActivity : ComponentActivity() {
    /// 월간·연간 기본 요금제를 표시하는 결제 화면 연결
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val purchases = (application as SampleApplication).purchases
        setContent {
            val configuration = remember { PaywallConfiguration(
                catalog = PurchaseCatalog("default", listOf(
                    PurchaseOption("monthly", "premium", "monthly"),
                    PurchaseOption("yearly", "premium", "yearly"),
                )), defaultPurchaseOptionIdentifier = "yearly",
            ) }
            MaterialTheme {
                PaywallView(purchases, configuration, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("Purpl", style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
    }
}
