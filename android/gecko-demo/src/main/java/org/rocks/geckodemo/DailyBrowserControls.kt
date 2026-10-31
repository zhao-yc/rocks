package org.rocks.geckodemo

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun DefaultBrowserSetting() {
    val context = LocalContext.current
    val manager = context.getSystemService(RoleManager::class.java)
    var held by remember { mutableStateOf(manager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true) }
    var unavailable by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        held = manager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) held = manager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Text(stringResource(R.string.default_browser), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (held) R.string.default_browser_active else R.string.default_browser_note), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        runCatching {
            if (manager?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true && !held)
                launcher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
            else launcher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure { unavailable = true }
    }) { Text(stringResource(if (held) R.string.default_browser_settings else R.string.set_default_browser)) }
    if (unavailable) Text(stringResource(R.string.system_settings_unavailable), color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun FindInPageControls(model: BrowserModel) {
    val finder = model.finder
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    DisposableEffect(Unit) { onDispose { focusManager.clearFocus(); keyboard?.hide() } }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        OutlinedTextField(value = finder.query, onValueChange = finder::change, singleLine = true,
            placeholder = { Text(stringResource(R.string.find_in_page)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); finder.next(false) }))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (finder.query.isEmpty()) stringResource(R.string.find_hint)
                else if (finder.busy) stringResource(R.string.find_searching)
                else if (finder.total < 0) stringResource(R.string.find_count_unknown, finder.current)
                else stringResource(R.string.find_count, finder.current, finder.total),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { keyboard?.hide(); finder.next(true) }, enabled = finder.query.isNotEmpty() && !finder.busy && finder.current > 0) {
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.find_previous))
            }
            IconButton(onClick = { keyboard?.hide(); finder.next(false) }, enabled = finder.query.isNotEmpty() && !finder.busy && finder.current > 0) {
                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.find_next))
            }
            IconButton(onClick = { finder.close(); focusManager.clearFocus(); keyboard?.hide() }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.find_close))
            }
        }
    }
}

@Composable
internal fun SitePermissionPanel(model: BrowserModel) {
    val context = LocalContext.current
    val current = model.current
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.site_permissions), style = MaterialTheme.typography.headlineSmall)
        Text(BrowserEntryPolicy.secureOrigin(current.url) ?: current.url, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.site_permissions_note), style = MaterialTheme.typography.bodySmall)
        if (current.recordingCamera || current.recordingMicrophone)
            Text(stringResource(R.string.recording_active), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = model::resetCurrentSitePermissions,
            enabled = BrowserEntryPolicy.secureOrigin(current.url) != null) { Text(stringResource(R.string.reset_site_permissions)) }
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null))) }.onFailure { failed = true }
        }) { Text(stringResource(R.string.android_app_permissions)) }
        if (failed) Text(stringResource(R.string.system_settings_unavailable), color = MaterialTheme.colorScheme.error)
    }
}
