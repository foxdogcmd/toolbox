// HupingActivity.kt — 综合测评「班级互评」打分（只保存，绝不提交）
//
// 对应学工系统 zhcptybbapp 的班级互评页（modules/evaluationBjhpController）：
//   getPageInitParams → 当前批次 PCDM / 学年学期 / 窗口（每次运行实时读，学期换了自动跟随）
//   cpsq/cxddfxsxx    → 全班学生（XSID/姓名/SFCPTAG）
//   getBjhbZb         → 互评评分项（ZBLB=9 且 SFSY=1 的是打分项，FS 是已存的分）
//   saveHpScore       → 保存草稿（页面上的「保存」按钮）
//
// 三个刻意的设计：
//   1. 只调 saveHpScore（保存）。提交是 ekdz/submithp.do，本工具不实现 ——
//      提交必须由用户自己去学工系统确认，避免误提交。
//   2. 「已评价」严格按页面口径判断：页面 mustache 写的是 {{#SFCPTAG}}，
//      即 SFCPTAG 为 null 或空串就是「未测评」。注意 org.json 的
//      optString() 对 JSON null 会返回字符串 "null"，直接 isNotEmpty() 会全变「已评价」。
//   3. 每行可点开逐项打分，也支持按统一分数批量保存。
package com.foxxd.toolbox.tools.huping

import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.grades.GradeClient
import com.foxxd.toolbox.tools.grades.HpInfo
import com.foxxd.toolbox.tools.grades.HpItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

class HupingActivity : AppCompatActivity() {

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoToggle: TextView
    private lateinit var infoHeader: LinearLayout
    private lateinit var notice: TextView
    private lateinit var scoreInput: EditText
    private lateinit var runBtn: MaterialButton
    private lateinit var onlyUnscored: CheckBox
    private lateinit var recycler: RecyclerView

    private data class StuRow(
        val name: String, val xh: String, val xsid: String,
        var scored: Boolean, var state: String,
    )

    private var pcdm = ""
    private var items: List<HpItem> = emptyList()
    private var hp: HpInfo? = null
    private var submitted = false
    private var infoExpanded = false
    private val rows = mutableListOf<StuRow>()
    private val adapter = StuAdapter(rows) { openStudent(it) }

