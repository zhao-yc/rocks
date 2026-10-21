package org.rocks.geckodemo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebRequestError

internal class BrowserTab(val id: Long, val session: GeckoSession, val extensionBase: String? = null) {
    var url by mutableStateOf(DemoPolicy.HOME)
    var title by mutableStateOf("")
    var progress by mutableStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var error by mutableStateOf(false)
}

internal data class ExtensionPopup(val session: GeckoSession, val extensionId: String, val title: String)
internal data class PermissionQuestion(val title: String, val body: String, val answer: (Boolean) -> Unit)
internal data class AddonChoice(val id: String, val name: String, val description: Int, val uri: String?)

internal val addonCatalog = listOf(
    AddonChoice(DemoPolicy.PAPER_ID, "Rocks Paper", R.string.paper_description, null),
    AddonChoice(DemoPolicy.DARK_READER_ID, "Dark Reader", R.string.dark_description,
        "https://addons.mozilla.org/firefox/downloads/file/5055786/darkreader-4.9.133.xpi"),
    AddonChoice("uBlock0@raymondhill.net", "uBlock Origin", R.string.ublock_description,
        "https://addons.mozilla.org/firefox/downloads/file/5034826/ublock_origin-1.75.0.xpi"),
)

internal class BrowserModel(application: Application) : AndroidViewModel(application) {
    val runtime = (application as DemoApplication).runtime
    private val controller = runtime.webExtensionController
    val tabs = mutableStateListOf<BrowserTab>()
    val extensions = mutableStateListOf<WebExtension>()
    private val defaults = mutableStateMapOf<String, WebExtension.Action>()
    private val overrides = mutableStateMapOf<Pair<String, Long>, WebExtension.Action>()
    var selectedId by mutableStateOf(0L)
        private set
    val current: BrowserTab get() = tabs.first { it.id == selectedId }
    var popup by mutableStateOf<ExtensionPopup?>(null)
        private set
    var question by mutableStateOf<PermissionQuestion?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var busyId by mutableStateOf<String?>(null)
        private set
    var installing by mutableStateOf(false)
        private set
    var extensionsReady by mutableStateOf(false)
        private set
    var fullScreen by mutableStateOf(false)
        private set
    private var nextId = 1L
    private var foreground = true
    private var pendingInstall: GeckoResult<WebExtension>? = null
    private var disposed = false

    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)
    fun notify(id: Int) { notice = text(id) }


    private fun permissionResult(extension: WebExtension, permissions: Array<String>, origins: Array<String>,
        data: Array<String>): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        ask(extension, permissions, origins, data) { result.complete(if (it) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
        return result
    }

    private fun ask(extension: WebExtension, permissions: Array<String>, origins: Array<String>,
        data: Array<String>, answer: (Boolean) -> Unit) {
        if (!foreground || disposed || question != null) { answer(false); return }
        val requested = (permissions.toList() + origins.toList()).distinct().joinToString("\n")
        val dataText = if (data.isEmpty()) "" else "\n\n" + text(R.string.data_permissions, data.joinToString("\n"))
        question = PermissionQuestion(text(R.string.extension_permission, extension.metaData?.name ?: extension.id),
            text(R.string.permission_body, requested.ifBlank { text(R.string.no_extra_permissions) }) + dataText, answer)
    }

    fun answerQuestion(allow: Boolean) {
        val pending = question ?: return
        question = null
        pending.answer(allow && foreground && !disposed)
    }

    fun refreshExtensions() {
        controller.list().accept({ list ->
            if (!disposed) {
                extensions.clear()
                list?.forEach(::putExtension)
                extensionsReady = true
            }
        }, { if (!disposed) { extensionsReady = true; notify(R.string.extensions_failed) } })
    }

    private fun putExtension(extension: WebExtension) {
        if (disposed) return
        val index = extensions.indexOfFirst { it.id == extension.id }
        if (index < 0) extensions.add(extension) else extensions[index] = extension
        extension.setActionDelegate(actionDelegate)
        extension.setTabDelegate(extensionTabDelegate)
        tabs.forEach { attachExtension(it, extension) }
    }

    private fun clearActions(id: String) {
        defaults.remove(id)
        overrides.keys.filter { it.first == id }.forEach { overrides.remove(it) }
        if (popup?.extensionId == id) closePopup()
        tabs.filter { it.extensionBase != null && extensions.firstOrNull { e -> e.id == id }?.metaData?.baseUrl == it.extensionBase }
            .toList().forEach { closeTab(it.id) }
    }

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (disposed) return
            if (session == null) defaults[extension.id] = action
            else tabs.firstOrNull { it.session === session }?.let { overrides[extension.id to it.id] = action }
        }
        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            if (popup?.extensionId == extension.id) { closePopup(); return null }
            return openPopup(extension)
        }
        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? = openPopup(extension)
    }

    fun action(extension: WebExtension): WebExtension.Action? {
        val default = defaults[extension.id]
        val override = overrides[extension.id to selectedId]
        return if (override != null && default != null) override.withDefault(default) else override ?: default
    }

    fun openExtension(extension: WebExtension) {
        if (extension.metaData?.enabled != true) return
        val action = action(extension)
        if (action != null && action.enabled != false) action.click()
        else extension.metaData?.optionsPageUrl?.let { newTab(it, extensionBase = extension.metaData?.baseUrl) }
            ?: notify(R.string.extension_not_ready)
    }

    private fun openPopup(extension: WebExtension): GeckoResult<GeckoSession>? {
        if (!foreground || disposed) return null
        closePopup()
        val session = GeckoSession()
        session.permissionDelegate = deniedPermissions
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) { closePopup() }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                return if (DemoPolicy.canLoad(request.uri, extension.metaData?.baseUrl)) GeckoResult.allow() else GeckoResult.deny()
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (!DemoPolicy.canLoad(uri, extension.metaData?.baseUrl)) return null
                closePopup()
                return newTab(null, extensionBase = extension.metaData?.baseUrl)?.let { GeckoResult.fromValue(it.session) }
            }
        }
        session.open(runtime)
        session.setActive(true)
        popup = ExtensionPopup(session, extension.id, extension.metaData?.name ?: extension.id)
        current.session.setFocused(false)
        return GeckoResult.fromValue(session)
    }

    fun closePopup() {
        popup?.session?.let { it.setActive(false); it.close() }
        popup = null
        tabs.firstOrNull { it.id == selectedId }?.session?.setFocused(foreground)
    }

    private val extensionTabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
            val url = createDetails.url
            if (url != null && !DemoPolicy.canLoad(url, source.metaData?.baseUrl)) return null
            return newTab(null, select = createDetails.active != false, extensionBase = source.metaData?.baseUrl)
                ?.let { GeckoResult.fromValue(it.session) }
        }
        override fun onOpenOptionsPage(source: WebExtension) {
            source.metaData?.optionsPageUrl?.let { newTab(it, extensionBase = source.metaData?.baseUrl) }
        }
    }

    private fun attachExtension(tab: BrowserTab, extension: WebExtension) {
        tab.session.webExtensionController.setActionDelegate(extension, actionDelegate)
        tab.session.webExtensionController.setTabDelegate(extension, object : WebExtension.SessionTabDelegate {
            override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
                closeTab(tab.id); return GeckoResult.allow()
            }
            override fun onUpdateTab(extension: WebExtension, session: GeckoSession, details: WebExtension.UpdateTabDetails): GeckoResult<AllowOrDeny> {
                if (details.url != null && !DemoPolicy.canLoad(details.url!!, tab.extensionBase)) return GeckoResult.deny()
                if (details.active == true) selectTab(tab.id)
                return GeckoResult.allow()
            }
        })
    }

    private val deniedPermissions = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
            notify(R.string.site_permission_denied)
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
        }
    }

    fun newTab(url: String?, select: Boolean = true, extensionBase: String? = null): BrowserTab? {
        if (tabs.size >= DemoPolicy.MAX_TABS) { notify(R.string.tab_limit); return null }
        if (url != null && !DemoPolicy.canLoad(url, extensionBase)) return null
        val session = GeckoSession()
        val tab = BrowserTab(nextId++, session, extensionBase)
        session.permissionDelegate = deniedPermissions
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW &&
                    (!request.hasUserGesture || tab.id != selectedId)) return GeckoResult.deny()
                val allowed = DemoPolicy.canLoad(request.uri, tab.extensionBase)
                if (!allowed && request.hasUserGesture && tab.id == selectedId) notify(R.string.unsupported_link)
                return if (allowed) GeckoResult.allow() else GeckoResult.deny()
            }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null) tab.url = url
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canGoBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canGoForward = canGoForward }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (!DemoPolicy.canLoad(uri, tab.extensionBase)) return null
                return newTab(null, extensionBase = tab.extensionBase)?.let { GeckoResult.fromValue(it.session) }
            }
            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                tab.error = true; tab.progress = 100
                return null // Never create certificate exceptions or bypass TLS errors.
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { tab.error = false; tab.progress = 5 }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tab.progress = progress }
            override fun onPageStop(session: GeckoSession, success: Boolean) { tab.progress = 100; if (!success) tab.error = true }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title.orEmpty() }
            override fun onCloseRequest(session: GeckoSession) { closeTab(tab.id) }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (tab.id == selectedId) this@BrowserModel.fullScreen = fullScreen
            }
            override fun onCrash(session: GeckoSession) { tab.error = true; tab.progress = 100 }
        }
        // NavigationDelegate and WebExtension.TabDelegate require an UNOPENED session.
        // Gecko opens it with the inherited browsing context before loading its target.
        if (url != null) session.open(runtime)
        extensions.forEach { attachExtension(tab, it) }
        tabs.add(tab)
        if (select || tabs.size == 1) selectTab(tab.id) else updateActiveSessions()
        if (url != null) { tab.url = url; session.loadUri(url) }
        return tab
    }

    fun selectTab(id: Long) {
        if (tabs.none { it.id == id }) return
        if (selectedId != id) {
            if (fullScreen) tabs.firstOrNull { it.id == selectedId }?.session?.exitFullScreen()
            fullScreen = false
            closePopup()
            answerQuestion(false)
        }
        selectedId = id
        updateActiveSessions()
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        if (selectedId == id) { closePopup(); fullScreen = false }
        val removed = tabs.removeAt(index)
        removed.session.setActive(false)
        removed.session.close()
        overrides.keys.filter { it.second == id }.forEach { overrides.remove(it) }
        if (tabs.isEmpty()) newTab(DemoPolicy.HOME)
        else if (selectedId == id) selectTab(tabs[index.coerceAtMost(tabs.lastIndex)].id)
    }

    fun navigate(input: String): Boolean {
        val url = DemoPolicy.inputUrl(input) ?: run { notify(R.string.unsupported_link); return false }
        closePopup(); current.url = url; current.error = false; current.session.loadUri(url)
        return true
    }

    fun home() { current.url = DemoPolicy.HOME; current.error = false; current.session.loadUri(DemoPolicy.HOME) }
    fun back() { if (fullScreen) current.session.exitFullScreen() else if (current.canGoBack) current.session.goBack() }
    fun reload() { current.error = false; current.session.reload() }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { answerQuestion(false); closePopup() }
        updateActiveSessions()
    }

    private fun updateActiveSessions() {
        val selected = tabs.indexOfFirst { it.id == selectedId }
        tabs.forEachIndexed { index, it ->
            // Adjacent pages must paint before a bottom-bar drag exposes their surfaces.
            it.session.setActive(foreground && kotlin.math.abs(index - selected) <= 1)
            it.session.setFocused(foreground && popup == null && it.id == selectedId)
            controller.setTabActive(it.session, it.id == selectedId)
        }
    }

    fun install(choice: AddonChoice) {
        if (busyId != null || !extensionsReady) return
        if (choice.uri == null) {
            if (question != null) return
            question = PermissionQuestion(text(R.string.install_paper), text(R.string.paper_permission)) { allow ->
                if (allow) beginInstall(choice)
            }
        } else beginInstall(choice)
    }

    private fun beginInstall(choice: AddonChoice) {
        busyId = choice.id
        installing = true
        val operation = if (choice.uri == null) controller.ensureBuiltIn("resource://android/assets/paper/", choice.id)
            else controller.install(choice.uri, WebExtensionController.INSTALLATION_METHOD_MANAGER)
        pendingInstall = operation
        operation.accept({ extension ->
            if (!disposed && pendingInstall === operation) {
                if (extension != null && extension.id == choice.id) {
                    putExtension(extension); notify(R.string.installed_reload)
                    tabs.filter { DemoPolicy.isWebUrl(it.url) }.forEach { it.session.reload() }
                } else notify(R.string.extensions_failed)
                busyId = null; installing = false; pendingInstall = null
            }
        }, { error ->
            if (!disposed && pendingInstall === operation) {
                busyId = null; installing = false; pendingInstall = null
                notice = text(R.string.install_failed, (error as? WebExtension.InstallException)?.code?.toString() ?: "—")
            }
        })
    }

    fun cancelInstall() {
        if (!installing) return
        answerQuestion(false)
        busyId = null
        installing = false
        pendingInstall?.cancel()
        pendingInstall = null
    }

    fun setEnabled(extension: WebExtension, enabled: Boolean) {
        if (busyId != null) return
        closePopup(); busyId = extension.id
        val operation = if (enabled) controller.enable(extension, WebExtensionController.EnableSource.USER)
            else controller.disable(extension, WebExtensionController.EnableSource.USER)
        operation.accept({ result ->
            if (!disposed) {
                if (result != null) putExtension(result)
                busyId = null
                tabs.filter { DemoPolicy.isWebUrl(it.url) }.forEach { it.session.reload() }
                notify(R.string.changed_reload)
            }
        }, { busyId = null; notify(R.string.extensions_failed) })
    }

    fun uninstall(extension: WebExtension) {
        if (busyId != null || question != null) return
        question = PermissionQuestion(text(R.string.uninstall_title, extension.metaData?.name ?: extension.id),
            text(R.string.uninstall_body)) { allow ->
            if (allow) {
                closePopup(); busyId = extension.id
                controller.uninstall(extension).accept({
                    extensions.removeAll { it.id == extension.id }; clearActions(extension.id)
                    busyId = null
                    tabs.filter { DemoPolicy.isWebUrl(it.url) }.forEach { it.session.reload() }
                }, { busyId = null; notify(R.string.extensions_failed) })
            }
        }
    }

    init {
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(extension: WebExtension, permissions: Array<String>,
                origins: Array<String>, dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                if (extension.id != busyId || !foreground || disposed) {
                    result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                } else {
                    ask(extension, permissions, origins, dataCollectionPermissions) { allow ->
                        result.complete(WebExtension.PermissionPromptResponse(allow, false, false))
                    }
                }
                return result
            }
            override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>,
                origins: Array<String>, dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny> = permissionResult(extension, permissions, origins, dataCollectionPermissions)

            override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>,
                newOrigins: Array<String>, newDataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny> = permissionResult(extension, newPermissions, newOrigins, newDataCollectionPermissions)
        })
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) { putExtension(extension) }
            override fun onEnabled(extension: WebExtension) { putExtension(extension) }
            override fun onDisabled(extension: WebExtension) { putExtension(extension); clearActions(extension.id) }
            override fun onUninstalled(extension: WebExtension) {
                clearActions(extension.id)
                extensions.removeAll { it.id == extension.id }
            }
        })
        newTab(DemoPolicy.HOME)
        newTab("https://example.org", select = false)
        refreshExtensions()
    }


    override fun onCleared() {
        disposed = true
        answerQuestion(false); cancelInstall(); closePopup()
        extensions.forEach { it.setActionDelegate(null); it.setTabDelegate(null) }
        tabs.forEach { it.session.close() }
        tabs.clear()
        controller.setPromptDelegate(null)
        controller.setAddonManagerDelegate(null)
        super.onCleared()
    }
}
