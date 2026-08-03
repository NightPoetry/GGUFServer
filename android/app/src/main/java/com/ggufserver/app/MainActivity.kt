package com.ggufserver.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ggufserver.app.ui.ChatScreen
import com.ggufserver.app.ui.GgufTheme
import com.ggufserver.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GgufTheme {
                AppRoot()
            }
        }
    }
}

private enum class Screen { CHAT, SETTINGS }

/**
 * 入口规则：服务器没启动 → 进来就是设置（服务器配置）；
 * 启动成功 → 自动切到聊天，设置从聊天右上角的服务器图标进。
 * 服务停止/出错 → 退回设置。
 */
@Composable
private fun AppRoot() {
    val state by ServerBus.state.collectAsStateWithLifecycle()
    val restarting by ServerBus.restarting.collectAsStateWithLifecycle()
    val chatVm: ChatViewModel = viewModel()
    var screen by rememberSaveable {
        mutableStateOf(if (ServerBus.state.value == ServerState.RUNNING) Screen.CHAT else Screen.SETTINGS)
    }
    var wasRunning by rememberSaveable { mutableStateOf(ServerBus.state.value == ServerState.RUNNING) }

    LaunchedEffect(state, restarting) {
        val running = state == ServerState.RUNNING
        if (running && !wasRunning) {
            if (!restarting) chatVm.newChat()   // 新一轮服务 = 新会话；应用配置的重启则保留会话
            screen = Screen.CHAT
        }
        if (!running && state != ServerState.STARTING && !restarting) {
            screen = Screen.SETTINGS  // 停止 / 出错：聊天不可用，回配置（应用配置的重启除外）
        }
        wasRunning = running
    }

    when (screen) {
        Screen.CHAT -> ChatScreen(chatVm, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> {
            val running = state == ServerState.RUNNING
            if (running) BackHandler { screen = Screen.CHAT }
            SettingsScreen(onBack = if (running) fun() { screen = Screen.CHAT } else null)
        }
    }
}
