package org.rocks.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

/** 展示域名，编辑时始终提供完整 URL；横划范围只限地址栏，不截获网页和系统边缘手势。 */
@Composable
internal fun BrowserAddressBar(
    tabId: String, url: String, progress: Int, editing: Boolean,
    onEditingChange: (Boolean) -> Unit, onNavigate: (String) -> Boolean,
    onSwitchTab: (next: Boolean) -> Unit, onReloadOrStop: () -> Unit,
) {
    var field by rememberSaveable(tabId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(url)) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cancel = {
        onEditingChange(false)
        focus.clearFocus()
        keyboard?.hide()
    }
    BackHandler(enabled = editing) { cancel() }
    LaunchedEffect(editing) { if (editing) focusRequester.requestFocus() }
    if (editing) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field, onValueChange = { field = it }, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("address-input"),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    if (field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue("") }) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear_address))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (onNavigate(field.text)) cancel() }),
                shape = RoundedCornerShape(18.dp),
            )
            TextButton(onClick = { cancel() }, modifier = Modifier.testTag("cancel-address")) {
                Text(stringResource(R.string.cancel))
            }
        }
    } else {
        val threshold = with(LocalDensity.current) { 56.dp.toPx() }
        val switchTab by rememberUpdatedState(onSwitchTab)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().testTag("address-bar").pointerInput(tabId, threshold) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        if (distance < -threshold) switchTab(true)
                        else if (distance > threshold) switchTab(false)
                        distance = 0f
                    },
                    onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                )
            }) {
            Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = url.ifEmpty { stringResource(R.string.search_hint) }
                Row(Modifier.weight(1f).heightIn(min = 52.dp).clickable {
                    field = TextFieldValue(url, TextRange(0, url.length))
                    onEditingChange(true)
                }.semantics { contentDescription = label }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (url.isNotEmpty()) {
                        Text(if (url.startsWith("https://", ignoreCase = true)) "HTTPS" else "HTTP", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (url.isEmpty()) stringResource(R.string.search_hint) else pageHost(url),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                }
                if (url.isNotEmpty()) IconButton(onClick = onReloadOrStop) {
                    Icon(if (progress < 100) Icons.Default.Close else Icons.Default.Refresh,
                        stringResource(if (progress < 100) R.string.stop_loading else R.string.reload))
                }
            }
        }
    }
}

internal fun pageHost(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
