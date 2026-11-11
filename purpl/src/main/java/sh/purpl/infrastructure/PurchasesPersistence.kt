package sh.purpl.infrastructure

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import sh.purpl.ProductKind
import sh.purpl.internal.SynchronizationRequest
import java.io.File

/// 권한 캐시와 분리된 미완료 작업 저장 경계
internal interface PurchasesPersistence {
    /// 아직 서버에서 확인하지 못한 구매
    var pendingSynchronizations: List<SynchronizationRequest>
    /// 권한 확인 후 아직 완료되지 않은 Google 확인 처리
    var pendingAcknowledgements: Set<String>
    /// 정상 서버 응답에서 비활성 또는 구매 거부를 확인한 토큰
    var serverInactivePurchaseTokens: Set<String>
    /// 페이월 응답 캐시 조회
    fun cachedPaywall(key: String): String?
    /// 페이월 응답 캐시 저장
    fun cachePaywall(key: String, value: String)
}

/// 백업에서 제외하고 원자적으로 기록하는 구매 재시도 저장소
internal class NoBackupPurchasesPersistence(context: Context) : PurchasesPersistence {
    private val lock = Any()
    private val file = AtomicFile(File(context.noBackupFilesDir, "purpl-purchase-tasks.json"))
    private val preferences = context.getSharedPreferences("sh.purpl.paywalls", Context.MODE_PRIVATE)
    private val state = try { JSONObject(file.openRead().bufferedReader().use { it.readText() }) } catch (_: Exception) { JSONObject() }
    override var pendingSynchronizations: List<SynchronizationRequest>
        /// 저장된 서버 동기화 목록 읽기
        get() = synchronized(lock) {
            val items = state.optJSONArray("synchronizations") ?: JSONArray()
            (0 until items.length()).map { index -> items.getJSONObject(index).let { item ->
                SynchronizationRequest(item.getString("token"), item.getString("product"), ProductKind.valueOf(item.getString("kind")), item.optString("account").takeIf { it.isNotEmpty() }, item.optString("republication").takeIf { it.isNotEmpty() })
            } }
        }
        /// 다음 프로세스에서 복구할 서버 동기화 목록 저장
        set(value) = synchronized(lock) { state.put("synchronizations", JSONArray(value.map { request -> JSONObject().put("token", request.purchaseToken).put("product", request.productIdentifier).put("kind", request.kind.name).put("account", request.applicationAccountIdentifier).put("republication", request.republicationRequestIdentifier) })); persist() }
    override var pendingAcknowledgements: Set<String>
        /// 저장된 확인 처리 목록 읽기
        get() = readTokens("acknowledgements")
        /// 다음 프로세스에서 복구할 확인 처리 목록 저장
        set(value) = writeTokens("acknowledgements", value)
    override var serverInactivePurchaseTokens: Set<String>
        /// 오류 차단이 섞인 이전 키 대신 확정된 비활성 기록만 읽기
        get() = readTokens("serverInactivePurchaseTokens")
        /// 정상 서버 응답의 비활성·구매 거부 기록 보존
        set(value) = synchronized(lock) {
            state.remove("fallbackBlockedTokens")
            writeTokens("serverInactivePurchaseTokens", value)
        }
    /// 저장된 페이월 응답 읽기
    override fun cachedPaywall(key: String): String? = preferences.getString("paywall:$key", null)
    /// 성공한 페이월 응답 저장
    override fun cachePaywall(key: String, value: String) { preferences.edit().putString("paywall:$key", value).apply() }
    /// 토큰 목록의 안전한 복사본 읽기
    private fun readTokens(name: String): Set<String> = synchronized(lock) {
        val items = state.optJSONArray(name) ?: JSONArray()
        (0 until items.length()).map { items.getString(it) }.toSet()
    }
    /// 토큰 목록을 독립적인 항목으로 기록
    private fun writeTokens(name: String, value: Set<String>) = synchronized(lock) { state.put(name, JSONArray(value.toList())); persist() }
    /// 구매 작업 변경을 확인 처리 전에 디스크에 확정
    private fun persist() {
        val output = file.startWrite()
        try { output.write(state.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
