package com.foxxd.toolbox.tools.schedule

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.Credentials
import com.foxxd.toolbox.tools.grades.GradeClient
import com.foxxd.toolbox.tools.grades.ScheduleEntry
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ScheduleActivity : AppCompatActivity() {

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var weekLabel: TextView
    private lateinit var refreshBtn: MaterialButton
    private lateinit var grid: LinearLayout
    private lateinit var emptyView: TextView

    private var term = ""
    private var entries: List<ScheduleEntry> = emptyList()
    private var week = 1
    private var maxWeek = 1
    private var refreshing = false

    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val palette = listOf(
        "#E3F2FD", "#E8F5E9", "#FFF3E0", "#F3E5F5",
        "#E0F7FA", "#FCE4EC", "#FFFDE7", "#EFEBE9",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        status = findViewById(R.id.status)
        weekLabel = findViewById(R.id.weekLabel)
        refreshBtn = findViewById(R.id.refreshBtn)
        grid = findViewById(R.id.grid)
        emptyView = findViewById(R.id.emptyView)

        auth = AuthUi(this) { _, _ -> refresh() }

        findViewById<MaterialButton>(R.id.weekPrev).setOnClickListener {
            if (week > 1) { week--; render() }
        }
        findViewById<MaterialButton>(R.id.weekNext).setOnClickListener {
            if (week < maxWeek) { week++; render() }
        }
        refreshBtn.setOnClickListener { refresh() }

        // 缓存优先
        loadCache()
        // 有凭据：隐藏登录卡片；没缓存则自动拉取
        if (auth.credentials(this) != null) {
            auth.hide()
            if (entries.isEmpty()) refresh()
        } else {
            status.text = "请登录教务系统"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    private fun setRefreshing(on: Boolean) = runOnUiThread {
        refreshing = on
        refreshBtn.isEnabled = !on
    }

    private fun refresh() {
        if (refreshing) return
        val cred = auth.credentials(this)
        if (cred == null) {
            auth.show()
            status.text = "请先登录"
            return
        }
        val (xh, pwd) = cred
        setRefreshing(true)
        setStatus("登录中…")
        Thread {
            try {
                val client = GradeClient { msg -> setStatus(msg) }
                client.login(xh, pwd)
                val (t, list) = client.fetchScheduleAuto()
                val zc = client.getTodayZc().toIntOrNull() ?: 1
                ScheduleCache(filesDir, xh).save(t, list)
                runOnUiThread {
                    term = t
                    entries = list
                    maxWeek = list.flatMap { it.weeks }.maxOrNull() ?: 1
                    week = zc.coerceIn(1, maxWeek)
                    render("已更新 · ${fmtNow()}")
                }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
                runOnUiThread { auth.show() }
            } finally {
                setRefreshing(false)
            }
        }.start()
    }

    private fun fmtNow() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun loadCache() {
        val xh = Credentials.xh(this)
        if (xh.isEmpty()) return
        ScheduleCache(filesDir, xh).load()?.let { c ->
            term = c.term
            entries = c.entries
            maxWeek = c.entries.flatMap { it.weeks }.maxOrNull() ?: 1
            render("缓存 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(c.updatedAt))}")
        }
    }

    private fun render(statusTip: String? = null) {
        weekLabel.text = "第 $week 周 · $term"
        if (statusTip != null) status.text = "共 ${entries.size} 条 · $statusTip"
        buildGrid()
    }

    // ---------- 周表格 ----------

    private fun cellColor(name: String) =
        Color.parseColor(palette[abs(name.hashCode()) % palette.size])

    private fun baseCell(width: Int, height: Int, bg: Int): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            setBackgroundColor(bg)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

    private fun courseCell(e: ScheduleEntry, width: Int, height: Int, maxLines: Int): TextView =
        baseCell(width, height, cellColor(e.name)).apply {
            text = e.name
            // 用 dp 而不是 sp：避免系统字体放大把格子撑爆
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            setTextColor(Color.parseColor("#37474F"))
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showDetail(e) }
        }

    private fun buildGrid() {
        grid.removeAllViews()
        val weekEntries = entries.filter { week in it.weeks }
        if (weekEntries.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        val screenW = resources.displayMetrics.widthPixels
        val indexW = dp(26)
        val colW = (screenW - dp(24) - indexW - dp(16)) / 7  // 减去页面 padding 和格子 margin
        val headH = dp(30)
        val cellH = dp(62)
        val gray = Color.parseColor("#ECEFF1")

        fun headerCell(text: String, width: Int, height: Int) =
            baseCell(width, height, gray).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#455A64"))
            }

        // 接口会对同一门课返回多条记录（不同教学班/教室），同格同课合并为一条
        // （教室、教师合并显示），真正不同的课才按多门堆叠
        val byCell = weekEntries
            .groupBy { Triple(it.day, it.jie, it.name) }
            .map { (_, v) ->
                v.first().copy(
                    weeks = v.flatMap { it.weeks }.distinct().sorted(),
                    room = v.map { it.room }.filter { it.isNotEmpty() }
                        .distinct().joinToString(" / "),
                    teacher = v.map { it.teacher }.filter { it.isNotEmpty() }
                        .distinct().joinToString(" / "),
                )
            }
            .groupBy { it.day to it.jie }
        val maxJie = weekEntries.maxOf { it.jie }

        // 表头行：空 + 周一~周日
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, headH
            )
        }
        headRow.addView(headerCell("", indexW, headH))
        for (d in dayNames) headRow.addView(headerCell(d, colW, headH))
        grid.addView(headRow)

        // 内容区：按列排（左侧节次列 + 7 天各一列），连上的课合并成一个高格
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // 节次列
        val indexCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                indexW + dp(2), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (jie in 1..maxJie) indexCol.addView(headerCell("$jie", indexW, cellH))
        content.addView(indexCol)

        // 每天的列
        for (day in 1..7) {
            val dayCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    colW + dp(2), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            var jie = 1
            while (jie <= maxJie) {
                val list = byCell[day to jie]
                if (list == null) {
                    dayCol.addView(baseCell(colW, cellH, Color.parseColor("#FAFAFA")))
                    jie++
                    continue
                }
                // 连上判断：下一节课程名集合相同则合并
                val names = list.map { it.name }.toSet()
                var span = 1
                while (jie + span <= maxJie &&
                    byCell[day to jie + span]?.map { it.name }?.toSet() == names
                ) span++
                val h = cellH * span
                if (list.size == 1) {
                    dayCol.addView(courseCell(list[0], colW, h, maxLines = 3 * span))
                } else {
                    // 同一格多门不同的课：竖排堆叠，缩字号保证放得下
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(colW, h)
                    }
                    for (e in list) {
                        box.addView(
                            courseCell(e, colW - dp(2), h / list.size - dp(2), maxLines = 2).apply {
                                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9f)
                            }
                        )
                    }
                    dayCol.addView(box)
                }
                jie += span
            }
            content.addView(dayCol)
        }
        grid.addView(content)
    }

    private fun showDetail(e: ScheduleEntry) {
        val msg = buildString {
            append("星期${"一二三四五六日"[e.day - 1]} · 第 ${e.jie} 大节\n")
            if (e.room.isNotEmpty()) append("教室：${e.room}\n")
            if (e.teacher.isNotEmpty()) append("教师：${e.teacher}\n")
            if (e.campus.isNotEmpty()) append("校区：${e.campus}\n")
            append("周次：${e.weeksText}周\n")
            if (e.jxbmc.isNotEmpty()) append("教学班：${e.jxbmc}")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }
}

