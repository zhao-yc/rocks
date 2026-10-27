package org.rocks.geckodemo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import org.rocks.browser.AtomicSessionFile
import org.rocks.browser.SessionRepository
import org.rocks.browser.core.BrowserData
import org.rocks.browser.core.BrowserSettings
import org.rocks.browser.core.BrowserTab as StoredTab
import org.rocks.browser.core.PageRecord
import org.rocks.browser.core.SearchEngine
import org.mozilla.geckoview.WebResponse
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
    var pendingUrl: String? = null
}

internal data class ExtensionPopup(val session: GeckoSession, val extensionId: String, val title: String)
internal data class PermissionQuestion(val title: String, val body: String, val answer: (Boolean) -> Unit)
internal data class AddonChoice(val id: String, val name: String, val description: Int, val uri: String?)
internal data class LinkTarget(val tabId: Long, val url: String, val title: String)

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
    var toolbarCollapsed by mutableStateOf(false)
        private set
    private val toolbarScroll = ToolbarScrollState()
    private var toolbarInteractionLocked = false

    fun expandToolbar() { toolbarScroll.expand(); toolbarCollapsed = false }
    fun setToolbarInteraction(pinned: Boolean, dragging: Boolean) {
        toolbarInteractionLocked = pinned || dragging
        if (pinned) expandToolbar()
    }
    private fun resetToolbar() { toolbarScroll.reset(); toolbarCollapsed = false }
    private var nextId = 1L
    private var foreground = true
    private var pendingInstall: GeckoResult<WebExtension>? = null
    private var disposed = false
    var restoring by mutableStateOf(true)
        private set
    var storageReadOnly by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set
    val bookmarks = mutableStateListOf<PageRecord>()
    val history = mutableStateListOf<PageRecord>()
    var searchEngine by mutableStateOf(SearchEngine.BING)
        private set
    var linkMenu by mutableStateOf<LinkTarget?>(null)
    private val storage = (application as DemoApplication).storage
    private var saveJob: Job? = null
    val files = BrowserFiles(application, viewModelScope, { session ->
        tabs.firstOrNull { it.session === session && it.id == selectedId && !restoring && !disposed }?.url
    }, ::notify)

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
                if (url != null) {
                    if (tab.url != url && tab.id == selectedId) { files.cancelPrompts(); answerQuestion(false); linkMenu = null }
                    if (tab.url != url && tab.id == selectedId) resetToolbar()
                    tab.url = url; save()
                }
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
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.error = false; tab.progress = 5
                if (tab.id == selectedId) resetToolbar()
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tab.progress = progress }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.progress = 100
                if (!success) tab.error = true
                else if (DemoPolicy.isWebUrl(tab.url)) {
                    history.removeAll { it.url == tab.url }
                    history.add(0, PageRecord(tab.url, tab.title.take(512), System.currentTimeMillis()))
                    while (history.size > 500) history.removeAt(history.lastIndex)
                    save()
                }
            }
        }
        session.compositorScrollDelegate = object : GeckoSession.CompositorScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, update: GeckoSession.ScrollPositionUpdate) {
                if (tab.id != selectedId || !foreground || fullScreen) return
                toolbarCollapsed = toolbarScroll.onScroll(update.scrollY, update.zoom,
                    update.source == GeckoSession.ScrollPositionUpdate.SOURCE_USER_INTERACTION,
                    toolbarInteractionLocked || tab.url == DemoPolicy.HOME)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title.orEmpty().take(512); save() }
            override fun onCloseRequest(session: GeckoSession) { closeTab(tab.id) }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (tab.id == selectedId) { this@BrowserModel.fullScreen = fullScreen; resetToolbar() }
            }
            override fun onCrash(session: GeckoSession) { tab.error = true; tab.progress = 100 }
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                val url = element.linkUri ?: return
                if (foreground && tab.id == selectedId && DemoPolicy.isWebUrl(url))
                    linkMenu = LinkTarget(tab.id, url, element.linkText ?: element.title ?: url)
            }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                if (foreground) files.requestDownload(session, response) else runCatching { response.body?.close() }
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                return if (foreground) files.requestUpload(session, prompt) else GeckoResult.fromValue(prompt.dismiss())
            }
            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                return websiteQuestion(tab, prompt, prompt.message.orEmpty()) { prompt.dismiss() }
            }
            override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                return websiteQuestion(tab, prompt, prompt.message.orEmpty()) { allow ->
                    prompt.confirm(if (allow) GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE else GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)
                }
            }
        }
        // NavigationDelegate and WebExtension.TabDelegate require an UNOPENED session.
        // Gecko opens it with the inherited browsing context before loading its target.
        if (url != null) session.open(runtime)
        if (url != null) {
            tab.url = url
            if (url != DemoPolicy.HOME) tab.pendingUrl = url
        }
        extensions.forEach { attachExtension(tab, it) }
        tabs.add(tab)
        if (select || tabs.size == 1) selectTab(tab.id) else updateActiveSessions()
        save()
        return tab
    }

    private fun websiteQuestion(tab: BrowserTab, prompt: GeckoSession.PromptDelegate.BasePrompt, message: String,
        confirm: (Boolean) -> GeckoSession.PromptDelegate.PromptResponse): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        var finished = false
        fun resolve(allow: Boolean) {
            if (finished) return
            finished = true
            if (prompt.isComplete) result.completeExceptionally(CancellationException("Page dismissed the dialog"))
            else result.complete(confirm(allow))
        }
        if (!foreground || tab.id != selectedId || question != null) resolve(false)
        else {
            val pending = PermissionQuestion(text(R.string.website_dialog, android.net.Uri.parse(tab.url).host.orEmpty()), message.take(4096), ::resolve)
            question = pending
            prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                    if (question === pending) question = null
                    resolve(false)
                }
            })
        }
        return result
    }

    fun selectTab(id: Long) {
        if (tabs.none { it.id == id }) return
        if (selectedId != id) {
            resetToolbar()
            if (fullScreen) tabs.firstOrNull { it.id == selectedId }?.session?.exitFullScreen()
            fullScreen = false
            closePopup()
            answerQuestion(false)
            files.cancelPrompts(); linkMenu = null
        }
        selectedId = id
        updateActiveSessions()
        save()
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        files.cancelPrompts(); linkMenu = null
        if (selectedId == id) { closePopup(); fullScreen = false }
        val removed = tabs.removeAt(index)
        removed.session.setActive(false)
        removed.session.close()
        overrides.keys.filter { it.second == id }.forEach { overrides.remove(it) }
        if (tabs.isEmpty()) newTab(DemoPolicy.HOME)
        else if (selectedId == id) selectTab(tabs[index.coerceAtMost(tabs.lastIndex)].id)
        save()
    }

    fun navigate(input: String): Boolean {
        val url = DemoPolicy.inputUrl(input, searchEngine) ?: run { notify(R.string.unsupported_link); return false }
        files.cancelPrompts(); answerQuestion(false); linkMenu = null
        current.pendingUrl = null
        closePopup(); current.url = url; current.error = false; current.session.loadUri(url)
        save()
        return true
    }

    fun home() { files.cancelPrompts(); current.pendingUrl = null; current.url = DemoPolicy.HOME; current.error = false; current.session.loadUri(DemoPolicy.HOME); save() }
    fun back() { if (fullScreen) current.session.exitFullScreen() else if (current.canGoBack) current.session.goBack() }
    fun reload() {
        current.error = false
        if (!current.session.isOpen) { current.session.open(runtime); current.session.loadUri(current.url) }
        else current.session.reload()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { answerQuestion(false); closePopup(); files.backgrounded(); linkMenu = null; saveImmediately() }
        updateActiveSessions()
    }

    private fun updateActiveSessions() {
        val selected = tabs.indexOfFirst { it.id == selectedId }
        tabs.forEachIndexed { index, it ->
            if (!restoring && foreground && kotlin.math.abs(index - selected) <= 1) {
                it.pendingUrl?.let { target -> it.pendingUrl = null; it.session.loadUri(target) }
            }
            // Adjacent pages must paint before a bottom-bar drag exposes their surfaces.
            it.session.setActive(foreground && kotlin.math.abs(index - selected) <= 1)
            it.session.setFocused(foreground && popup == null && it.id == selectedId)
            controller.setTabActive(it.session, it.id == selectedId)
        }
    }

    fun save() {
        if (restoring || disposed) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(250); saveImmediately() }
    }

    private fun saveImmediately() {
        if (restoring || disposed) return
        val portableTabs = tabs.filter { it.url == DemoPolicy.HOME || DemoPolicy.isWebUrl(it.url) }
            .map { StoredTab(it.id.toString(), if (it.url == DemoPolicy.HOME) "" else it.url, it.title) }
            .ifEmpty { listOf(StoredTab()) }
        val active = selectedId.toString().takeIf { id -> portableTabs.any { it.id == id } } ?: portableTabs.first().id
        storage.save(BrowserData(tabs = portableTabs, activeTabId = active, bookmarks = bookmarks.toList(),
            history = history.toList(), settings = BrowserSettings(searchEngine.id)))
    }

    fun toggleBookmark() {
        if (!DemoPolicy.isWebUrl(current.url)) return
        val removed = bookmarks.removeAll { it.url == current.url }
        if (!removed) {
            if (bookmarks.size >= 200) { notify(R.string.bookmark_limit); return }
            bookmarks.add(0, PageRecord(current.url, current.title.take(512), System.currentTimeMillis()))
        }
        save(); notify(if (removed) R.string.bookmark_removed else R.string.bookmark_added)
    }

    fun removeBookmark(url: String) { bookmarks.removeAll { it.url == url }; save() }
    fun clearHistory() {
        question = PermissionQuestion(text(R.string.clear_history), text(R.string.clear_history_note)) { allow ->
            if (allow) { history.clear(); save() }
        }
    }
    fun setSearch(engine: SearchEngine) { searchEngine = engine; save() }
    fun closeOtherTabs() { tabs.filter { it.id != selectedId }.toList().forEach { closeTab(it.id) } }
    fun openLink(background: Boolean) {
        val target = linkMenu ?: return
        linkMenu = null
        if (target.tabId == selectedId) newTab(target.url, select = !background)
    }

    private fun reloadWebTabs() {
        tabs.filter { it.pendingUrl == null && DemoPolicy.isWebUrl(it.url) && it.session.isOpen }.forEach { it.session.reload() }
    }

    fun updateExtension(extension: WebExtension) {
        if (busyId != null || extension.isBuiltIn) return
        busyId = extension.id
        controller.update(extension).accept({ updated ->
            if (!disposed) {
                busyId = null
                if (updated != null) { putExtension(updated); reloadWebTabs(); notify(R.string.extension_updated) }
                else notify(R.string.extension_latest)
            }
        }, { busyId = null; notify(R.string.extensions_failed) })
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
                    reloadWebTabs()
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
                reloadWebTabs()
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
                    reloadWebTabs()
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
        viewModelScope.launch {
            val restored = storage.restore()
            storageReadOnly = restored.isFailure
            restored.getOrNull()?.let { data ->
                tabs.forEach { it.session.close() }; tabs.clear()
                data.tabs.forEach { stored -> newTab(stored.url.ifEmpty { DemoPolicy.HOME }, select = false)?.title = stored.title }
                selectedId = tabs[data.tabs.indexOfFirst { it.id == data.activeTabId }.coerceIn(0, tabs.lastIndex)].id
                bookmarks.addAll(data.bookmarks); history.addAll(data.history)
                searchEngine = SearchEngine.fromId(data.settings.searchEngine)
            }
            restoring = false
            updateActiveSessions()
            if (storageReadOnly) notify(R.string.restore_failed) else save()
            storage.writeFailed.collect { saveFailed = it }
        }
        refreshExtensions()
    }


    override fun onCleared() {
        saveImmediately()
        disposed = true
        files.cancelPrompts()
        answerQuestion(false); cancelInstall(); closePopup()
        extensions.forEach { it.setActionDelegate(null); it.setTabDelegate(null) }
        tabs.forEach { it.session.close() }
        tabs.clear()
        controller.setPromptDelegate(null)
        controller.setAddonManagerDelegate(null)
        super.onCleared()
    }
}
