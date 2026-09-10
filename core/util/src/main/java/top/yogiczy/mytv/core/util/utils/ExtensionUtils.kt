package top.yogiczy.mytv.core.util.utils

import java.util.regex.Pattern

fun Long.humanizeMs(): String {
    return when (this) {
        in 0..<60_000 -> "${this / 1000}秒"
        in 60_000..<3_600_000 -> "${this / 60_000}分钟"
        in 3_600_000..<86_400_000 -> "${this / 3_600_000}小时"
        else -> "${this / 86_400_000}天"
    }
}

fun String.isIPv6(): Boolean {
    val urlPattern = Pattern.compile(
        "^((http|https)://)?(\\[[0-9a-fA-F:]+])(:[0-9]+)?(/.*)?$"
    )
    return urlPattern.matcher(this).matches()
}

/**
 * 比较版本号大小
 * @return 大于返回 1，小于返回 -1，相等返回 0
 */
fun String.compareVersion(version2: String): Int {
    fun parseVersion(version: String): Pair<List<Int>, String?> {
        val cleanVersion = version.removePrefix("v").removePrefix("V")
        val mainParts = cleanVersion.split("-", limit = 2)
        // 非数字段兜底为 0，避免异常版本号导致崩溃
        val versionNumbers = mainParts[0].split(".").map { it.toIntOrNull() ?: 0 }
        val preReleaseLabel = mainParts.getOrNull(1)
        return versionNumbers to preReleaseLabel
    }

    /**
     * 比较预发布标签，null（正式版）视为最大
     * 标签按「字母前缀 + 数字序号」比较，使 beta9 < beta10（字符串字典序会误判为 beta10 < beta9）
     */
    fun comparePreRelease(label1: String?, label2: String?): Int {
        if (label1 == null && label2 == null) return 0
        if (label1 == null) return 1
        if (label2 == null) return -1

        // 拆分：beta1 -> ("beta", 1)、beta -> ("beta", 0)、rc2 -> ("rc", 2)
        fun splitLabel(label: String): Pair<String, Int> {
            val prefix = label.takeWhile { !it.isDigit() }
            val number = label.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
            return prefix to number
        }

        val (prefix1, number1) = splitLabel(label1)
        val (prefix2, number2) = splitLabel(label2)

        val prefixResult = prefix1.compareTo(prefix2)
        if (prefixResult != 0) return prefixResult

        return number1.compareTo(number2)
    }

    val (v1, preRelease1) = parseVersion(this)
    val (v2, preRelease2) = parseVersion(version2)
    val maxLength = maxOf(v1.size, v2.size)

    for (i in 0 until maxLength) {
        val part1 = v1.getOrElse(i) { 0 }
        val part2 = v2.getOrElse(i) { 0 }
        if (part1 > part2) return 1
        if (part1 < part2) return -1
    }

    // 统一规范化为 -1/0/1，避免返回 compareTo 的原始差值
    return comparePreRelease(preRelease1, preRelease2).compareTo(0)
}

fun String.removeBom(): String {
    val bom = "\uFEFF"
    return if (this.startsWith(bom)) this.removePrefix(bom) else this
}