/** 课表缓存：按学号存整学期数据 */
private class ScheduleCache(filesDir: File, xh: String) {

    data class Entry(val term: String, val entries: List<ScheduleEntry>, val updatedAt: Long)

    private val file = File(filesDir, "schedule_cache_$xh.json")

    fun load(): Entry? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("entries")
            val list = mutableListOf<ScheduleEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val weeks = mutableListOf<Int>()
                val wa = o.optJSONArray("weeks")
                if (wa != null) for (j in 0 until wa.length()) weeks.add(wa.getInt(j))
                list.add(
                    ScheduleEntry(
                        day = o.optInt("day"), jie = o.optInt("jie"),
                        name = o.optString("name"), room = o.optString("room"),
                        teacher = o.optString("teacher"), weeks = weeks,
                        weeksText = o.optString("weeksText"),
                        campus = o.optString("campus"), jxbmc = o.optString("jxbmc"),
                    )
                )
            }
            Entry(root.optString("term"), list, root.optLong("updatedAt"))
        } catch (e: Exception) {
            null
        }
    }

    fun save(term: String, entries: List<ScheduleEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("day", e.day); o.put("jie", e.jie)
            o.put("name", e.name); o.put("room", e.room); o.put("teacher", e.teacher)
            o.put("weeks", JSONArray(e.weeks)); o.put("weeksText", e.weeksText)
            o.put("campus", e.campus); o.put("jxbmc", e.jxbmc)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("term", term)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("entries", arr)
        file.writeText(root.toString(), Charsets.UTF_8)
    }
}
