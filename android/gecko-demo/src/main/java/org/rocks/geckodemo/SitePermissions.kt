package org.rocks.geckodemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal class NativePermissionRequest(val id: Long, val permissions: Array<String>) {
    var launched = false
}

/** One pending page request; an Android result can never authorize a replacement document. */
internal class SitePermissions(
    private val context: Context,
    private val runtime: GeckoRuntime,
    private val current: (GeckoSession) -> BrowserPageScope?,
    private val ask: (String, String, () -> Unit, (Boolean) -> Unit) -> Boolean,
) : GeckoSession.PermissionDelegate {
    private class Request(val id: Long, val session: GeckoSession, val page: BrowserPageScope,
        val uri: String, val capabilities: Set<SiteCapability>, val complete: (Boolean) -> Unit) {
        var nativeFinished = false
    }
    var nativeRequest by mutableStateOf<NativePermissionRequest?>(null)
        private set
    private var pending: Request? = null
    private var nextId = 1L
    private val handshake = PermissionHandshake()

    private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun granted(cap: SiteCapability): Boolean = when (cap) {
        SiteCapability.CAMERA -> has(Manifest.permission.CAMERA)
        SiteCapability.MICROPHONE -> has(Manifest.permission.RECORD_AUDIO)
        SiteCapability.LOCATION -> has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    private fun permissions(caps: Set<SiteCapability>): Array<String> = caps.flatMap { cap -> when (cap) {
        SiteCapability.CAMERA -> listOf(Manifest.permission.CAMERA)
        SiteCapability.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        SiteCapability.LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    } }.distinct().toTypedArray()
    private fun capabilities(names: Array<out String>): Set<SiteCapability>? {
        if (names.isEmpty()) return null
        return names.map { when (it) {
            Manifest.permission.CAMERA -> SiteCapability.CAMERA
            Manifest.permission.RECORD_AUDIO -> SiteCapability.MICROPHONE
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION -> SiteCapability.LOCATION
            else -> return null
        } }.toSet()
    }
    private fun label(cap: SiteCapability) = context.getString(when (cap) {
        SiteCapability.CAMERA -> R.string.camera
        SiteCapability.MICROPHONE -> R.string.microphone
        SiteCapability.LOCATION -> R.string.location
    })
    private fun valid(request: Request) = BrowserEntryPolicy.canAuthorize(request.page, current(request.session), request.uri)

    private fun request(session: GeckoSession, uri: String, caps: Set<SiteCapability>, useHandshake: Boolean,
        shown: () -> Unit = {}, complete: (Boolean) -> Unit) {
        val page = current(session)
        if (page == null || caps.isEmpty() || !BrowserEntryPolicy.canAuthorize(page, page, uri) || pending != null || nativeRequest != null) {
            complete(false); return
        }
        if (useHandshake && caps.all(::granted) && handshake.consume(page, caps, SystemClock.uptimeMillis())) {
            complete(true); return
        }
        val request = Request(nextId++, session, page, uri, caps, complete)
        pending = request
        val origin = BrowserEntryPolicy.secureOrigin(uri) ?: run { finish(request, false); return }
        val body = context.getString(R.string.site_permission_body,
            caps.joinToString("、", transform = ::label)) + if (SiteCapability.LOCATION in caps) "\n\n" + context.getString(R.string.location_permission_note) else ""
        val presented = ask(context.getString(R.string.site_permission_title, origin), body, shown) { allow ->
            if (pending !== request) return@ask
            if (!allow || !valid(request)) { finish(request, false); return@ask }
            val missing = caps.filterNot(::granted).toSet()
            if (missing.isEmpty()) finish(request, true)
            else nativeRequest = NativePermissionRequest(request.id, permissions(missing))
        }
        if (!presented) finish(request, false)
    }

    private fun finish(request: Request, allow: Boolean) {
        if (pending !== request) return
        pending = null
        if (nativeRequest?.launched == false) nativeRequest = null
        request.complete(allow && valid(request) && request.capabilities.all(::granted))
    }

    fun nativeResult() {
        val native = nativeRequest ?: return
        nativeRequest = null
        val request = pending?.takeIf { it.id == native.id } ?: return
        request.nativeFinished = true
        // Activity Result may run before Activity.onResume; resume performs the scope check.
        if (current(request.session) != null) finish(request, request.capabilities.all(::granted))
    }
    fun resumed() { pending?.takeIf { it.nativeFinished }?.let { finish(it, it.capabilities.all(::granted)) } }
    fun backgrounded() { if (nativeRequest?.launched != true) cancel() }
    fun cancel() {
        handshake.clear()
        pending?.let { finish(it, false) }
    }

    override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<String>?, callback: GeckoSession.PermissionDelegate.Callback) {
        val caps = capabilities(permissions.orEmpty()) ?: run { callback.reject(); return }
        val page = current(session) ?: run { callback.reject(); return }
        request(session, page.url, caps, false) { allow ->
            if (allow) { handshake.issue(page, caps, SystemClock.uptimeMillis()); callback.grant() }
            else callback.reject()
        }
    }

    // Gecko 158 requires this notification after the actual prompt is composed. Keep the opt-in
    // local to this pinned API rather than marking the entire permission implementation experimental.
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private fun promptShown(perm: GeckoSession.PermissionDelegate.ContentPermission) { perm.notifyShown() }

    override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION || perm.privateMode) {
            result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY); return result
        }
        request(session, perm.uri, setOf(SiteCapability.LOCATION), true, { promptShown(perm) }) { allow ->
            result.complete(if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
        return result
    }

    override fun onMediaPermissionRequest(session: GeckoSession, uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?, audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback) {
        val camera = video?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA }
        val microphone = audio?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE }
        if ((video != null && camera == null) || (audio != null && microphone == null)) { callback.reject(); return }
        val caps = buildSet {
            if (camera != null) add(SiteCapability.CAMERA)
            if (microphone != null) add(SiteCapability.MICROPHONE)
        }
        request(session, uri, caps, true) { allow -> if (allow) callback.grant(camera, microphone) else callback.reject() }
    }

    fun resetLocation(uri: String, done: (Boolean) -> Unit) {
        val origin = BrowserEntryPolicy.secureOrigin(uri) ?: run { done(false); return }
        runtime.storageController.getPermissions(origin).accept({ permissions ->
            permissions.orEmpty().filter { it.permission == GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION }.forEach {
                runtime.storageController.setPermission(it, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
            }
            done(true)
        }, { done(false) })
    }
}
