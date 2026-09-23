package com.foxxd.toolbox.tools.grades

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.Credentials
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GradesActivity : AppCompatActivity() {

    private lateinit var refreshBtn: MaterialButton
    private lateinit var status: TextView
    private lateinit var gpaSummary: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView

    private var courses: List<Course> = emptyList()
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grades)

        refreshBtn = findViewById(R.id.refreshBtn)
        status = findViewById(R.id.status)
        gpaSummary = findViewById(R.id.gpaSummary)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        // 打开先展示缓存（有的话秒出）
        val xh = Credentials.xh(this)
        if (xh.isNotEmpty()) {
            GradeCache(filesDir, xh).load()?.let { entry ->
                showCourses(entry.courses, "缓存 · ${fmtTime(entry.updatedAt)}")
            }
        }

        refreshBtn.setOnClickListener { refresh() }
        swipeRefresh.setOnRefreshListener { refresh() }
        // 有凭据但从没缓存过 → 自动查一次；没凭据则提示去设置
        if (courses.isEmpty()) {
            if (Credentials.has(this)) refresh()
            else status.text = "请先在首页右上角「设置」里配置学号和密码"
        }
    }

    private fun fmtTime(t: Long) =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    private fun setRefreshing(on: Boolean) = runOnUiThread {
        refreshing = on
        refreshBtn.isEnabled = !on
        swipeRefresh.isRefreshing = on
    }

    private fun refresh() {
        if (refreshing) return
        val xh = Credentials.xh(this)
        val pwd = Credentials.password(this)
        if (xh.isEmpty() || pwd.isNullOrEmpty()) {
            status.text = "请先在首页右上角「设置」里配置学号和密码"
            swipeRefresh.isRefreshing = false
            return
        }

        setRefreshing(true)
        setStatus("登录中…")
        Thread {
            try {
                val cache = GradeCache(filesDir, xh)
                val cached = (cache.load()?.courses ?: emptyList()).associateBy { it.id }

                val client = GradeClient { msg -> setStatus(msg) }
                client.login(xh, pwd)
                val rows = client.listRows()

                // 增量：列表里综合成绩没变的课直接复用缓存的明细，只拉新增/变化的
                var reused = 0
                var fetched = 0
                val merged = mutableListOf<Course>()
                for ((i, r) in rows.withIndex()) {
                    val fresh = client.courseFromRow(r)
                    val old = cached[fresh.id]
                    if (old != null && old.zhcj == fresh.zhcj && old.sfbk == fresh.sfbk) {
                        merged.add(old)
                        reused++
                    } else {
                        try {
                            client.fillDetail(fresh)
                        } catch (e: Exception) {
                            fresh.error = e.message
                        }
                        merged.add(fresh)
                        fetched++
                        if (fetched % 5 == 0) setStatus("拉取明细 $fetched/${rows.size - reused}…")
                    }
                }
                cache.save(merged)
                val now = System.currentTimeMillis()
                runOnUiThread {
                    val tip = if (fetched == 0) "成绩无变化" else "更新 $fetched 门，复用 $reused 门"
                    showCourses(merged, "${fmtTime(now)} · $tip")
                }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
            } finally {
                setRefreshing(false)
            }
        }.start()
    }

    private fun showCourses(result: List<Course>, statusTip: String) {
        courses = result.sortedWith(compareBy({ it.xnxq }, { it.kcmc }))
        status.text = "共 ${courses.size} 门 · $statusTip · 点条目看分项"
        showGpaSummary()
        recycler.adapter = CourseAdapter(buildGroups(courses)) { showDetail(it) }
    }

    /** 顶部总体平均学分绩点；有补考记录时额外给“不含补考”口径 */
    private fun showGpaSummary() {
        val all = weightedGpa(courses)
        val sb = StringBuilder("平均学分绩点 ${all.text}")
        if (all.count > 0) sb.append("（${fmtNum(all.credits)} 学分 / ${all.count} 门）")
        val noRetake = weightedGpa(courses.filter { !isRetake(it) })
        if (noRetake.count != all.count) {
            sb.append(" · 不含补考 ${noRetake.text}")
            if (noRetake.count > 0) sb.append("（${fmtNum(noRetake.credits)} 学分）")
        }
        gpaSummary.text = sb.toString()
        gpaSummary.visibility = View.VISIBLE
    }

    /** 按学期分组（保持学期先后顺序），供列表折叠展示 */
    private fun buildGroups(courses: List<Course>): List<GradeGroup> =
        courses.groupBy { it.xnxq }.map { (xnxq, group) ->
            GradeGroup(xnxq, weightedGpa(group), group)
        }

    private fun fmtNum(v: Double) =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)

    private fun showDetail(c: Course) {
        val sb = StringBuilder()
        sb.append("学期：${c.xnxq}\n学分：${c.xf}    综合：${c.zhcj}    绩点：${c.jd}\n")
        if (c.bkbj.isNotEmpty()) sb.append("标记：${c.bkbj}\n")
        sb.append("\n分项明细：\n")
        if (c.subs.isEmpty()) {
            sb.append(c.error?.let { "获取失败：$it" } ?: "无分项数据")
        } else {
            for (s in c.subs) sb.append("  ${s.name}（${s.zb}%）：${s.score}\n")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(c.kcmc)
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .show()
    }
}

private class GradeGroup(
    val xnxq: String,
    val gpa: GpaResult,
    val courses: List<Course>,
    var collapsed: Boolean = false,
)

private sealed class GradeRow {
    data class Header(val group: GradeGroup) : GradeRow()
    data class Item(val course: Course) : GradeRow()
}

private class CourseAdapter(
    private val groups: List<GradeGroup>,
    private val onClick: (Course) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private fun flatRows(): List<GradeRow> = groups.flatMap { g ->
        if (g.collapsed) listOf(GradeRow.Header(g))
        else listOf(GradeRow.Header(g)) + g.courses.map { GradeRow.Item(it) }
    }

    private var rows = flatRows()

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.semesterName)
        val gpa: TextView = view.findViewById(R.id.semesterGpa)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.courseName)
        val meta: TextView = view.findViewById(R.id.courseMeta)
        val score: TextView = view.findViewById(R.id.courseScore)
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is GradeRow.Header -> 0
        is GradeRow.Item -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(inf.inflate(R.layout.item_grade_header, parent, false))
        } else {
            VH(inf.inflate(R.layout.item_course, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is GradeRow.Header -> {
                val h = holder as HeaderVH
                val arrow = if (row.group.collapsed) "▶" else "▼"
                h.name.text = "$arrow ${row.group.xnxq}"
                h.gpa.text = "平均学分绩点 ${row.group.gpa.text}"
                // 点整行表头折叠/展开
                h.itemView.setOnClickListener {
                    row.group.collapsed = !row.group.collapsed
                    rows = flatRows()
                    notifyDataSetChanged()
                }
            }
            is GradeRow.Item -> {
                val c = row.course
                val h = holder as VH
                h.name.text = c.kcmc
                val tag = if (isRetake(c)) " · 补考" else ""
                h.meta.text = "${c.xnxq} · ${c.xf} 学分 · 绩点 ${c.jd}$tag"
                h.score.text = c.zhcj
                h.itemView.setOnClickListener { onClick(c) }
            }
        }
    }

    override fun getItemCount() = rows.size
}
