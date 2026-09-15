package top.yogiczy.mytv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.core.util.utils.compareVersion

/**
 * 版本比较回归用例：模拟真实调用「远端.compareVersion(当前) > 0 即可升级」
 * 重点覆盖 beta 序号进位（beta9 -> beta10）、beta 与正式版互切等边界场景
 */
class CompareVersionTest {

    private fun assertUpgradable(remote: String, current: String) =
        assertEquals("期望 $remote > $current", 1, remote.compareVersion(current))

    private fun assertNotUpgradable(remote: String, current: String) =
        assertEquals("期望 $remote <= $current", true, remote.compareVersion(current) <= 0)

    @Test
    fun basicVersion() {
        assertUpgradable("2.6.0", "2.5.9")
        assertNotUpgradable("2.5.9", "2.6.0")
        assertEquals(0, "2.6.0".compareVersion("2.6.0"))
        // 位数不同：2.6 应等于 2.6.0
        assertEquals(0, "2.6".compareVersion("2.6.0"))
        assertUpgradable("2.6.0.1", "2.6.0")
    }

    @Test
    fun betaToBeta() {
        assertUpgradable("2.6.0-beta1", "2.6.0-beta")
        assertUpgradable("2.6.0-beta2", "2.6.0-beta1")
        assertUpgradable("2.6.0-beta", "2.5.9")
    }

    /** 核心修复点：beta9 -> beta10 必须可升级 */
    @Test
    fun betaDoubleDigit() {
        assertUpgradable("2.6.0-beta10", "2.6.0-beta9")
        assertUpgradable("2.6.0-beta11", "2.6.0-beta10")
        assertUpgradable("2.6.0-beta100", "2.6.0-beta99")
        assertUpgradable("2.6.0-beta10", "2.6.0-beta2")
        assertNotUpgradable("2.6.0-beta9", "2.6.0-beta10")
    }

    @Test
    fun betaToStable() {
        // beta -> 正式版：可升级
        assertUpgradable("2.6.0", "2.6.0-beta1")
        assertUpgradable("2.6.0", "2.6.0-beta10")
    }

    @Test
    fun stableToBeta() {
        // 正式版 -> 同号 beta：不可升级（不降级）
        assertNotUpgradable("2.6.0-beta1", "2.6.0")
        // 正式版 -> 更高版本号 beta：可升级
        assertUpgradable("2.6.1-beta1", "2.6.0")
        assertUpgradable("2.6.1-beta", "2.6.0-beta1")
    }

    @Test
    fun preReleasePrefixOrder() {
        // rc > beta（字典序，符合常规语义）
        assertUpgradable("2.6.0-rc1", "2.6.0-beta1")
        assertNotUpgradable("2.6.0-beta1", "2.6.0-rc1")
        // alpha < beta
        assertUpgradable("2.6.0-beta1", "2.6.0-alpha1")
    }

    @Test
    fun versionPrefixAndFallback() {
        // 带 v 前缀
        assertEquals(0, "v2.6.0".compareVersion("2.6.0"))
        assertUpgradable("V2.6.1", "v2.6.0")
        // 非数字段兜底，不崩溃
        assertEquals(0, "2.6.x".compareVersion("2.6.0"))
    }
}
