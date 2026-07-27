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
        // versionNote: 修两个 KV/熔断层面的真缺陷（都由「桌面端切面反哺评估」核对纪律时撞见，不是新功能）：①教学注入位曾用「本轮 exposure 是否为 null」判定，而 null 有两种成因——「无人登记 curate」（静态）与「登记了但全员异常/超时」（逐轮可变的运行结果），后者会让首条 system 的字节随 curate 的成败逐轮抖动（违反红线 5「head 必须字节稳定」），抖动那轮连同抖回去的下一轮各重算一次整段 prefill（实测 169 token ≈ 1.9s）；判据改为「有没有插件登记 curate」，失败轮退化成「在动态位全露」，head 恒定、历史 KV 不受牵连。②App 冷启时 service WebView 有几百毫秒到几秒未就绪，此间 dispatch 石沉大海只能等超时，既白吃满钩子预算又被按「超时」记进熔断计数——而熔断状态是落盘的，冷启后连发三条请求就能把一个好插件永久旁路；现在 dispatch 遇未就绪立即返回且不记熔断，curate 面把「登记了但未就绪」按弃权处理（「没准备好」≠「坏了」：等模型几秒后吐标记时它早就绪、tool 钩子跑得动，不该藏它的工具）。另：真机补量 curate 收窄的延迟收益（省 1641ms prefill vs 45ms 判定成本，净赚约 36:1）；规范 §13.7 新增「跨宿主契约：哪些是不变量、哪些是宿主绑定」，吸收桌面端「小模型轮椅」反哺的 scope/governs 辖域契约（含 own/global 记账相反这一条关键坑）。单测 19→21 项
        // 上一版 versionNote: curate 切面真正开始收窄（阶段 3.5）——便签/朗读各自登记 curate 处理器，用正则级快判定决定「本轮该不该把自己的工具露给模型」，闲聊轮不再注入工具教学（省 1B 极其金贵的 system 预算）；SDK 补上 gguf.hooks.curate（阶段 3 只做了宿主侧，JS 侧一直 undefined、插件根本注册不上）；内核补两条纪律——弃权豁免（没登记 curate 的插件的 callable 不因别人收窄而消失）+ 动态注入位（有人表态时教学条款从首条 system 改插到末条用户消息之前，否则每次意图切换整条 prompt 前缀作废、手机上要重算整段 prefill）；fail-open 升为 AspectBus 自带属性（处理器抛错一律当无返回，CancellationException 原样上抛）；heavymem 1.0.6 召回块按相关度升序（最相关贴指令）+ 计算类问答改「模型只抽数字(json_schema)、加减乘除交 JS」两段式；新增 19 项 JVM 单测（AspectBus 分发纪律/curate 语义 + ToolTagScanner 流式零泄漏，正是真机验收里两条 ⚠️ 覆盖不到的路径）与 node scripts/check-plugins.js 插件离线自检。设计见规范 §6.14/§13.4
        versionCode = 16
        versionName = "1.1.5"

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
