package com.foxxd.toolbox

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.tools.Credentials
import com.foxxd.toolbox.tools.leave.QuickFillStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * 设置页：统一认证账号密码 + 请假快速填充数据管理。
 * 快速填充区按网页请假表单的顺序和分组展示；
 * 请假性质/请假类型/所去地点与网页一样是弹窗选择（选项与网页一致），其余文本项直接编辑。
 * 学号/姓名等页面自动加载项、请假天数、证明材料不纳入管理。
 */
class SettingsActivity : AppCompatActivity() {

    /** 文本字段：view id → emap 字段代码（与网页表单同序） */
    private val textFields by lazy {
        linkedMapOf(
            R.id.fJJLXR to "JJLXR",
            R.id.fLXDH to "LXDH",
            R.id.fDZ_LXRGX to "DZ_LXRGX",
            R.id.fDZ_SSL to "DZ_SSL",
            R.id.fDZ_FJH to "DZ_FJH",
            R.id.fDZ_LXDH to "DZ_LXDH",
            R.id.fQJSY to "QJSY",
            R.id.fXXDZ to "XXDZ",
            R.id.fDZ_BZ to "DZ_BZ",
        )
    }

    /** 弹窗选择字段：view id → (emap 字段代码, 选项)。选项与网页弹窗保持一致 */
    private val pickerFields by lazy {
        linkedMapOf(
            R.id.fQJXZ to ("QJXZ_DISPLAY" to listOf("因私请假", "因公请假")),
            R.id.fQJLX to ("QJLX_DISPLAY" to listOf(
                "公派", "教学活动", "就业升学", "伤病", "私事",
                "寒暑假专项任务请销假", "次日后连续白日假（从次日起可连续多次申请隔天白日假)",
            )),
        )
    }

    /** 开关字段：view id → emap 字段代码（"1" 开 / "0" 关） */
    private val switchFields by lazy {
        linkedMapOf(
            R.id.swSFLX to "SFLX",
            R.id.swDZ_SFLJ to "DZ_SFLJ",
            R.id.swDZ_SFYK to "DZ_SFYK",
            R.id.swDZ_SFGYJ to "DZ_SFGYJ",
        )
    }

    /** 弹窗选择字段对应的内部代码键：保存时丢弃，填充靠 UI 回放重新生成 */
    private val codeKeys = listOf("QJXZ", "QJLX", "LXQX")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val xhInput = findViewById<TextInputEditText>(R.id.xhInput)
        val pwdInput = findViewById<TextInputEditText>(R.id.pwdInput)
        xhInput.setText(Credentials.xh(this))
        Credentials.password(this)?.let { pwdInput.setText(it) }

