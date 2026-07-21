plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ggufserver.app"
    compileSdk = 35
    // 指定 NDK，让 AGP 能找到 llvm-strip 在 release 构建时剥离 jniLibs 里 .so 的调试符号
    // （否则会 "Unable to strip ... packaging them as they are"，正式包白白大出 ~20MB）。
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.ggufserver.app"
        minSdk = 28
        targetSdk = 35
        // 版本规则：每次发正式包 versionName +0.0.1、versionCode +1，旧 APK 按版本号留档（见 docs/打包流程.md）
        // versionNote: 插件消息能力三连——①消息角标：manifest messageActions + messageAction 钩子（chat:action，纯 UI 侧不破零插件快路径）；②消息卡片：插件 WebView 自绘正文（chat:card，工具返回 card 附到本轮回复同条混排 / gguf.card.post 独立卡片，data.text 内部文本降级），交互即卡片页 DOM 事件；③gguf.tts.* 系统语音桥；内置朗读插件 1.1.2（角标朗读 + tts.say 工具→语音气泡自动播放/长按展开文字）；panel.topbar 可配置；顶栏改两端对齐槽位网格（44dp 提前算位、与头像 18dp 视觉线对齐）；门户下拉热区改回上 1/4；能力提供者=补丁式插件依赖（provides:["tts"] 接管全机发声、调穿默认实现可变调，内置猫嗓/简单变声示范）；中间件生效范围用户三选一（middleware.configurable，仅对内/内外通用/仅对外）；定制音色插件三来源（端侧 sherpa-onnx VITS JNI 神经推理·aishell3 174 音色·常驻后 64ms，局域网 OpenAI 兼容 /v1/audio/speech 与 GPT-SoVITS /tts 克隆）；新桥 gguf.tts.synthesize/synthesizeLocal 与 gguf.net.fetchAudio（宿主代取绕 CORS）；新增 docs/插件开发指南.md 与 docs/交互设计技巧/
        versionCode = 13
        versionName = "1.1.2"

        // The bundled llama-server binary is arm64 only. Virtually every phone
        // from the last ~8 years is arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }
    }

    // 发布签名：密钥库在 android/keystore/（个人项目，密码直接写在这，README 有备注；
    // 丢了密钥就没法给老用户发同签名升级包，注意备份）
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/ggufserver-release.jks")
            storePassword = "gguf-server-2026"
            keyAlias = "ggufserver"
            keyPassword = "gguf-server-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            // Keep native libs uncompressed & extracted to nativeLibraryDir so the
            // llama-server executable can actually be exec()'d at runtime.
            useLegacyPackaging = true
        }
    }
}

// 只让 Debug 包保留 .so 调试符号（真机崩溃时 logcat 直接显示函数名/行号，便于定位）；
// 正式包（release）仍按上面的 ndkVersion 自动 strip，体积小、给用户。
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.packaging.jniLibs.keepDebugSymbols.add("**/*.so")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
