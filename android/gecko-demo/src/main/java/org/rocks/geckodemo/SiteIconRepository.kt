package org.rocks.geckodemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.mozilla.gecko.util.ImageDecoder
import org.mozilla.geckoview.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Gecko 158 在响应头之前不保证取消成功；跨模型保留名额，直到迟到的原生响应真正结束。 */
private object NativeIconNetworkBudget { val gate = Semaphore(2) }

/** 原生匿名图标仓库：不接入扩展消息、不读取网页 Cookie，也不使用第三方图标服务。 */
internal class SiteIconRepository(runtime: GeckoRuntime, cacheDir: File, private val scope: CoroutineScope) {
    private val executor = GeckoWebExecutor(runtime)
    private val directory = File(cacheDir, "site-icons")
    private val disk = SiteIconDiskCache(directory)
    private val memory = SiteIconMemoryCache<Bitmap>(SiteIconPolicy.MEMORY_LIMIT) { it.allocationByteCount }
    private val gate = Semaphore(2)
    private val lock = Mutex()
    private data class Pending(val work: Deferred<Bitmap?>, var users: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    var active by mutableStateOf(true)
        private set

    /** 同网址共享请求；最后一个可见使用者离开后取消未完成工作，迟到结果不会绑定到其他行。 */
    suspend fun load(url: String): Bitmap? {
        if (!active) return null
        val safe = SiteIconPolicy.safeUrl(url) ?: return null
        val key = SiteIconPolicy.key(safe)
        memory.get(key, System.currentTimeMillis()).let { if (it.first) return it.second }
        val request = lock.withLock {
            pending.getOrPut(key) { Pending(scope.async(start = CoroutineStart.LAZY) { gate.withPermit { loadFresh(safe, key) } }) }
                .also { it.users++ }
        }
        try { return request.work.await() }
        finally {
            withContext(NonCancellable) {
                lock.withLock {
                    request.users--
                    if (request.users == 0) {
                        if (!request.work.isCompleted) request.work.cancel()
                        if (pending[key] === request) pending.remove(key)
                    }
                }
            }
        }
    }

    /** 缓存失败和网络失败都只触发首字图块，不向用户显示加载错误或阻塞资料页。 */
    private suspend fun loadFresh(url: String, key: String): Bitmap? {
        val now = System.currentTimeMillis()
        val cached = withContext(Dispatchers.IO) { disk.read(key, now) }
        if (cached != null) {
            val bitmap = cached.png?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (cached.png == null || bitmap != null) { memory.put(key, bitmap, cached.expires); return bitmap }
        }
        var bitmap: Bitmap? = null
        try {
            val html = try { fetch(url).toString(Charsets.UTF_8) }
                catch (_: Exception) { currentCoroutineContext().ensureActive(); "" }
            val candidates = withContext(Dispatchers.Default) { SiteIconPolicy.candidates(url, html) }
            for (candidate in candidates) {
                try {
                    val bytes = fetch(candidate)
                    bitmap = decode(bytes)
                    if (bitmap != null) break
                } catch (_: Exception) { currentCoroutineContext().ensureActive() /* 超时的候选不阻止标准图标回退。 */ }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 图标是装饰信息，任何异常都不得影响网页。 */ }
        val expires = now + if (bitmap != null) SiteIconPolicy.SUCCESS_TTL else SiteIconPolicy.FAILURE_TTL
        val saved = bitmap
        withContext(Dispatchers.IO) {
            runCatching {
                val png = saved?.let { image -> ByteArrayOutputStream().use { output ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
                } }
                disk.write(key, DiskIconEntry(expires, png))
            }
        }
        memory.put(key, bitmap, expires)
        return bitmap
    }

    /** 每次请求都匿名且禁用自动重定向，跨地址跳转必须再次检查协议和凭据。 */
    private suspend fun fetch(original: String): ByteArray = withTimeout(SiteIconPolicy.TIMEOUT) {
        var url = SiteIconPolicy.safeUrl(original) ?: error("图标地址无效")
        for (redirects in 0..3) {
            val lease = request(url)
            try {
                val response = lease.response
                response.setReadTimeoutMillis(SiteIconPolicy.TIMEOUT)
                if (response.statusCode in listOf(301, 302, 303, 307, 308)) {
                    check(redirects < 3)
                    val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value ?: error("缺少重定向地址")
                    url = SiteIconPolicy.safeUrl(URI(url).resolve(location).toASCIIString(), url) ?: error("图标重定向无效")
                    continue
                }
                val body = response.body ?: error("图标资源为空")
                check(response.statusCode in 200..299) { "图标响应失败" }
                return@withTimeout suspendCancellableCoroutine { continuation ->
                    val reader = scope.launch(Dispatchers.IO) {
                        try { val bytes = body.use(SiteIconPolicy::readBounded); continuation.resume(bytes) }
                        catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    }
                    continuation.invokeOnCancellation { runCatching { body.close() }; reader.cancel() }
                }
            } finally { lease.close() }
        }
        error("图标重定向过多")
    }

    private class ResponseLease(val response: WebResponse, private val release: () -> Unit) {
        private val closed = AtomicBoolean(false)
        /** 读取、取消和迟到回调可能竞争关闭，同一名额只能释放一次。 */
        fun close() { if (closed.compareAndSet(false, true)) { runCatching { response.body?.close() }; release() } }
    }

    /** 界面超时立即结束等待；取消失败的底层请求仍占名额，迟到结果只关闭而不交给界面。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun request(url: String): ResponseLease {
        NativeIconNetworkBudget.gate.acquire()
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) NativeIconNetworkBudget.gate.release(); Unit }
        return suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) { release(); return@suspendCancellableCoroutine }
            try {
                // Gecko 158 的 IntDef 漏标组合位标记，两项保护仍必须同时保留。
                @android.annotation.SuppressLint("WrongConstant")
                val result = executor.fetch(WebRequest.Builder(url).header("Accept", "image/*,text/html;q=0.8").build(),
                    GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS or GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
                result.accept({ response ->
                    if (response == null) {
                        release()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Gecko 图标响应为空"))
                    } else {
                        val lease = ResponseLease(response, release)
                        if (continuation.isActive) continuation.resume(lease) { lease.close() }
                        else lease.close()
                    }
                }, { failure ->
                    release()
                    if (continuation.isActive) continuation.resumeWithException(failure ?: IllegalStateException("Gecko 图标请求失败"))
                })
                continuation.invokeOnCancellation { result.cancel() }
            } catch (failure: Exception) {
                release()
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    /** 通过固定 Gecko 版本的本地图片解码器兼容 ICO，绝不把远程地址直接交给解码器再次请求。 */
    private suspend fun decode(bytes: ByteArray): Bitmap? = withTimeout(SiteIconPolicy.TIMEOUT) {
        val temporary = withContext(Dispatchers.IO) {
            directory.mkdirs()
            File.createTempFile("decode-", ".tmp", directory).also { it.writeBytes(bytes) }
        }
        try {
            val bitmap = await(ImageDecoder.instance().decode(temporary.toURI().toString(), 128))
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= 128) bitmap else Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * 128 / longest).coerceAtLeast(1), (bitmap.height * 128 / longest).coerceAtLeast(1), true)
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
    }

    /** GeckoResult 与协程取消相连，面板销毁、超时和 ViewModel 结束均能释放请求。 */
    private suspend fun <T : Any> await(result: GeckoResult<T>): T = suspendCancellableCoroutine { continuation ->
        result.accept({ value ->
            if (value != null) continuation.resume(value)
            else if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Gecko 未返回图标资源"))
        }, { failure ->
            if (continuation.isActive) continuation.resumeWithException(failure ?: IllegalStateException("Gecko 图标处理失败"))
        })
        continuation.invokeOnCancellation { result.cancel() }
    }

    /** 不主动回收仍可能被 Compose 绘制的位图，仅释放仓库引用。 */
    fun close() { setForeground(false); pending.clear(); memory.clear() }

    /** 后台窗口不再订阅网络资源；回前台由实际可见的图块重新订阅。 */
    fun setForeground(value: Boolean) {
        active = value
        if (!value) pending.values.forEach { it.work.cancel() }
    }
}
