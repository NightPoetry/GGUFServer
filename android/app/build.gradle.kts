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
        // versionNote: curate 切面真正开始收窄（阶段 3.5）——便签/朗读各自登记 curate 处理器，用正则级快判定决定「本轮该不该把自己的工具露给模型」，闲聊轮不再注入工具教学（省 1B 极其金贵的 system 预算）；SDK 补上 gguf.hooks.curate（阶段 3 只做了宿主侧，JS 侧一直 undefined、插件根本注册不上）；内核补两条纪律——弃权豁免（没登记 curate 的插件的 callable 不因别人收窄而消失）+ 动态注入位（有人表态时教学条款从首条 system 改插到末条用户消息之前，否则每次意图切换整条 prompt 前缀作废、手机上要重算整段 prefill）；fail-open 升为 AspectBus 自带属性（处理器抛错一律当无返回，CancellationException 原样上抛）；heavymem 1.0.6 召回块按相关度升序（最相关贴指令）+ 计算类问答改「模型只抽数字(json_schema)、加减乘除交 JS」两段式；新增 19 项 JVM 单测（AspectBus 分发纪律/curate 语义 + ToolTagScanner 流式零泄漏，正是真机验收里两条 ⚠️ 覆盖不到的路径）与 node scripts/check-plugins.js 插件离线自检。设计见规范 §6.14/§13.4
        versionCode = 15
        versionName = "1.1.4"

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

    // 纯 JVM 单测（./gradlew :app:testDebugUnitTest）：只覆盖不需要真机的纯逻辑内核件——
    // 流式工具标记扫描器、切面分发组合子的 fail-open/预算/curate 语义。这些正是真机验收里
    // 「模型不肯自发吐标记」「不方便现场注错」而覆盖不到的路径（见 docs/切面微内核迁移进展.md）。
    // returnDefaultValues：内核里的 android.util.Log 在 JVM 上是 stub，让它静默返回 0 而不是抛异常。
    testOptions { unitTests.isReturnDefaultValues = true }

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

    // 单测：JUnit4 + 真 org.json 实现（android.jar 里的 org.json 在 JVM 上只是空壳）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
