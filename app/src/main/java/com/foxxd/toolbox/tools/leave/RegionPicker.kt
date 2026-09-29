package com.foxxd.toolbox.tools.leave

import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/** 与学校级联地区表同名的本机省、市、区选择器；提交时再向学校码表解析代码。 */
object RegionPicker {
    fun show(context: Context, onSelected: (String) -> Unit) {
        val tree = JSONArray(context.assets.open("lxqx_tree.json").bufferedReader().use { it.readText() })
        pick(context, tree, "", onSelected)
    }

    private fun pick(context: Context, nodes: JSONArray, prefix: String, selected: (String) -> Unit) {
        val items = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
        AlertDialog.Builder(context)
            .setTitle(prefix.ifEmpty { "选择省份" })
            .setItems(items.map { it.optString("n") }.toTypedArray()) { _, index ->
                val node: JSONObject = items[index]
                val name = node.optString("n")
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val children = node.optJSONArray("c")
                if (children == null || children.length() == 0) selected(path)
                else pick(context, children, path, selected)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
