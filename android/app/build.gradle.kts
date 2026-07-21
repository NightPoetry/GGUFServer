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
        // versionNote: 插件系统演进为「切面微内核」阶段1-3（等价重构·行为不变·真机验收通过）——内核唯一原语=在一小组具名切面上注册处理器并按预算/权限/熔断/fail-open分发（注册面 callable/ui/capability·管线面 route/curate/context/params/generate/stream/after·分发面 dispatch）；新增 AspectBus 内核（Aspects 切面常量+每切面预算 / AspectRegistry 启用插件→管线面投影 / routeUntilTakeOver·parallel·serialForEach 分发组合子），把 Gateway 固定流水线改为按管线面顺序 dispatch；工具系统收敛为内置 type:tool 运行时插件（CapabilityRuntime 接口 + Runtimes 注册表 + ToolRuntime，"加一种能力类型=加一个 runtime、内核不动"由此为真）；新增 curate 切面（按需给小模型露 callable，无处理器则 exposure=null 全露、逐字节等价 v1）+ generate 显式化（takeOver=替换默认 generate）；现有 manifest 字段/钩子名/桥接口/真机行为全不变，KV 前缀缓存字节稳定（真机 prompt_n 28→1）。设计见规范 §13，迁移与真机结果见 docs/切面微内核迁移进展.md
        versionCode = 14
        versionName = "1.1.3"

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
