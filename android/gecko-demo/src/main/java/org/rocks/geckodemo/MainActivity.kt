package org.rocks.geckodemo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    private val browser: BrowserModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        window.isStatusBarContrastEnforced = false
        // 透明对照版使用固定浅色安全区，无需采集网页来判断状态栏图标颜色。
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        if (savedInstanceState == null) handleWebIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF35614B), onPrimary = Color.White,
                primaryContainer = Color(0xFFD8E9D7), onPrimaryContainer = Color(0xFF173C2B),
                background = Color(0xFFF8F8F2), surface = Color(0xFFF8F8F2),
                surfaceContainer = Color(0xFFEBEFE5), surfaceContainerHigh = Color(0xFFE5EADD),
                onSurface = Color(0xFF202822), onSurfaceVariant = Color(0xFF526451),
            )) { BrowserScreen(browser) }
        }
    }
    private fun handleWebIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = BrowserEntryPolicy.externalUrl(intent.action, intent.dataString)
        if (url != null) browser.openExternalUrl(url) else browser.notify(R.string.unsupported_link)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWebIntent(intent)
    }
    override fun onStop() {
        // Some OEM permission controllers stop the host Activity instead of only pausing it.
        // Keep that one system result pending; it can authorize nothing until resume + scope checks.
        if (!isChangingConfigurations && browser.sitePermissions.nativeRequest?.launched != true)
            browser.sitePermissions.cancel()
        super.onStop()
    }
    override fun onResume() { super.onResume(); browser.setForeground(true) }
    override fun onPause() { browser.setForeground(false); super.onPause() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(model: BrowserModel) {
    if (model.restoring) {
        Surface(Modifier.fillMaxSize()) { Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        return
    }
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val shareLabel = stringResource(R.string.share)
    val tabClosedLabel = stringResource(R.string.tab_closed)
    val undoLabel = stringResource(R.string.undo)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showExtensions by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTabActions by remember { mutableStateOf(false) }
    var showSitePermissions by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<LibraryPage?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode != Activity.RESULT_OK) emptyList() else
            data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
        model.files.completeUpload(uris)
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.files.completeDownload(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }
    LaunchedEffect(model.files.upload) {
        model.files.upload?.let { request ->
            if (!request.launched) {
                request.launched = true
                val types = request.prompt.mimeTypes.orEmpty().filter { it.matches(Regex("[a-zA-Z0-9.+_*-]+/[a-zA-Z0-9.+_*-]+")) }
                runCatching { uploadLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = types.singleOrNull() ?: "*/*"
                    if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }) }.onFailure { model.files.completeUpload(emptyList()); model.notify(R.string.file_failed) }
            }
        }
    }
    LaunchedEffect(model.files.destination) {
        model.files.destination?.let { request ->
            if (!request.launched) {
                request.launched = true
                runCatching { downloadLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = request.mime
                    putExtra(Intent.EXTRA_TITLE, request.filename)
                }) }.onFailure { model.files.completeDownload(null); model.notify(R.string.file_failed) }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.sitePermissions.nativeResult()
    }
    LaunchedEffect(model.sitePermissions.nativeRequest) {
        model.sitePermissions.nativeRequest?.let { request ->
            if (!request.launched) {
                request.launched = true
                runCatching { permissionLauncher.launch(request.permissions) }
                    .onFailure { model.sitePermissions.nativeResult() }
            }
        }
    }
    val hub = model.addressHub
    val editing = hub.visible
    var editorSawKeyboard by remember { mutableStateOf(false) }
    var holdWebViewport by remember { mutableStateOf(false) }
    var editNewTabId by remember { mutableStateOf<Long?>(null) }
    val current = model.current
    val pager = rememberPagerState(initialPage = model.tabs.indexOf(current).coerceAtLeast(0), pageCount = { model.tabs.size })
    var contentWidth by remember { mutableFloatStateOf(1f) }
    var addressWidth by remember { mutableFloatStateOf(1f) }
    var gestureStart by remember { mutableIntStateOf(0) }
    var draggingTabs by remember { mutableStateOf(false) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 网页与起始页共用固定底色，顶部效果完全关闭，不读取网页纹理。
    val pageBackground = if (current.url == DemoPolicy.HOME) MaterialTheme.colorScheme.surface else Color.White
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomObscured = toolbarHeight + navigationBottom + with(density) { 16.dp.roundToPx() }
    val touchExploration = rememberTouchExploration()
    val chromePinned = editing || keyboardVisible || showExtensions || showTabs || showMenu || showTabActions || library != null ||
        model.popup != null || model.question != null || model.linkMenu != null || model.files.offer != null ||
        model.finder.visible || showSitePermissions || model.sitePermissions.nativeRequest != null || current.recordingCamera || current.recordingMicrophone ||
        current.url == DemoPolicy.HOME || touchExploration || density.fontScale > 1.3f
    val compact = model.toolbarCollapsed && !chromePinned
    SideEffect { model.setToolbarInteraction(chromePinned, draggingTabs) }
    DisposableEffect(model) { onDispose { model.setToolbarInteraction(false, false) } }
    fun finishEditing() {
        hub.close()
        focusManager.clearFocus()
        keyboard?.hide()
        model.current.session.setFocused(true)
    }
    // 点击地址栏时先展示最近访问，只有实际修改文字才启动匹配。
    fun beginEditing() {
        model.finder.close()
        hub.open(model.selectedId, model.current.url)
        holdWebViewport = true
        model.current.session.setFocused(false)
    }
    // 新标签创建成功才发出编辑请求，选中状态完成重组后再聚焦，避免焦点竞态。
    fun createTab() {
        showMenu = false; showTabActions = false; showTabs = false
        model.newTab(DemoPolicy.HOME)?.let { editNewTabId = it.id }
    }
    val suggestions = if (editing) LocalBrowserSearch.suggestions(
        if (hub.showingMatches) hub.address.text else "",
        model.tabs.filter { it.extensionBase == null }.sortedBy { if (it.id == model.selectedId) 0 else 1 }
            .map { SearchableTab(it.id, it.url, it.title) }, model.bookmarks.toList(), model.history.toList()) else emptyList()
    val dragState = rememberDraggableState { delta ->
        // Dispatch synchronously so a queue of per-frame coroutines cannot outlive the gesture.
        // One gesture exposes at most one adjacent page, including on a very long drag.
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val start = gestureStart.coerceIn(0, model.tabs.lastIndex.coerceAtLeast(0))
        val target = (position - delta / addressWidth.coerceAtLeast(1f))
            .coerceIn((start - 1).coerceAtLeast(0).toFloat(),
                (start + 1).coerceAtMost(model.tabs.lastIndex).toFloat())
        pager.dispatchRawDelta((target - position) * contentWidth)
    }
    val addressGesture = Modifier.onSizeChanged { addressWidth = it.width.toFloat() }
        .draggable(dragState, Orientation.Horizontal, enabled = model.tabs.size > 1 && !editing,
            onDragStarted = { gestureStart = pager.settledPage; draggingTabs = true },
            onDragStopped = { velocity ->
                val offset = pager.currentPage + pager.currentPageOffsetFraction - gestureStart
                val target = DemoPolicy.swipeTarget(gestureStart, offset, velocity, model.tabs.size)
                scope.launch {
                    try {
                        pager.animateScrollToPage(target, animationSpec = spring(stiffness = 380f))
                        model.tabs.getOrNull(target)?.let { model.selectTab(it.id) }
                    } finally { draggingTabs = false }
                }
            })

    val pageOutOfSync by remember(model, pager) { derivedStateOf {
        val target = model.tabs.indexOfFirst { it.id == model.selectedId }
        target >= 0 && (pager.currentPage != target || pager.currentPageOffsetFraction != 0f)
    } }
    LaunchedEffect(model.selectedId, model.tabs.size, current.url == DemoPolicy.HOME, draggingTabs, pageOutOfSync) {
        // 模型选择不能因上一段动画尚未结束而被永久跳过；手势结束后再对齐实际内容页。
        // 程序切换立即落在目标页，地址栏拖动仍由原来的跟手动画处理。
        if (draggingTabs) return@LaunchedEffect
        val index = model.tabs.indexOfFirst { it.id == model.selectedId }
        // 插入/删除后旧的稳定 key 会在下一次布局继续移动页码，即使当前页码看起来已经相等。
        // 首页与网页互换、迟到的焦点滚动也可能移动分页；只观察是否失配，避免每帧重组整个界面。
        // 请求下一次布局对齐，避免立即测量仍使用旧的数据数量。
        if (index >= 0) pager.requestScrollToPage(index)
    }
    LaunchedEffect(model.notice) {
        model.notice?.let { message ->
            snackbar.showSnackbar(message)
            if (model.notice == message) model.notice = null
        }
    }
    LaunchedEffect(model.closeNotice) {
        model.closeNotice?.let { closed ->
            val result = snackbar.showSnackbar(tabClosedLabel,
                actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) model.reopenClosedTab(closed.closureId)
            model.dismissCloseNotice(closed.closureId)
        }
    }
    LaunchedEffect(editing, hub.input, keyboardVisible, windowFocused) {
        // 系统返回先由输入法消费时，等收起动画结束后一起退出编辑。
        // 窗口失焦时解除观察，切换输入法或临时离开应用不会因此丢弃草稿。
        when {
            !editing || !windowFocused || hub.input == HubInput.NONE -> editorSawKeyboard = false
            keyboardVisible -> editorSawKeyboard = true
            editorSawKeyboard -> { delay(150); finishEditing() }
        }
    }
    LaunchedEffect(editing, keyboardVisible) {
        if (!editing && !keyboardVisible) holdWebViewport = false
    }
    LaunchedEffect(model.selectedId) {
        if (editing && hub.ownerTabId != model.selectedId) finishEditing()
        model.expandToolbar()
        if (editNewTabId == model.selectedId) {
            beginEditing()
            editNewTabId = null
        }
    }
    LaunchedEffect(model.fullScreen) {
        val window = (context as ComponentActivity).window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (model.fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    BackHandler(enabled = model.popup != null || editing || model.finder.visible || model.fullScreen || current.canGoBack) {
        when {
            model.popup != null -> model.closePopup()
            editing -> finishEditing()
            model.finder.visible -> { model.finder.close(); keyboard?.hide(); focusManager.clearFocus() }
            else -> model.back()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = pageBackground) {
        Box(Modifier.fillMaxSize()) {
            // 顶部与两侧安全区只消费一次；滚动时不再平移真实网页或触发顶部效果。
            Box(Modifier.fillMaxSize().then(if (model.fullScreen) Modifier else
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)))
                .then(if (editing || holdWebViewport) Modifier else Modifier.imePadding())
                .then(if (editing) Modifier.clearAndSetSemantics { } else Modifier)) {
                HorizontalPager(state = pager, userScrollEnabled = false, beyondViewportPageCount = 1,
                    key = { model.tabs[it].id }, modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { contentWidth = it.width.toFloat() }) { index ->
                    val tab = model.tabs.getOrNull(index)
                    if (tab != null) {
                        if (tab.url == DemoPolicy.HOME) HomeScreen(model,
                            onOpen = { model.navigate(it) }, onExtensions = { showExtensions = true })
                        else GeckoPage(tab.session, Modifier.fillMaxSize(), if (model.fullScreen) 0 else bottomObscured,
                            protectTopAndSides = !model.fullScreen,
                            allowFocus = tab.id == model.selectedId && windowFocused && !editing && !model.finder.visible)
                        if (tab.error) {
                            Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.errorContainer) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.page_error), Modifier.weight(1f))
                                    TextButton(onClick = { model.reload() }) { Text(stringResource(R.string.retry)) }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    if (model.storageReadOnly || model.saveFailed) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(stringResource(if (model.storageReadOnly) R.string.restore_failed else R.string.save_failed),
                            Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.loading && current.url != DemoPolicy.HOME) {
                        LinearProgressIndicator(progress = { current.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { bottomObscured.toDp() }))
                if (!model.fullScreen) {
                    if (!editing) FloatingBrowserToolbar(model, compact,
                        addressGesture,
                        onAddress = {
                            model.expandToolbar()
                            beginEditing()
                        },
                        onTabs = { showTabs = true }, onMenu = { showMenu = true },
                        menuExpanded = showMenu, onDismissMenu = { showMenu = false },
                        tabMenuExpanded = showTabActions, onTabMenu = { showTabActions = true },
                        onDismissTabMenu = { showTabActions = false }, onNewTab = ::createTab,
                        onExtensions = { showMenu = false; showExtensions = true },
                        onLibrary = {
                            showMenu = false
                            if (it == LibraryPage.BOOKMARKS || it == LibraryPage.HISTORY) {
                                model.finder.close()
                                hub.open(model.selectedId, current.url,
                                    if (it == LibraryPage.BOOKMARKS) AddressHubPage.BOOKMARKS else AddressHubPage.HISTORY, edit = false)
                                holdWebViewport = true
                                current.session.setFocused(false)
                            } else library = it
                        },
                        onSitePermissions = { showMenu = false; showSitePermissions = true },
                        onShare = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.url)
                            }, shareLabel))
                        },
                        onBarHeight = { toolbarHeight = it },
                        modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).fillMaxWidth()
                            .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            if (editing) AddressHub(model, suggestions, snackbar,
                onSubmit = {
                    val target = LocalBrowserSearch.resolve(hub.address.text, model.searchEngine)
                    hub.error = target.error
                    if (target.url != null && model.navigate(target.url)) finishEditing()
                }, onSuggestion = { item ->
                    if (item.tabId != null && model.tabs.any { it.id == item.tabId }) {
                        finishEditing(); model.selectTab(item.tabId)
                    } else if (model.navigate(item.url)) finishEditing()
                }, onClose = ::finishEditing)
        }
    }

    if (showSitePermissions) {
        ModalBottomSheet(onDismissRequest = { showSitePermissions = false }) { SitePermissionPanel(model) }
    }
    if (showExtensions) {
        ModalBottomSheet(onDismissRequest = { showExtensions = false }) {
            ExtensionManager(model, onOpen = { extension -> showExtensions = false; model.openExtension(extension) })
        }
    }
    if (showTabs) TabOverview(model, onNewTab = ::createTab) { showTabs = false }
    library?.let { page ->
        ModalBottomSheet(onDismissRequest = { library = null }) {
            LibraryPanel(model, page) { library = null }
        }
    }
    model.linkMenu?.let { target ->
        AlertDialog(onDismissRequest = { model.linkMenu = null }, title = { Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Column {
                Text(target.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.openLink(false) }) { Text(stringResource(R.string.open_new_tab)) }
                TextButton(onClick = { model.openLink(true) }) { Text(stringResource(R.string.open_background)) }
                TextButton(onClick = {
                    (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText(appName, target.url))
                    model.linkMenu = null; model.notify(R.string.link_copied)
                }) { Text(stringResource(R.string.copy_link)) }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, target.url) }, shareLabel))
                    model.linkMenu = null
                }) { Text(stringResource(R.string.share)) }
            } }, confirmButton = { TextButton(onClick = { model.linkMenu = null }) { Text(stringResource(R.string.cancel)) } })
    }
    model.files.offer?.let { offer ->
        AlertDialog(onDismissRequest = { model.files.confirmDownload(false) }, title = { Text(stringResource(R.string.save_download)) },
            text = { Text(stringResource(R.string.download_confirm, offer.filename)) },
            confirmButton = { TextButton(onClick = { model.files.confirmDownload(true) }) { Text(stringResource(R.string.save_file)) } },
            dismissButton = { TextButton(onClick = { model.files.confirmDownload(false) }) { Text(stringResource(R.string.cancel)) } })
    }
    model.popup?.let { popup ->
        ModalBottomSheet(onDismissRequest = model::closePopup, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(popup.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ToolIcon(Icons.Outlined.Close, R.string.close_panel, model::closePopup)
            }
            GeckoPage(popup.session, Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.55f).coerceIn(180f, 500f).dp))
        }
    }
    model.question?.let { question ->
        LaunchedEffect(question) { question.onShown() }
        AlertDialog(onDismissRequest = { model.answerQuestion(false) }, title = { Text(question.title) },
            text = { Text(question.body, Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { model.answerQuestion(true) }) { Text(stringResource(R.string.allow)) } },
            dismissButton = { TextButton(onClick = { model.answerQuestion(false) }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
/** 网页与扩展面板沿用独立生命周期；主网页由宿主统一消费顶部安全区。 */
private fun GeckoPage(session: GeckoSession, modifier: Modifier, bottomObscured: Int = 0,
    protectTopAndSides: Boolean = false, allowFocus: Boolean = true) {
    key(session) {
        AndroidView(factory = { context ->
            SafeGeckoView(context).apply {
                // Gecko 会异步恢复原生焦点；预加载的相邻页不能参与焦点搜索。
                isFocusable = false
                isFocusableInTouchMode = false
                this.protectTopAndSides = protectTopAndSides
                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                setSession(session)
            }
        }, modifier = modifier, update = { view ->
            view.protectTopAndSides = protectTopAndSides
            view.isFocusableInTouchMode = allowFocus
            view.isFocusable = allowFocus
            view.importantForAccessibility = if (allowFocus) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (allowFocus) {
                if (!view.hasFocus()) view.requestFocus()
            } else if (view.hasFocus()) view.clearFocus()
            // 沿用底部遮挡提示，工具栏收放不会改变网页物理尺寸。
            view.setVerticalClipping(bottomObscured)
        }, onReset = null, onRelease = {
            it.releaseSession()
        })
    }
}

@Composable
private fun HomeScreen(model: BrowserModel, onOpen: (String) -> Unit, onExtensions: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.home_eyebrow), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.home_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onExtensions, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Extension, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.try_extensions))
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gesture_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.gesture_body))
            }
        }
        model.bookmarks.take(6).forEach { record ->
            OutlinedButton(onClick = { onOpen(record.url) }, Modifier.fillMaxWidth()) {
                Text(record.title.ifBlank { record.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(stringResource(R.string.try_pages), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onOpen("https://example.org") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.example_page)) }
        OutlinedButton(onClick = { onOpen("https://www.baidu.com") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.baidu_page)) }
        TextButton(onClick = { onOpen("https://github.com/zhao-yc/rocks/blob/main/android/gecko-demo/README.md") }) {
            Text(stringResource(R.string.open_source))
        }
        Text(stringResource(R.string.demo_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExtensionManager(model: BrowserModel, onOpen: (org.mozilla.geckoview.WebExtension) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.extensions), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.extensions_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!model.extensionsReady) LinearProgressIndicator(Modifier.fillMaxWidth())
        val installedOnly = model.extensions.filter { ext -> addonCatalog.none { it.id == ext.id } }
        val choices = addonCatalog + installedOnly.map { AddonChoice(it.id, it.metaData?.name ?: it.id, R.string.other_extension_description, null) }
        choices.forEach { choice ->
            val extension = model.extensions.firstOrNull { it.id == choice.id }
            val enabled = extension?.metaData?.enabled == true
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.primary)
                        Text(choice.name, Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.Bold)
                        if (extension != null) Switch(checked = enabled, enabled = model.busyId == null,
                            onCheckedChange = { model.setEnabled(extension, it) })
                    }
                    Text(stringResource(choice.description), style = MaterialTheme.typography.bodySmall)
                    Text(if (choice.id == DemoPolicy.PAPER_ID) stringResource(R.string.bundled_source) else stringResource(R.string.mozilla_source),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (model.busyId == choice.id) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (model.installing) TextButton(onClick = model::cancelInstall) { Text(stringResource(R.string.cancel)) }
                    } else if (extension == null) {
                        Button(onClick = { model.install(choice) }, enabled = model.extensionsReady && model.busyId == null) {
                            Text(stringResource(R.string.install))
                        }
                    } else {
                        Text(stringResource(if (enabled) R.string.enabled_version else R.string.disabled_version, extension.metaData?.version.orEmpty()),
                            style = MaterialTheme.typography.labelSmall)
                        if (!extension.isBuiltIn) TextButton(onClick = { model.updateExtension(extension) }, enabled = model.busyId == null) {
                            Text(stringResource(R.string.check_update))
                        }
                        Row {
                            FilledTonalButton(onClick = { onOpen(extension) }, enabled = enabled && model.busyId == null) {
                                Text(stringResource(R.string.open_panel))
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { model.uninstall(extension) }, enabled = model.busyId == null) { Text(stringResource(R.string.uninstall)) }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.extension_steps), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, stringResource(label)) }
}

@Composable
internal fun displayAddress(url: String): String = when {
    url == DemoPolicy.HOME -> stringResource(R.string.home)
    url.startsWith("moz-extension://") -> stringResource(R.string.extension_page)
    else -> Uri.parse(url).host ?: url
}
