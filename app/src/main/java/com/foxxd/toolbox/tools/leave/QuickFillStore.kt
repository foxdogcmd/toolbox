// QuickFillStore.kt — 请假表单的常用信息（兼容旧版快速填充存档）
package com.foxxd.toolbox.tools.leave

import android.content.Context
import org.json.JSONObject

object QuickFillStore {

    private const val PREFS = "quickfill"
    private const val KEY = "qjForm"

    /** 页面自动加载/不可修改/无法预填的字段：不在设置页展示，快速填充时跳过 */
    val BLACKLIST = setOf(
        "XH", "XM", "DWDM", "DWDM_DISPLAY", "ZYDM", "ZYDM_DISPLAY",
        "BJDM", "BJDM_DISPLAY", "XZNJ", "QJKSRQ", "QJJSRQ", "QJTS", "SQBH",
        "DZ_ZZZM", "DZ_ZZZM_YY", "ZMCL", // 证明材料是上传件,无法预填
    )

    fun save(context: Context, json: String) {
        // 校验是合法 JSON 对象再存，避免脏数据
        try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
    }

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)

    fun loadMap(context: Context): Map<String, String> {
        val json = load(context) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence()
                .map { it to obj.optString(it) }
                .filter { it.second.isNotEmpty() && it.second != "null" }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun remove(context: Context, field: String) {
        val json = load(context) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(field)
            save(context, obj.toString())
        } catch (e: Exception) {
        }
    }

    /** 设置页编辑后整体写回；空值字段直接丢弃（填充时本来就跳过空值） */
    fun saveMap(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            if (v.isNotEmpty()) obj.put(k, v)
        }
        save(context, obj.toString())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
