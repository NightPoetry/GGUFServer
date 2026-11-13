package com.ggufserver.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型市场多尺寸断点钉子（设计文档04 增补2）：
 * <600dp = compact（横向顶栏）；≥600dp = medium（左侧纵栏）。
 * 典型真机宽度：375/390（竖屏手机）→ compact；494（手机平板分界带）→ compact；
 * 600~839（小平板/横屏中板）与 ≥840（大板/桌面模式）→ medium。
 */
class HubLayoutTest {

    @Test
    fun `窄屏与竖屏手机走compact`() {
        assertEquals(HubLayout.COMPACT, hubLayout(360f))
        assertEquals(HubLayout.COMPACT, hubLayout(375f))
        assertEquals(HubLayout.COMPACT, hubLayout(390f))
        assertEquals(HubLayout.COMPACT, hubLayout(494f))
    }

    @Test
    fun `600dp是分界线_含端点走medium`() {
        assertEquals(HubLayout.MEDIUM, hubLayout(600f))
        assertEquals(HubLayout.COMPACT, hubLayout(599.9f))
    }

    @Test
    fun `平板横置与桌面模式走medium`() {
        assertEquals(HubLayout.MEDIUM, hubLayout(768f))
        assertEquals(HubLayout.MEDIUM, hubLayout(840f))
        assertEquals(HubLayout.MEDIUM, hubLayout(1440f))
    }
}
