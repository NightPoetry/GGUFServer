package com.ggufserver.app

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.ggufserver.app.ui.LocalWarmTones
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
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
                // 宽屏封顶（FasFox 面板语态）：≥600dp 时整个 App 收进 760dp 居中列，
                // 两侧同底色留白 + 发丝线，聊天/设置/市场所有屏一次到位地解决
                // 「内容全宽拉伸、输入框贴边、顶栏图标铺满」的填充问题（用户令「全面参考FasFox」）。
                // 手机竖屏（<600dp）不受影响，仍全幅。
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                            Box(
                                Modifier
                                    .width(760.dp)
                                    .fillMaxHeight()
                                    .background(LocalWarmTones.current.bg)
                                    .border(0.6.dp, LocalWarmTones.current.line, RectangleShape)
                            ) {
                                AppRoot()
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) { AppRoot() }
                    }
                }
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
