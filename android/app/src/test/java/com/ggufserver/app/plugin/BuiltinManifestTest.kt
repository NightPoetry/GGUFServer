package com.ggufserver.app.plugin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置插件的 manifest **必须**都能被 [PluginManifest.parse] 接受。
 *
 * 为什么值得一条专门的单测：`PluginStore.bootstrapBuiltins` 里是
 * `for (id in ids) runCatching { releaseBuiltin(am, id) }`，而 `releaseBuiltin` 遇到
 * 解析失败是 `?: return`——**两层静默**。于是一个字段写错的 manifest 表现为
 * 「插件改了却没生效」：assets 里明明是新版本，设备上还是旧文件，没有任何日志、没有任何提示。
 * 2026-07-27 就这么坑了一次（乐句插件 1.1.0 死活不释放）。这条测试把它变成编译期就能发现的错。
 */
class BuiltinManifestTest {

    private fun assetsPluginsDir(): File {
        // 单测工作目录是 android/app，assets 在 src/main/assets
        val d = File("src/main/assets/plugins")
        assertTrue("找不到内置插件目录：${d.absolutePath}", d.isDirectory)
        return d
    }

    @Test
    fun `所有内置插件的 manifest 都能通过校验`() {
        val dirs = assetsPluginsDir().listFiles { f: File -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        assertTrue("内置插件目录是空的？", dirs.isNotEmpty())
        val bad = ArrayList<String>()
        for (dir in dirs) {
            val mf = File(dir, "manifest.json")
            if (!mf.exists()) { bad += "${dir.name}: 缺 manifest.json"; continue }
            val r = PluginManifest.parse(mf.readText())
            val err = r.exceptionOrNull()
            if (err != null) bad += "${dir.name}: ${err.message}"
        }
        assertTrue(
            "以下内置插件的 manifest 过不了校验（后果是「改了却不释放到设备」且全程静默）：\n  " +
                bad.joinToString("\n  "),
            bad.isEmpty(),
        )
    }

    @Test
    fun `内置插件声明的入口文件都真实存在`() {
        val bad = ArrayList<String>()
        for (dir in assetsPluginsDir().listFiles { f: File -> f.isDirectory }?.sortedBy { it.name }.orEmpty()) {
            val mf = File(dir, "manifest.json")
            val m = PluginManifest.parse(mf.readText()).getOrNull() ?: continue   // 上一条测试负责报错
            if (!File(dir, m.entryUi).exists()) bad += "${dir.name}: entry.ui=${m.entryUi} 不存在"
            m.entryService?.let { if (!File(dir, it).exists()) bad += "${dir.name}: entry.service=$it 不存在" }
            if (!File(dir, "icon.png").exists()) bad += "${dir.name}: 缺 icon.png（规范 §3 必需）"
        }
        assertTrue("入口文件缺失：\n  " + bad.joinToString("\n  "), bad.isEmpty())
    }
}
