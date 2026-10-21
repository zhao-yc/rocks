package org.rocks.geckodemo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {
    private val browser: BrowserModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    override fun onResume() { super.onResume(); browser.setForeground(true) }
    override fun onPause() { browser.setForeground(false); super.onPause() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(model: BrowserModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showExtensions by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val current = model.current
    val pager = rememberPagerState(pageCount = { model.tabs.size })
    var contentWidth by remember { mutableFloatStateOf(1f) }
    var addressWidth by remember { mutableFloatStateOf(1f) }
    var gestureStart by remember { mutableIntStateOf(0) }
    val dragState = rememberDraggableState { delta ->
        scope.launch { pager.scrollBy(-delta * contentWidth / addressWidth.coerceAtLeast(1f)) }
    }

    LaunchedEffect(model.selectedId, model.tabs.size) {
        val index = model.tabs.indexOfFirst { it.id == model.selectedId }
        if (index >= 0 && pager.settledPage != index && !pager.isScrollInProgress) pager.animateScrollToPage(index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { index ->
            if (!pager.isScrollInProgress) model.tabs.getOrNull(index)?.let { model.selectTab(it.id) }
        }
    }
    LaunchedEffect(model.notice) {
        model.notice?.let { val message = it; model.notice = null; snackbar.showSnackbar(message) }
    }
    LaunchedEffect(editing) { if (editing) focusRequester.requestFocus() }
    LaunchedEffect(model.fullScreen) {
        val window = (context as ComponentActivity).window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (model.fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    BackHandler(enabled = model.popup != null || editing || model.fullScreen || current.canGoBack) {
        when {
            model.popup != null -> model.closePopup()
            editing -> editing = false
            else -> model.back()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (model.fullScreen) Modifier else Modifier.systemBarsPadding()).imePadding()) {
            if (!model.fullScreen) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rocks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.demo_badge), Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    ToolIcon(Icons.Outlined.Extension, R.string.extensions, { showExtensions = true })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { contentWidth = it.width.toFloat() }) {
                HorizontalPager(state = pager, userScrollEnabled = false, beyondViewportPageCount = 1,
                    key = { model.tabs[it].id }, modifier = Modifier.fillMaxSize()) { index ->
                    val tab = model.tabs.getOrNull(index)
                    if (tab != null) {
                        if (tab.url == DemoPolicy.HOME) HomeScreen(
                            onOpen = { model.navigate(it) },
                            onExtensions = { showExtensions = true },
                        ) else GeckoPage(tab.session, Modifier.fillMaxSize())
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
                if (current.progress < 100 && current.url != DemoPolicy.HOME) {
                    LinearProgressIndicator(progress = { current.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (!model.fullScreen) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        if (editing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                                    placeholder = { Text(stringResource(R.string.address_hint)) },
                                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                    shape = RoundedCornerShape(20.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { if (model.navigate(address.text)) editing = false }))
                                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                                    .onSizeChanged { addressWidth = it.width.toFloat() }
                                    .draggable(dragState, Orientation.Horizontal,
                                        onDragStarted = { gestureStart = pager.settledPage },
                                        onDragStopped = { velocity ->
                                            val offset = pager.currentPage + pager.currentPageOffsetFraction - gestureStart
                                            val target = DemoPolicy.swipeTarget(gestureStart, offset, velocity, model.tabs.size)
                                            scope.launch {
                                                pager.animateScrollToPage(target, animationSpec = spring(stiffness = 380f))
                                                model.tabs.getOrNull(target)?.let { model.selectTab(it.id) }
                                            }
                                        })
                                    .clickable {
                                        address = TextFieldValue(if (current.url == DemoPolicy.HOME) "" else current.url,
                                            selection = TextRange(0, if (current.url == DemoPolicy.HOME) 0 else current.url.length))
                                        editing = true
                                    }) {
                                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (current.url.startsWith("https://")) Icons.Outlined.Lock else Icons.Outlined.Language,
                                        contentDescription = null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(displayAddress(current.url), Modifier.weight(1f).padding(horizontal = 9.dp),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text("${model.tabs.indexOf(current) + 1}/${model.tabs.size}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, R.string.back, { model.back() }, current.canGoBack)
                            ToolIcon(Icons.AutoMirrored.Outlined.ArrowForward, R.string.forward, { current.session.goForward() }, current.canGoForward)
                            ToolIcon(Icons.Outlined.Refresh, R.string.reload, { model.reload() }, current.url != DemoPolicy.HOME)
                            ToolIcon(Icons.Outlined.Home, R.string.home, { model.home() })
                            ToolIcon(Icons.Outlined.Share, R.string.share, {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.url)
                                }, context.getString(R.string.share)))
                            }, DemoPolicy.isWebUrl(current.url))
                            BadgedBox(badge = { Badge { Text(model.tabs.size.toString()) } }) {
                                ToolIcon(Icons.Outlined.Tab, R.string.tabs, { showTabs = true })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExtensions) {
        ModalBottomSheet(onDismissRequest = { showExtensions = false }) {
            ExtensionManager(model, onOpen = { extension -> showExtensions = false; model.openExtension(extension) })
        }
    }
    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tabs), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.newTab(DemoPolicy.HOME); showTabs = false }) {
                    Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.new_tab))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 24.dp)) {
                items(model.tabs.toList(), key = { it.id }) { tab ->
                    ListItem(headlineContent = { Text(tab.title.ifBlank { displayAddress(tab.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(displayAddress(tab.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { RadioButton(selected = tab.id == model.selectedId, onClick = { model.selectTab(tab.id); showTabs = false }) },
                        trailingContent = { ToolIcon(Icons.Outlined.Close, R.string.close_tab, { model.closeTab(tab.id) }) },
                        modifier = Modifier.clickable { model.selectTab(tab.id); showTabs = false })
                }
            }
        }
    }
    model.popup?.let { popup ->
        ModalBottomSheet(onDismissRequest = model::closePopup, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(popup.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ToolIcon(Icons.Outlined.Close, R.string.close_panel, model::closePopup)
            }
            GeckoPage(popup.session, Modifier.fillMaxWidth().height(440.dp))
        }
    }
    model.question?.let { question ->
        AlertDialog(onDismissRequest = { model.answerQuestion(false) }, title = { Text(question.title) },
            text = { Text(question.body, Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { model.answerQuestion(true) }) { Text(stringResource(R.string.allow)) } },
            dismissButton = { TextButton(onClick = { model.answerQuestion(false) }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun GeckoPage(session: GeckoSession, modifier: Modifier) {
    key(session) {
        AndroidView(factory = { context ->
            GeckoView(context).apply {
                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                setSession(session)
            }
        }, modifier = modifier, onRelease = { it.releaseSession() })
    }
}

@Composable
private fun HomeScreen(onOpen: (String) -> Unit, onExtensions: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
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
        Text(stringResource(R.string.try_pages), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onOpen("https://example.org") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.example_page)) }
        OutlinedButton(onClick = { onOpen("https://www.baidu.com") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.baidu_page)) }
        TextButton(onClick = { onOpen("https://github.com/zhao-yc/rocks/blob/build/gecko-demo-20261021/android/gecko-demo/README.md") }) {
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
        addonCatalog.forEach { choice ->
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
                    Text(if (choice.uri == null) stringResource(R.string.bundled_source) else stringResource(R.string.mozilla_source),
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
private fun displayAddress(url: String): String = when {
    url == DemoPolicy.HOME -> stringResource(R.string.home)
    url.startsWith("moz-extension://") -> stringResource(R.string.extension_page)
    else -> Uri.parse(url).host ?: url
}
