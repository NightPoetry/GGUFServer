package com.ggufserver.app.server

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * “最优引擎选择测评”：针对某个模型跑一小段 llama-bench，实测比较 CPU 与 GPU(Vulkan)，选最快的引擎。
 *
 * - 用打包进 jniLibs 的 llama-bench（改名 `libllamabench.so`）作为子进程，与 llama-server 共用同一套原生库，
 *   因此测出来的速度就是实际服务会得到的速度。
 * - GPU 到底帮不帮忙由**每台设备 × 每个模型的实测数字**决定，而不是写死——Mali 上 GPU 更慢会被自动判为 CPU；
 *   Adreno/高端 GPU 上更快则会选 GPU。
 * - GPU 运行崩溃（子进程 SIGSEGV/被杀）或超时 → 判该模型 GPU 不可用，绝不拖垮 app 本体。
 *
 * 始终注入 `GGML_VK_DISABLE_BDA=1`：打包了 Vulkan 后，即便 `-ngl 0` 纯 CPU 也会走 Vulkan 的 host buffer 分配，
 * 部分驱动（Mali）会在 `vkGetBufferDeviceAddress` 空指针上崩溃；关掉这个次要优化让 Vulkan 存在也安全。
 */
object EngineBenchmark {

    const val BENCH_BIN = "libllamabench.so"

    /** 单个引擎配置的测评结果。ok=false 表示崩溃/超时/无结果（如 GPU 不兼容）。 */
    data class Row(
        val label: String,   // "CPU" / "GPU"
        val ngl: Int,        // 0 = 纯 CPU；99 = 全量 GPU offload
        val ok: Boolean,
        val prefill: Double, // pp t/s（prompt 处理）
        val gen: Double,     // tg t/s（逐字生成）
        val note: String     // 失败原因 / 附注
    )

    data class Outcome(
        val rows: List<Row>,
        val bestNgl: Int,    // 选定引擎的 -ngl
        val summary: String  // 存进 SettingsStore、展示给用户的一行摘要
    )

    /** 是否打包了 Vulkan(GPU) 后端。 */
    fun hasVulkan(ctx: Context): Boolean =
        File(ctx.applicationInfo.nativeLibraryDir, "libggml-vulkan.so").exists()

    /** 是否具备测评能力（测评二进制存在）。 */
    fun available(ctx: Context): Boolean =
        File(ctx.applicationInfo.nativeLibraryDir, BENCH_BIN).exists()

    /**
     * 跑完整比较并选出最优引擎。onProgress 回调给 UI 展示进度。
     * 每档只跑一小段（-p 256 -n 64 -r 2）；GPU 首次含着色器编译，给足超时。
     */
    suspend fun run(
        ctx: Context,
        modelPath: String,
        onProgress: (String) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Row>()

        onProgress("测评 CPU 引擎…")
        rows += benchOne(ctx, modelPath, ngl = 0, timeoutMs = 120_000)

        if (hasVulkan(ctx)) {
            onProgress("测评 GPU (Vulkan) 引擎…（首次含着色器编译，可能一两分钟）")
            rows += benchOne(ctx, modelPath, ngl = 99, timeoutMs = 240_000)
        }

        val best = pickBest(rows.filter { it.ok })
        val bestNgl = best?.ngl ?: 0   // 理论上 CPU 不会崩；全崩兜底 CPU
        Outcome(rows, bestNgl, buildSummary(rows, bestNgl))
    }

    /** 选择：主看生成速度(tg)；两者相近(≤5%)时看 prefill(pp)。 */
    private fun pickBest(ok: List<Row>): Row? {
        if (ok.isEmpty()) return null
        return ok.reduce { a, b ->
            when {
                b.gen > a.gen * 1.05 -> b
                a.gen > b.gen * 1.05 -> a
                b.prefill > a.prefill -> b
                else -> a
            }
        }
    }

    private fun benchOne(ctx: Context, modelPath: String, ngl: Int, timeoutMs: Long): Row {
        val label = if (ngl == 0) "CPU" else "GPU"
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, BENCH_BIN)
        if (!bin.exists()) return Row(label, ngl, false, 0.0, 0.0, "缺少测评程序")

        val cmd = listOf(
            bin.absolutePath, "-m", modelPath,
            "-ngl", ngl.toString(),
            "-p", "256", "-n", "64", "-r", "2",
            "-t", threadsForBench().toString()
        )
        return try {
            val pb = ProcessBuilder(cmd).apply {
                redirectErrorStream(true)
                directory(File(nativeDir))
                environment()["LD_LIBRARY_PATH"] = nativeDir
                environment()["HOME"] = ctx.filesDir.absolutePath
                environment()["TMPDIR"] = ctx.cacheDir.absolutePath
                // 与 LlamaServerService 完全一致的引擎环境，否则测出来的速度不代表实际服务：
                environment()["GGML_VK_DISABLE_BDA"] = "1"
                // CPU 档必须屏蔽 Vulkan 设备，否则统一内存设备上即便 -ngl 0 也会被塞到慢速 GPU 上，
                // 测出的“CPU”其实是 GPU 速度（~15 而非 dotprod 的 ~85）。
                if (ngl <= 0) environment()["GGML_VK_VISIBLE_DEVICES"] = ""
            }
            val proc = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                runCatching { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            }.apply { isDaemon = true; start() }

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                return Row(label, ngl, false, 0.0, 0.0, "超时")
            }
            reader.join(2000)
            val code = proc.exitValue()
            if (code != 0) {
                // 137/143=被杀、139=SIGSEGV → GPU 档基本就是驱动/算子不兼容
                val why = if (ngl > 0) "GPU 崩溃（不兼容）" else "退出码 $code"
                return Row(label, ngl, false, 0.0, 0.0, why)
            }
            val (pp, tg) = parse(out.toString())
            if (pp <= 0.0 && tg <= 0.0) Row(label, ngl, false, 0.0, 0.0, "无结果")
            else Row(label, ngl, true, pp, tg, "")
        } catch (e: Exception) {
            Row(label, ngl, false, 0.0, 0.0, e.message ?: "失败")
        }
    }

    /** 解析 llama-bench 的 markdown 表：抓 ppN / tgN 行的 t/s（“91.62 ± 1.03” 取 ± 前的数）。 */
    private fun parse(text: String): Pair<Double, Double> {
        var pp = 0.0; var tg = 0.0
        for (line in text.lineSequence()) {
            if (!line.trimStart().startsWith("|")) continue
            val cells = line.split("|").map { it.trim() }
            val testCell = cells.firstOrNull { it.startsWith("pp") || it.startsWith("tg") } ?: continue
            val tsCell = cells.lastOrNull { it.contains("±") } ?: cells.lastOrNull { it.isNotEmpty() } ?: continue
            val v = tsCell.substringBefore("±").trim().toDoubleOrNull() ?: continue
            if (testCell.startsWith("pp")) pp = v else tg = v
        }
        return pp to tg
    }

    private fun threadsForBench(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

    private fun buildSummary(rows: List<Row>, bestNgl: Int): String {
        val parts = rows.map { r ->
            if (r.ok) "%s %.0f/%.0f t/s".format(r.label, r.prefill, r.gen)
            else "%s·%s".format(r.label, r.note)
        }
        val pick = if (bestNgl > 0) "已选 GPU" else "已选 CPU"
        return parts.joinToString(" · ") + " → " + pick
    }
}
