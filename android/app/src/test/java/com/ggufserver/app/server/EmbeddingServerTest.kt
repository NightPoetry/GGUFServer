package com.ggufserver.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向量模型（嵌入服务）命令行组装的纯逻辑单测。
 *
 * 为什么写它：真机上「嵌入服务起不来」要付出装包+看日志的代价，而命令行形态是纯函数——
 * 就该在 JVM 上钉死：--embeddings 必须在（少了它 /v1/embeddings 直接 404）、绑定 0.0.0.0
 * （嵌入服务存在的意义就是给局域网用，绑错 127.0.0.1 等于白做）、API Key 只在有值时透传。
 */
class EmbeddingServerTest {

    private fun cmd(apiKey: String = "") = EmbeddingServer.buildCommand(
        binPath = "/data/app/bin/libllamaserver.so",
        modelPath = "/models/qwen3-embedding-0.6b-q4_k_m.gguf",
        port = EmbeddingServer.EMB_PORT,
        alias = "qwen3-embedding-0.6b-q4_k_m",
        apiKey = apiKey,
        threads = 4,
        contextSize = 2048,
    )

    @Test
    fun `嵌入模式与绑定形态钉死`() {
        val c = cmd()
        assertTrue("必须有 --embeddings，否则 /v1/embeddings 不可用", c.contains("--embeddings"))
        assertTrue("嵌入服务必须绑 0.0.0.0 对局域网开放", c.contains("0.0.0.0"))
        assertEquals("0.0.0.0", c[c.indexOf("--host") + 1])
        assertEquals("8101", c[c.indexOf("--port") + 1])
        assertEquals("/models/qwen3-embedding-0.6b-q4_k_m.gguf", c[c.indexOf("-m") + 1])
        assertEquals("2048", c[c.indexOf("-c") + 1])
        assertEquals("4", c[c.indexOf("-t") + 1])
        assertTrue("别名必须透传（调用名展示与 API model 字段一致）", c.contains("--alias"))
    }

    @Test
    fun `api_key只在有值时透传`() {
        assertFalse("留空不鉴权：不能出现 --api-key 空参对", cmd(apiKey = "").contains("--api-key"))
        val withKey = cmd(apiKey = "sk-test")
        assertTrue(withKey.contains("--api-key"))
        assertEquals("sk-test", withKey[withKey.indexOf("--api-key") + 1])
    }

    @Test
    fun `对话模型专属参数一律不出现`() {
        val c = cmd()
        listOf("--mmproj", "--reasoning", "--temp", "--top-p", "--jinja", "-ngl").forEach { flag ->
            assertFalse("嵌入实例不该带对话模型参数 $flag", c.contains(flag))
        }
    }
}