        findViewById<MaterialButton>(R.id.saveBtn).setOnClickListener {
            val xh = xhInput.text.toString().trim()
            val pwd = pwdInput.text.toString()
            if (xh.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "学号和密码不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Credentials.save(this, xh, pwd, remember = true)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        // 虚拟校园卡 openid：支持粘贴完整链接，自动提取 openid 参数
        val openidInput = findViewById<TextInputEditText>(R.id.yktOpenidInput)
        openidInput.setText(getSharedPreferences("ykt", MODE_PRIVATE).getString("openid", ""))
        findViewById<MaterialButton>(R.id.saveYktBtn).setOnClickListener {
            val raw = openidInput.text.toString().trim()
            val openid = Regex("[?&]openid=([0-9A-Fa-f]{16,})").find(raw)?.groupValues?.get(1)
                ?: raw.takeIf { it.matches(Regex("[0-9A-Fa-f]{16,}")) } ?: ""
            if (openid.isEmpty()) {
                Toast.makeText(this, "没识别出 openid，请粘贴完整链接或 64 位 openid", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            getSharedPreferences("ykt", MODE_PRIVATE).edit().putString("openid", openid).apply()
            Toast.makeText(this, "已保存 openid", Toast.LENGTH_SHORT).show()
        }

        // 弹窗选择字段
        for ((id, pair) in pickerFields) {
            val (key, options) = pair
            findViewById<TextView>(id).setOnClickListener { view ->
                showPicker(view as TextView, options)
            }
        }
        // 所去地点：完整省市区级联选择（数据 dump 自网页请假表单的级联组件，
        // 与网页选项完全一致；叶子级名称即网页存储的全路径格式）
        findViewById<TextView>(R.id.fLXQX).setOnClickListener { view ->
            showRegionPicker(view as TextView)
        }

        findViewById<MaterialButton>(R.id.saveFillBtn).setOnClickListener { saveFill() }
        findViewById<MaterialButton>(R.id.clearFillBtn).setOnClickListener {
            QuickFillStore.clear(this)
            reloadFill()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPicker(view: TextView, options: List<String>) {
        val current = view.text.toString()
        val checked = options.indexOf(current)
        AlertDialog.Builder(this)
            .setSingleChoiceItems(options.toTypedArray(), checked, null)
            .setPositiveButton("确定") { dlg, _ ->
                val pos = (dlg as AlertDialog).listView.checkedItemPosition
                view.text = if (pos >= 0) options[pos] else ""
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPathInput(view: TextView) {
        val input = EditText(this).apply {
            setText(view.text)
            hint = "如:北京市/市辖区/大兴区"
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("所去地点")
            .setView(input)
            .setPositiveButton("确定") { _, _ -> view.text = input.text.toString().trim() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 地区树（assets/lxqx_tree.json，dump 自网页级联组件；n=本级名, c=子级） */
    private val regionTree by lazy {
        org.json.JSONArray(assets.open("lxqx_tree.json").bufferedReader().readText())
    }

    private fun showRegionPicker(view: TextView) {
        val nodes = (0 until regionTree.length()).map { regionTree.getJSONObject(it) }
        pickRegionLevel(nodes, "", view, firstLevel = true)
    }

    /** 逐级弹单选列表；到叶子为止。叶子名逐级拼成网页的全路径格式（省/市/区） */
    private fun pickRegionLevel(
        nodes: List<org.json.JSONObject>,
        prefix: String,
        view: TextView,
        firstLevel: Boolean = false,
    ) {
        val labels = nodes.map { it.getString("n") }.toMutableList()
        if (firstLevel) labels.add("手动输入…")
        AlertDialog.Builder(this)
            .setTitle(if (prefix.isEmpty()) "所去地点" else prefix)
            .setItems(labels.toTypedArray()) { _, which ->
                if (firstLevel && which == labels.lastIndex) {
                    showPathInput(view)
                    return@setItems
                }
                val node = nodes[which]
                val path = if (prefix.isEmpty()) node.getString("n")
                           else "$prefix/${node.getString("n")}"
                val children = node.optJSONArray("c")
                if (children == null || children.length() == 0) {
                    view.text = path
                } else {
                    pickRegionLevel(
                        (0 until children.length()).map { children.getJSONObject(it) },
                        path, view,
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        reloadFill()
    }

    private fun reloadFill() {
        val data = QuickFillStore.loadMap(this)
        for ((id, key) in textFields) {
            findViewById<EditText>(id).setText(data[key].orEmpty())
        }
        for ((id, pair) in pickerFields) {
            findViewById<TextView>(id).text = data[pair.first].orEmpty()
        }
        findViewById<TextView>(R.id.fLXQX).text = data["LXQX_DISPLAY"].orEmpty()
        for ((id, key) in switchFields) {
            findViewById<SwitchMaterial>(id).isChecked = data[key] == "1"
        }
    }

    private fun saveFill() {
        val out = QuickFillStore.loadMap(this).toMutableMap()
        for ((id, key) in textFields) {
            val value = findViewById<EditText>(id).text.toString().trim()
            if (value.isEmpty()) out.remove(key) else out[key] = value
        }
        for ((id, pair) in pickerFields) {
            val value = findViewById<TextView>(id).text.toString().trim()
            if (value.isEmpty()) out.remove(pair.first) else out[pair.first] = value
        }
        val lxqx = findViewById<TextView>(R.id.fLXQX).text.toString().trim()
        if (lxqx.isEmpty()) out.remove("LXQX_DISPLAY") else out["LXQX_DISPLAY"] = lxqx
        // 内部代码一律丢弃：填充时通过 UI 回放重新选中,避免陈旧代码
        for (key in codeKeys) out.remove(key)
        // 历史遗留的自动提取键（学号/姓名等）也一并清掉,这些归页面自己加载
        for (key in QuickFillStore.BLACKLIST) out.remove(key)
        for ((id, key) in switchFields) {
            out[key] = if (findViewById<SwitchMaterial>(id).isChecked) "1" else "0"
        }
        QuickFillStore.saveMap(this, out)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }
}