    @Volatile
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_huping)

        status = findViewById(R.id.status)
        info = findViewById(R.id.info)
        infoTitle = findViewById(R.id.infoTitle)
        infoToggle = findViewById(R.id.infoToggle)
        infoHeader = findViewById(R.id.infoHeader)
        notice = findViewById(R.id.notice)
        scoreInput = findViewById(R.id.scoreInput)
        runBtn = findViewById(R.id.runBtn)
        onlyUnscored = findViewById(R.id.onlyUnscored)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        infoHeader.setOnClickListener {
            infoExpanded = !infoExpanded
            info.visibility = if (infoExpanded) View.VISIBLE else View.GONE
            infoToggle.text = if (infoExpanded) "收起" else "展开"
        }

        runBtn.isEnabled = false
        runBtn.setOnClickListener { confirmBatchSave() }

        auth = AuthUi(this) { _, _ -> load() }
        if (auth.credentials(this) != null) {
            auth.hide()
            load()
        } else {
            status.text = "请登录后使用（学工系统综合测评）"
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    /** 登录 → 读当前批次 / 提交状态 → 拉学生名单和评分项 */
    private fun load() {
        if (busy) return
        val cred = auth.credentials(this)
        if (cred == null) {
            auth.show()
            status.text = "请先登录"
            return
        }
        val (xh, pwd) = cred
        busy = true
        runBtn.isEnabled = false
        setStatus("登录并读取测评信息…")
        Thread {
            try {
                val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.LEAVE_PREFIX)
                client.loginXsfw(xh, pwd, APP)
                val current = client.hpInfo()
                if (current.pcdm.isEmpty()) throw RuntimeException("未获取到测评批次（可能本学期未开放）")
                val (shzt, sftj) = client.hpSubmitStatus(current.pcdm)
                val students = client.hpStudents(current.pcdm)
                // 评分项存在「学生维度」差异（自己被排除、已评过的人可能返回空），
                // 所以逐个试几个同学，取第一份非空的作为本批次标准。
                var scoreItems: List<HpItem> = emptyList()
                for (s in students.take(8)) {
                    val xsid = s.optString("XSID").ifEmpty { s.optString("XH") }
                    if (xsid.isEmpty()) continue
                    scoreItems = try {
                        client.hpScoreItems(xsid, current.pcdm)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (scoreItems.isNotEmpty()) break
                }
                runOnUiThread {
                    auth.hide()
                    pcdm = current.pcdm
                    hp = current
                    items = scoreItems
                    submitted = shzt == "1" || sftj == "1"
                    rows.clear()
                    for (s in students) {
                        val xsid = s.optString("XSID").ifEmpty { s.optString("XH") }
                        val scored = hpScored(s)
                        rows.add(
                            StuRow(
                                name = s.optString("XM"),
                                xh = s.optString("XH"),
                                xsid = xsid,
                                scored = scored,
                                state = if (scored) "已评价" else "未评价",
                            )
                        )
                    }
                    adapter.notifyDataSetChanged()
                    renderInfo(current, shzt, sftj)
                    val unscored = rows.count { !it.scored }
                    setStatus("共 ${rows.size} 人 · 未评价 $unscored 人 · 评分项 ${items.size} 项")
                    runBtn.isEnabled = items.isNotEmpty()
                    notice.text = if (submitted) {
                        "本轮班级互评在学工系统里已提交：这里的改动只是保存草稿，" +
                            "要生效须去学工系统撤销/重新提交。"
                    } else {
                        "本工具只「保存」草稿，不会替你提交；评分完成后请到学工系统提交。"
                    }
                }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
                runOnUiThread { auth.show() }
            } finally {
                busy = false
            }
        }.start()
    }

    /** 折叠区内容：批次 / 学期 / 窗口 / 提交状态 / 评分项 */
    private fun renderInfo(h: HpInfo, shzt: String, sftj: String) {
        infoTitle.text = "测评信息 · ${h.termText}"
        info.text = buildString {
            append("${h.studentName}（${h.xh}）")
            if (h.bjdm.isNotEmpty()) append(" · ${h.bjdm}")
            append("\n测评学期：${h.termText}")
            if (h.zbkmc.isNotEmpty()) append("\n指标库：${h.zbkmc}")
            if (h.dxmc.isNotEmpty()) append("\n测评对象：${h.dxmc}")
            append("\n测评窗口：${h.window}")
            append("\n批次号：${h.pcdm}")
            append("\n班级互评：")
            append(
                when {
                    submitted -> "已提交（SHZT=$shzt SFTJ=$sftj）· 改动需去学工系统"
                    else -> "未提交（SHZT=$shzt SFTJ=$sftj）"
                }
            )
            if (!h.allowBjhp) append("\n注意：本批次未开放班级互评")
            append("\n评分项 ${items.size} 项：")
            append(
                items.joinToString("、") { "${it.name}(${it.max}分)" }
                    .ifEmpty { "无（本批次未配置班级互评打分项）" }
            )
        }
    }

    // ---------- 批量保存 ----------

    private fun confirmBatchSave() {
        if (busy || items.isEmpty()) return
        val score = scoreInput.text.toString().trim()
        val value = score.toDoubleOrNull()
        if (value == null) {
            setStatus("[-] 分数格式不对")
            return
        }
        val max = maxScore()
        if (value < 0 || value > max) {
            setStatus("[-] 分数需在 0 ~ $max 之间")
            return
        }
        val targets = rows.filter { if (onlyUnscored.isChecked) !it.scored else true }
        if (targets.isEmpty()) {
            setStatus("没有需要保存的同学")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("批量保存班级互评")
            .setMessage(
                "将为 ${targets.size} 位同学按下列分数保存草稿：\n" +
                    "评分项：${items.joinToString("、") { it.name }}\n" +
                    "每项分数：$score 分\n\n" +
                    "只是「保存」，不会提交。" +
                    (if (submitted) "\n\n注意：本轮已提交，保存后需去学工系统撤销/重新提交才生效。"
                    else "\n保存后请到学工系统提交。")
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> saveBatch(targets, score) }
            .show()
    }

    private fun saveBatch(targets: List<StuRow>, score: String) {
        if (busy) return
        val cred = auth.credentials(this) ?: run {
            setStatus("请先登录")
            return
        }
        busy = true
        runBtn.isEnabled = false
        val scores = items.map { it.zbdm to score }
        Thread {
            val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.LEAVE_PREFIX)
            try {
                client.loginXsfw(cred.first, cred.second, APP)
            } catch (e: Exception) {
                setStatus("[-] 登录失败：${e.message}")
                busy = false
                runOnUiThread { runBtn.isEnabled = true }
                return@Thread
            }
            var ok = 0
            targets.forEachIndexed { i, row ->
                val err = try {
                    client.saveHpScore(pcdm, row.xsid, scores)
                } catch (e: Exception) {
                    e.message ?: "请求失败"
                }
                if (err.isEmpty()) {
                    ok++
                    row.scored = true
                    row.state = "已保存，待提交"
                } else {
                    row.state = "失败：$err"
                }
                runOnUiThread {
                    adapter.notifyItemChanged(rows.indexOf(row))
                    setStatus("进度 ${i + 1}/${targets.size} · 成功 $ok")
                }
            }
            runOnUiThread {
                setStatus("已保存 $ok/${targets.size}（未提交）· $SUBMIT_REMINDER")
                runBtn.isEnabled = true
            }
            busy = false
        }.start()
    }

    // ---------- 单人逐项打分 ----------

    private fun openStudent(row: StuRow) {
        if (busy) return
        if (items.isEmpty()) {
            setStatus("[-] 本批次没有可打分的互评项")
            return
        }
        busy = true
        setStatus("读取 ${row.name} 的评分项…")
        Thread {
            val cred = auth.credentials(this) ?: return@Thread
            val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.LEAVE_PREFIX)
            val fresh = try {
                client.loginXsfw(cred.first, cred.second, APP)
                client.hpScoreItems(row.xsid, pcdm)
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
                busy = false
                return@Thread
            }
            val list = fresh.ifEmpty { items }
            runOnUiThread {
                busy = false
                showStudentDialog(row, list)
            }
        }.start()
    }

    private fun showStudentDialog(row: StuRow, list: List<HpItem>) {
        val fallback = scoreInput.text.toString().trim().ifEmpty { list.first().max }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_hp_student, null)
        view.findViewById<TextView>(R.id.dlgHpTitle).text = row.name
        view.findViewById<TextView>(R.id.dlgHpSub).text =
            "${row.xh} · ${if (row.scored) "已评价（可覆盖）" else "未评价"}"
        view.findViewById<TextView>(R.id.dlgHpHint).text =
            "点「保存」只写草稿，不会提交。提交请到学工系统 → 综合测评 → 测评申请 → 班级互评。"

        val container = view.findViewById<LinearLayout>(R.id.dlgHpRows)
        val inputs = mutableListOf<Pair<HpItem, EditText>>()
        for (item in list) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = TextView(this).apply {
                text = item.name
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val input = EditText(this).apply {
                hint = "${item.max} 分"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(item.fs.ifEmpty { fallback })
                layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            line.addView(label)
            line.addView(input)
            container.addView(line)
            inputs.add(item to input)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val scores = mutableListOf<Pair<String, String>>()
                for ((item, input) in inputs) {
                    val raw = input.text.toString().trim()
                    val v = raw.toDoubleOrNull()
                    val max = item.max.toDoubleOrNull() ?: 100.0
                    val min = item.min.toDoubleOrNull() ?: 0.0
                    if (v == null || v < min || v > max) {
                        input.error = "请输入 $min ~ $max"
                        setStatus("[-] 分数需在 $min ~ $max 之间")
                        return@setOnClickListener
                    }
                    scores.add(item.zbdm to raw)
                }
                saveOne(dialog, row, scores)
            }
        }
        dialog.show()
    }

    private fun saveOne(dialog: AlertDialog, row: StuRow, scores: List<Pair<String, String>>) {
        if (busy) return
        val cred = auth.credentials(this) ?: run {
            setStatus("请先登录")
            return
        }
        busy = true
        setStatus("保存 ${row.name} 的评分…")
        Thread {
            val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.LEAVE_PREFIX)
            val err = try {
                client.loginXsfw(cred.first, cred.second, APP)
                client.saveHpScore(pcdm, row.xsid, scores)
            } catch (e: Exception) {
                e.message ?: "请求失败"
            }
            runOnUiThread {
                busy = false
                if (err.isEmpty()) {
                    row.scored = true
                    row.state = "已保存，待提交"
                    adapter.notifyItemChanged(rows.indexOf(row))
                    dialog.dismiss()
                    setStatus("${row.name} 已保存（未提交）· $SUBMIT_REMINDER")
                } else {
                    setStatus("[-] ${row.name} 保存失败：$err")
                }
            }
        }.start()
    }

    // ---------- 工具 ----------

    /**
     * 是否已评价。严格照页面的 mustache：{{#SFCPTAG}} 为真才显示「已测评」，
     * 即 SFCPTAG 是 null 或空串就是未评价。
     * 坑：org.json 的 optString() 对 JSON null 返回字符串 "null"，不能直接判空。
     */
    private fun hpScored(o: JSONObject): Boolean {
        if (o.isNull("SFCPTAG")) return false
        val tag = o.optString("SFCPTAG", "").trim()
        return tag.isNotEmpty() && !tag.equals("null", ignoreCase = true)
    }

    private fun maxScore(): Double =
        items.mapNotNull { it.max.toDoubleOrNull() }.maxOrNull() ?: 100.0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class StuVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.hpName)
        val sub: TextView = view.findViewById(R.id.hpSub)
        val state: TextView = view.findViewById(R.id.hpState)
    }

    private class StuAdapter(
        private val data: List<StuRow>,
        private val onClick: (StuRow) -> Unit,
    ) : RecyclerView.Adapter<StuVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = StuVH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hp_student, parent, false)
        )

        override fun onBindViewHolder(holder: StuVH, position: Int) {
            val row = data[position]
            holder.name.text = row.name
            holder.sub.text = row.xh
            holder.state.text = row.state
            holder.itemView.setOnClickListener { onClick(row) }
        }

        override fun getItemCount() = data.size
    }

    companion object {
        /** 综合测评 app（学工系统 xsfw） */
        private const val APP = "zhcptybbapp"
        private const val SUBMIT_REMINDER =
            "请到学工系统 → 综合测评 → 测评申请 → 班级互评 点「提交」才算提交完成。"
    }
}
