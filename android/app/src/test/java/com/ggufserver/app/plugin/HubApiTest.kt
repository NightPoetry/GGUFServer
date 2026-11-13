package com.ggufserver.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hf-mirror（HF 同构镜像）端点拼装与解析的钉子（模型市场，设计文档04 §2）。
 * 特别钉住两条设计原话的落地：
 *  - 热门列表必须 `filter=gguf&sort=downloads&direction=-1`（下载量降序，GGUF 库内）
 *  - 搜索 URL **不带排序参数**（「直接搜索的话就不过滤了，搜出什么来就是什么」）
 */
class HubApiTest {

    @Test
    fun `热门列表_下载量降序且限定gguf`() {
        val u = HubApi.listPopularUrl(30)
        assertTrue(u.startsWith("https://hf-mirror.com/api/models?"))
        assertTrue(u.contains("filter=gguf"))
        assertTrue(u.contains("sort=downloads"))
        assertTrue(u.contains("direction=-1"))
        assertTrue(u.contains("limit=30"))
    }

    @Test
    fun `搜索URL不带排序不过滤`() {
        val u = HubApi.searchUrl("qwen3 coder", 30)
        assertTrue(u.contains("search="))
        assertFalse(u.contains("sort="))     // 相关性排序交给站点
        assertFalse(u.contains("filter="))   // 不限 GGUF——搜出什么就是什么
        assertFalse(u.contains("+"))         // 空格编码成 %20 而不是 +
        assertTrue(u.contains("%20"))
    }

    @Test
    fun `limit钳制到合法区间`() {
        assertTrue(HubApi.listPopularUrl(0).contains("limit=1"))
        assertTrue(HubApi.listPopularUrl(999).contains("limit=100"))
    }

    @Test
    fun `tree与download的URL编码`() {
        // "/" 是路径分隔符必须原样保留（%2F 会被 hf-mirror 404，模拟器真机验证抓出的 bug）
        assertTrue(HubApi.treeUrl("unsloth/Qwen3-4B").endsWith("/api/models/unsloth/Qwen3-4B/tree/main"))
        val d = HubApi.downloadUrl("org/Repo.Name", "sub dir/Model-Q4_K_M.gguf")
        assertTrue(d.startsWith("https://hf-mirror.com/org/Repo.Name/resolve/main/"))
        assertTrue(d.contains("sub%20dir/"))
        assertTrue(d.endsWith("Model-Q4_K_M.gguf"))
    }

    @Test
    fun `解析列表响应_容忍脏条目`() {
        val body = """
            [
              {"id":"a/b","downloads":123,"likes":4},
              {"id":"","downloads":9},
              {"downloads":5},
              {"id":"c/d","downloads":10,"private":true}
            ]
        """.trimIndent()
        val models = HubApi.parseModels(body)
        assertEquals(listOf("a/b", "c/d"), models.map { it.id })
        assertEquals(123L, models[0].downloads)
        assertEquals(0L, models[1].likes) // 缺字段容错
    }

    @Test
    fun `解析目录树_只留gguf文件且排除mmproj`() {
        val body = """
            [
              {"type":"directory","path":"assets","size":0},
              {"type":"file","path":"README.md","size":100},
              {"type":"file","path":"mmproj-model-f16.gguf","size":200},
              {"type":"file","path":"Model-Q4_K_M.gguf","size":1000,"lfs":{"size":1000}},
              {"type":"file","path":"Model-Q8_0.gguf","size":55}
            ]
        """.trimIndent()
        val files = HubApi.parseTree(body)
        assertEquals(listOf("Model-Q4_K_M.gguf", "Model-Q8_0.gguf"), files.map { it.path })
        assertEquals(1000L, files[0].size)
        assertEquals(55L, files[1].size)
    }

    @Test
    fun `下载源域名钉死在镜像站`() {
        // HubApi.BASE 是唯一入口：页面拼的下载 URL 过桥时还要过 Kotlin 侧前缀校验（ModelHubDownloader.start）
        assertEquals("https://hf-mirror.com", HubApi.BASE)
        assertTrue(HubApi.downloadUrl("a/b", "x.gguf").startsWith(HubApi.BASE + "/a/b"))
    }
}
