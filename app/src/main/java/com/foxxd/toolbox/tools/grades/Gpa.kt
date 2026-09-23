// Gpa.kt — 平均学分绩点计算
package com.foxxd.toolbox.tools.grades

import java.util.Locale

/** 绩点统计结果：gpa 为 null 表示没有可用于计算的课程。 */
data class GpaResult(val gpa: Double?, val credits: Double, val count: Int) {
    val text: String
        get() = if (gpa == null) "—" else String.format(Locale.US, "%.2f", gpa)
}

/**
 * 加权平均学分绩点 = Σ(课程绩点 × 学分) / Σ学分。
 * 绩点或学分无法解析为数字的课程（等级制、空值等）不计入。
 */
fun weightedGpa(courses: List<Course>): GpaResult {
    var sum = 0.0
    var credits = 0.0
    var count = 0
    for (c in courses) {
        val jd = c.jd.trim().toDoubleOrNull() ?: continue
        val xf = c.xf.trim().toDoubleOrNull() ?: continue
        sum += jd * xf
        credits += xf
        count++
    }
    return GpaResult(if (credits == 0.0) null else sum / credits, credits, count)
}

/** 是否为补考记录 */
fun isRetake(c: Course) = c.bkbj == "补考" || c.sfbk == "1"
