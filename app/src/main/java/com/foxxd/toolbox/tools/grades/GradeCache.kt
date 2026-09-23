// GradeCache.kt — 成绩本地缓存（filesDir 下按学号存 JSON）
package com.foxxd.toolbox.tools.grades

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GradeCache(filesDir: File, xh: String) {

    data class Entry(val courses: List<Course>, val updatedAt: Long)

    private val file = File(filesDir, "grades_cache_$xh.json")

    fun load(): Entry? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("courses")
            val courses = mutableListOf<Course>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = Course(
                    id = o.optString("id"),
                    xnxq = o.optString("xnxq"),
                    kcmc = o.optString("kcmc"),
                    xf = o.optString("xf"),
                    zhcj = o.optString("zhcj"),
                    jd = o.optString("jd"),
                    sfbk = o.optString("sfbk"),
                    bkbj = o.optString("bkbj"),
                )
                val subs = o.optJSONArray("subs")
                if (subs != null) {
                    for (j in 0 until subs.length()) {
                        val s = subs.getJSONObject(j)
                        c.subs.add(SubItem(s.optString("name"), s.optString("zb"), s.optString("score")))
                    }
                }
                courses.add(c)
            }
            Entry(courses, root.optLong("updatedAt"))
        } catch (e: Exception) {
            null
        }
    }

    fun save(courses: List<Course>) {
        val arr = JSONArray()
        for (c in courses) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("xnxq", c.xnxq)
            o.put("kcmc", c.kcmc)
            o.put("xf", c.xf)
            o.put("zhcj", c.zhcj)
            o.put("jd", c.jd)
            o.put("sfbk", c.sfbk)
            o.put("bkbj", c.bkbj)
            val subs = JSONArray()
            for (s in c.subs) {
                subs.put(JSONObject().put("name", s.name).put("zb", s.zb).put("score", s.score))
            }
            o.put("subs", subs)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("updatedAt", System.currentTimeMillis())
        root.put("courses", arr)
        file.writeText(root.toString(), Charsets.UTF_8)
    }
}
