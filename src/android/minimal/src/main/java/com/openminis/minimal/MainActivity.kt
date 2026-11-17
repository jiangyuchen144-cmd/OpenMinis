package com.openminis.minimal

// [T-minimal] Three screens, one state machine.
//
// There is no navigation library. The graph is: welcome → list → chat, and back.
// A NavHost would add a dependency, a route DSL and a back-stack to reason about
// in exchange for handling a case this app does not have (deep links, nested
// graphs, saved-state restoration across process death).

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.minimal.data.Models
import com.openminis.minimal.data.Session
import com.openminis.minimal.ui.ChatScreen
import com.openminis.minimal.ui.ChatViewModel
import com.openminis.minimal.ui.Ink
import com.openminis.minimal.ui.InkMuted
import com.openminis.minimal.ui.MinimalTheme
import com.openminis.minimal.ui.Paper
import com.openminis.minimal.ui.SessionListScreen
import com.openminis.minimal.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // [T-minimal] An Intent may carry the key: `am start -n
        // com.openminis.minimal/.MainActivity --es api_key sk-...`.
        //
        // This exists so a key can be installed without baking it into the APK
        // (where anyone holding the file can read it) and without a debug build
        // (whose only purpose would be to make run-as work once). It is a
        // convenience for the person who owns the phone, nothing more — the
        // typed field remains the normal path.
        minimalApp.settings.let { s ->
            intent?.getStringExtra(EXTRA_API_KEY)?.takeIf { it.isNotBlank() }?.let {
                s.apiKey = it
                s.onboarded = true
            }
        }

        setContent {
            MinimalTheme { MinimalRoot() }
        }
    }

    private companion object {
        const val EXTRA_API_KEY = "api_key"
    }
}

private sealed interface Screen {
    data object Welcome : Screen
    data object List : Screen
    data object Chat : Screen
}

@Composable
private fun MinimalRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.minimalApp
    val settings = app.settings
    val store = app.sessions

    val vm: ChatViewModel = viewModel()

    var screen by remember {
        mutableStateOf<Screen>(
            if (settings.onboarded && settings.hasKey) Screen.List else Screen.Welcome,
        )
    }
    var sessions by remember { mutableStateOf(store.list()) }
    var showSettings by remember { mutableStateOf(false) }
    var memoryVersion by remember { mutableStateOf(0) }
    var importResult by remember { mutableStateOf<String?>(null) }

    // [T-minimal] SAF: pick a FOLDER, not a file. Memory is a corpus — the
    // server writes several .md files and the app should not ask the user to
    // pick them one at a time every time one changes.
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val ok = com.openminis.minimal.data.MemoryStore.setSharedTree(ctx, uri)
            val st = com.openminis.minimal.data.MemoryStore.status(ctx)
            importResult = when {
                !ok -> "系统拒绝了这个文件夹的授权"
                st.files == 0 -> "这个文件夹里没有 .md 文件"
                else -> "已连接：${st.files} 个文件 · ${st.chars} 字"
            }
            // The ViewModel caches the prompt for the life of the session; a new
            // folder must not silently apply mid-conversation.
            vm.reloadMemory()
            memoryVersion++
        }
    }

    fun refresh() {
        sessions = store.list()
    }

    // System back: chat → list, settings → close. A welcome screen with no key
    // cannot go anywhere, so back exits there.
    BackHandler(enabled = screen == Screen.Chat || showSettings) {
        if (showSettings) {
            showSettings = false
            return@BackHandler
        }
        if (vm.streaming) vm.stop()
        refresh()
        screen = Screen.List
    }

    when (screen) {
        Screen.Welcome -> WelcomeScreen(
            onVerified = { key ->
                settings.apiKey = key
                settings.onboarded = true
                refresh()
                screen = Screen.List
            },
        )

        Screen.List -> SessionListScreen(
            sessions = sessions,
            onOpen = { id ->
                vm.openSession(id)
                screen = Screen.Chat
            },
            onNew = {
                vm.startNew()
                screen = Screen.Chat
            },
            onDelete = { id ->
                store.delete(id)
                refresh()
            },
            onSettings = { showSettings = true },
        )

        Screen.Chat -> ChatScreen(
            title = if (vm.title.isBlank()) stringResource(R.string.sessions_new) else vm.title,
            messages = vm.messages,
            streaming = vm.streaming,
            errorText = vm.errorText,
            onSend = vm::send,
            onStop = vm::stop,
            onRetry = vm::retry,
            onBack = {
                if (vm.streaming) vm.stop()
                refresh()
                screen = Screen.List
            },
        )
    }

    if (showSettings) {
        // memoryVersion is read here so the dialog recomposes after an import.
        val mem = remember(memoryVersion) {
            com.openminis.minimal.data.MemoryStore.status(ctx)
        }
        SettingsDialog(
            model = settings.model,
            hasKey = settings.hasKey,
            memoryShared = mem.shared,
            memoryFiles = mem.files,
            memoryChars = mem.chars,
            importResult = importResult,
            onModelChange = { settings.model = it },
            onImportMemory = { importLauncher.launch(null) },
            onUnlinkMemory = {
                com.openminis.minimal.data.MemoryStore.clearSharedTree(ctx)
                vm.reloadMemory()
                importResult = null
                memoryVersion++
            },
            onDismiss = { showSettings = false; importResult = null },
        )
    }
}

@Composable
private fun SettingsDialog(
    model: String,
    hasKey: Boolean,
    memoryShared: Boolean,
    memoryFiles: Int,
    memoryChars: Int,
    importResult: String?,
    onModelChange: (String) -> Unit,
    onImportMemory: () -> Unit,
    onUnlinkMemory: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        title = {
            Text(
                stringResource(R.string.settings_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_model),
                    fontSize = 13.sp,
                    color = InkMuted,
                )
                Spacer(Modifier.height(6.dp))
                Models.all.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = model == id,
                                onClick = { onModelChange(id) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RadioButton(selected = model == id, onClick = { onModelChange(id) })
                        Text(label, fontSize = 15.sp, color = Ink)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.settings_key),
                    fontSize = 13.sp,
                    color = InkMuted,
                )
                Text(
                    if (hasKey) "••••••••（已保存）" else "未设置",
                    fontSize = 15.sp,
                    color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_key_hint),
                    fontSize = 12.sp,
                    color = InkMuted,
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.settings_memory),
                    fontSize = 13.sp,
                    color = InkMuted,
                )
                Text(
                    when {
                        memoryShared && memoryFiles > 0 -> "共享中 · $memoryFiles 个文件 · $memoryChars 字"
                        memoryShared -> "已连接，文件夹是空的"
                        memoryFiles > 0 -> "仅本机 · $memoryFiles 个文件"
                        else -> stringResource(R.string.settings_memory_empty)
                    },
                    fontSize = 15.sp,
                    color = Ink,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(
                        onClick = onImportMemory,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            stringResource(if (memoryShared) R.string.settings_memory_change else R.string.settings_memory_import),
                            fontSize = 14.sp,
                            color = com.openminis.minimal.ui.Terracotta,
                        )
                    }
                    if (memoryShared) {
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            onClick = onUnlinkMemory,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Text(
                                stringResource(R.string.settings_memory_unlink),
                                fontSize = 14.sp,
                                color = InkMuted,
                            )
                        }
                    }
                }
                if (importResult != null) {
                    Text(importResult, fontSize = 12.sp, color = InkMuted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close), color = Ink)
            }
        },
    )
}
