package sh.purpl.internal

import org.json.JSONObject
import sh.purpl.PurchasesErrorCode
import sh.purpl.PurchasesException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/// Google Play 앱 공개 키를 사용하는 구매 원본 서명 검증
internal class PurchaseSignatureVerifier(encodedPublicKey: String) {
    private val publicKey: PublicKey = try {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey.filterNot { it.isWhitespace() })))
    } catch (error: Exception) {
        throw PurchasesException(PurchasesErrorCode.Configuration, "Google Play 앱 공개 키 형식이 올바르지 않습니다.", error)
    }

    /// 서명과 원본에 포함된 앱·상품·토큰·구매 완료 상태 검증
    fun verify(purchase: StorePurchase, expectedPackageName: String, knownProducts: Set<String>) {
        if (purchase.state != StorePurchaseState.Purchased || purchase.suspended) {
            throw PurchasesException(PurchasesErrorCode.PurchaseNotCompleted, "완료되지 않았거나 일시 중지된 구매입니다.")
        }
        val validSignature = try {
            Signature.getInstance("SHA1withRSA").run {
                initVerify(publicKey)
                update(purchase.originalJson.toByteArray(Charsets.UTF_8))
                verify(Base64.getDecoder().decode(purchase.signature))
            }
        } catch (_: Exception) { false }
        if (!validSignature) throw PurchasesException(PurchasesErrorCode.PurchaseInvalid, "Google Play 구매 서명을 확인하지 못했습니다.")
        val payload = try { JSONObject(purchase.originalJson) } catch (error: Exception) {
            throw PurchasesException(PurchasesErrorCode.PurchaseInvalid, "구매 원본 형식이 올바르지 않습니다.", error)
        }
        val originalProducts = payload.optJSONArray("productIds")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        } ?: setOfNotNull(payload.optString("productId").takeIf { it.isNotBlank() })
        val originalToken = payload.optString("token").ifBlank { payload.optString("purchaseToken") }
        // 원본 JSON의 구매 완료 값은 Billing Library의 PurchaseState 값과 다르다.
        val originalState = payload.optInt("purchaseState", -1)
        if (payload.optString("packageName") != expectedPackageName || purchase.packageName != expectedPackageName ||
            originalToken != purchase.purchaseToken || originalState != 0 || originalProducts != purchase.productIdentifiers ||
            originalProducts.isEmpty() || !knownProducts.containsAll(originalProducts)) {
            throw PurchasesException(PurchasesErrorCode.PurchaseInvalid, "구매 원본과 앱 또는 상품 구성이 일치하지 않습니다.")
        }
    }
}
