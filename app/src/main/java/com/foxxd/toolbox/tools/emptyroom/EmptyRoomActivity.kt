package com.foxxd.toolbox.tools.emptyroom

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.grades.CalendarData
import com.foxxd.toolbox.tools.grades.GradeClient
import com.foxxd.toolbox.tools.jyjs.BorrowActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 空教室查询：复用教务「教室借用申请」页的空闲筛选接口（GradeClient.freeRooms）。
 * 一次刷新拉取全天 12 节的空闲状态（4 线程并发），结果按 教学楼 → 楼层 分组，
 * 楼层可折叠；每个教室下方一条细状态条（绿=空闲 红=占用），点击教室弹详情：
 * 教室信息 + 全天 12 节占用 + 当天课程（kbcx/getRoomkb 教室课表接口，按周缓存）。
 * 时段默认随时间自动选择：12 点前→上午，12-17 点→下午，17-21 点→晚上，21 点后→次日。
 */
class EmptyRoomActivity : AppCompatActivity() {

    /** 时段划分：上午/下午/晚上 各 4 节 */
    private val BLOCKS = listOf("上午" to (1..4), "下午" to (5..8), "晚上" to (9..12))

    private data class RoomInfo(
        val name: String, val jxl: String, val campus: String, val cap: String,
        val examCap: String, val tag: String, val dept: String, val manager: String,
    )

    /** 一间教室 + 全天 12 节的空闲标记 */
    private data class RoomRow(val room: RoomInfo, val free: BooleanArray)

    /** 教室课表显示行：一门课在同一天合并连续节次 */
    private data class CourseRow(val xq: Int, val start: Int, val time: String, val label: String)

    private data class Group(val title: String, val rooms: List<RoomRow>, var collapsed: Boolean = false)

    private sealed class Row {
        data class Header(val group: Group) : Row()
        data class Item(val room: RoomRow) : Row()
    }

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var weekSpinner: Spinner
    private lateinit var daySpinner: Spinner
    private lateinit var blockSpinner: Spinner
    private lateinit var jxlSpinner: Spinner
    private lateinit var queryBtn: MaterialButton
    private lateinit var borrowBtn: MaterialButton
    private lateinit var recycler: RecyclerView

    private var client: GradeClient? = null
    private var term = ""
    private var weekZcs = listOf<String>()

    /** 教室课表缓存：key = "教室名|周次"，值为整周课程（显示时按星期过滤） */
    private val roomKbCache = java.util.concurrent.ConcurrentHashMap<String, List<CourseRow>>()

    /** 教学楼 id 映射（懒加载一次） */
    @Volatile
    private var jxlIdMap: Map<String, String>? = null

    /** 各教学楼教室的 encodeId 映射（按楼懒加载） */
    private val roomIdCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Pair<String, String>>>()

    @Volatile
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emptyroom)

        status = findViewById(R.id.status)
        weekSpinner = findViewById(R.id.weekSpinner)
        daySpinner = findViewById(R.id.daySpinner)
        blockSpinner = findViewById(R.id.blockSpinner)
        jxlSpinner = findViewById(R.id.jxlSpinner)
        queryBtn = findViewById(R.id.queryBtn)
        borrowBtn = findViewById(R.id.borrowBtn)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        daySpinner.adapter = spinnerAdapter((1..7).map { "周${"一二三四五六日"[it - 1]}" })
        blockSpinner.adapter = spinnerAdapter(BLOCKS.map { it.first })
        jxlSpinner.adapter = spinnerAdapter(JXL_NAMES)

        queryBtn.setOnClickListener { query() }
        // 借用申请：直接用教务手机版借用页（可在此页完成筛选 + 借用 + 附件 + 审批）
        borrowBtn.setOnClickListener { openBorrow(null) }

        auth = AuthUi(this) { _, _ -> load() }
        if (auth.credentials(this) != null) {
            auth.hide()
            load()
        } else {
            status.text = "请登录教务系统"
        }
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    /** 今天是一周中的第几天（周一=1 … 周日=7） */
    private fun todayXq(): Int {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    /** 按当前时间返回 (默认时段, 是否顺延到明天) */
    private fun timeDefault(): Pair<Int, Boolean> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> 0 to false   // 早上 → 上午
            h < 17 -> 1 to false   // 中午后 → 下午
            h < 21 -> 2 to false   // 下午五点之后 → 晚上
            else -> 0 to true      // 晚上九点之后 → 第二天上午
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    /** 首次进入：登录并加载校历，得到周次列表后按时间规则设默认值并自动查询 */
    private fun load() {
        if (loading) return
        val cred = auth.credentials(this)
        if (cred == null) {
            auth.show()
            status.text = "请先登录"
            return
        }
        val (xh, pwd) = cred
        loading = true
        setStatus("登录中…")
        Thread {
            try {
                val c = GradeClient { msg -> setStatus(msg) }
                c.login(xh, pwd)
                val t = c.getCurrentXnxq().ifEmpty { throw RuntimeException("获取当前学期失败") }
                val cal = c.fetchCalendar(t)
                if (cal.weeks.isEmpty()) throw RuntimeException("本学期校历为空")
                client = c
                term = t
                weekZcs = cal.weeks.map { it.zc }
                roomKbCache.clear()
                roomIdCache.clear()
                jxlIdMap = null
                runOnUiThread {
                    val (blockIdx, nextDay) = timeDefault()
                    var dayIdx = todayXq() - 1
                    var weekIdx = currentWeekIndex(cal).coerceAtLeast(0)
                    if (nextDay) {
                        dayIdx++
                        if (dayIdx > 6) {
                            dayIdx = 0
                            weekIdx = (weekIdx + 1).coerceAtMost(weekZcs.size - 1)
                        }
                    }
                    weekSpinner.adapter = spinnerAdapter(weekZcs.map { "第 $it 周" })
                    weekSpinner.setSelection(weekIdx)
                    daySpinner.setSelection(dayIdx)
                    blockSpinner.setSelection(blockIdx)
                    query()
                }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
                runOnUiThread { auth.show() }
            } finally {
                loading = false
            }
        }.start()
    }

    /** 今天落在校历第几周（找不到返回 -1） */
    private fun currentWeekIndex(cal: CalendarData): Int {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return cal.weeks.indexOfFirst { w ->
            val dates = w.days.map { it.date }.filter { it.isNotEmpty() }
            dates.contains(today) ||
                    (dates.isNotEmpty() && today >= dates.first() && today <= dates.last())
        }
    }

    private fun query() {
        val c = client ?: return load()
        if (loading || weekZcs.isEmpty()) return
        val zc = weekZcs[weekSpinner.selectedItemPosition.coerceIn(0, weekZcs.size - 1)]
        val xq = daySpinner.selectedItemPosition + 1
        val blockIdx = blockSpinner.selectedItemPosition.coerceIn(0, BLOCKS.size - 1)
        val (blockName, periods) = BLOCKS[blockIdx]
        val jxl = jxlSpinner.selectedItem?.toString() ?: JXL_NAMES[0]
        loading = true
        queryBtn.isEnabled = false
        setStatus("查询中（全天 12 节）…")
        Thread {
            try {
                // 教室全集：不带周次/星期/节次筛选
                val universe = c.freeRooms(term, "", "", "")
                    .map {
                        RoomInfo(
                            it.optString("jsmc"), it.optString("jxlmc"), it.optString("xqmc"),
                            it.optString("zdskrnrs"), it.optString("ksrnrs"),
                            it.optString("jsbq"), it.optString("jsglbmmc"), it.optString("glymc"),
                        )
                    }
                    .distinctBy { it.jxl to it.name }
                // 全天 12 节的空闲集合，4 线程并发（key = 教学楼|教室名）
                val pool = Executors.newFixedThreadPool(4)
                val freeSets = try {
                    val futures = (1..12).map { p ->
                        pool.submit(Callable<Set<String>> {
                            c.freeRooms(term, zc, xq.toString(), p.toString())
                                .map { "${it.optString("jxlmc")}|${it.optString("jsmc")}" }
                                .toSet()
                        })
                    }
                    futures.map { it.get() }
                } finally {
                    pool.shutdown()
                }
                val filtered = universe
                    .filter { jxl == JXL_NAMES[0] || it.jxl.contains(jxl) }
                    .sortedWith(compareBy({ it.jxl }, { naturalKey(it.name) }))
                // 按 教学楼 → 楼层 分组
                val groups = mutableListOf<Group>()
                val byBuilding = filtered.groupBy { it.jxl }
                for ((b, rooms) in byBuilding) {
                    val byFloor = rooms.groupBy { floorOf(it.name) }
                        .toSortedMap(
                            compareBy<String> { if (it == "其他") 1 else 0 }
                                .thenBy { it.removeSuffix("楼").toIntOrNull() ?: 0 }
                        )
                    for ((floor, rs) in byFloor) {
                        val prefix = if (byBuilding.size > 1) "$b · " else ""
                        groups.add(
                            Group(
                                "$prefix$floor（${rs.size} 间）",
                                rs.map { r ->
                                    val key = "${r.jxl}|${r.name}"
                                    RoomRow(r, BooleanArray(12) { i -> freeSets[i].contains(key) })
                                },
                            )
                        )
                    }
                }
                runOnUiThread {
                    auth.hide()
                    recycler.adapter = RoomAdapter(groups, periods) { row -> showRoomDetail(row) }
                    setStatus(
                        "$term · 第 $zc 周 周${"一二三四五六日"[xq - 1]} " +
                                "$blockName（${periods.first}-${periods.last} 节）· 共 ${filtered.size} 间"
                    )
                }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
            } finally {
                loading = false
                runOnUiThread { queryBtn.isEnabled = true }
            }
        }.start()
    }

    /** 从教室名解析楼层：取第一串数字的首位（102→1楼），无数字归「其他」 */
    private fun floorOf(name: String): String {
        val m = Regex("\\d+").find(name) ?: return "其他"
        return if (m.value.length >= 2) "${m.value[0]}楼" else "其他"
    }

    /**
     * 打开教务手机版「教室借用申请」页。先让用户勾选具体要借哪几节（可精确到单节，
     * 默认选中当前时段），再带上教学楼/周次/星期/节次过去；从教室详情进来时再带
     * 教室名与校区，页面会自动回放这些条件并高亮目标教室。
     */
    private fun openBorrow(row: RoomRow?) {
        val preset = BLOCKS.getOrNull(blockSpinner.selectedItemPosition)?.second?.toList()
            ?: listOf(1, 2, 3, 4)
        pickPeriods(preset) { picked ->
            if (picked.isEmpty()) {
                Toast.makeText(this, "至少选一节", Toast.LENGTH_SHORT).show()
                return@pickPeriods
            }
            val jxl = jxlSpinner.selectedItem?.toString().orEmpty()
            val zc = weekZcs.getOrNull(weekSpinner.selectedItemPosition).orEmpty()
            val xq = (daySpinner.selectedItemPosition + 1).toString()
            startActivity(
                BorrowActivity.intent(
                    this,
                    jxl = if (jxl == JXL_NAMES[0]) "" else jxl,
                    room = row?.room?.name.orEmpty(),
                    campus = row?.room?.campus.orEmpty(),
                    zc = zc,
                    xq = xq,
                    jc = picked.map { it.toString() },
                )
            )
        }
    }

    /** 借用前挑节次：12 节的复选列表，默认勾选传入的时段 */
    private fun pickPeriods(preset: List<Int>, onOk: (List<Int>) -> Unit) {
        val all = (1..12).toList()
        val checked = BooleanArray(all.size) { all[it] in preset }
        val labels = all.map { "第 $it 节" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择借用节次（可多选）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("去借用") { _, _ ->
                onOk(all.filterIndexed { i, _ -> checked[i] })
            }
            .show()
    }

    /** 名称里的数字补前导零，让 2 排在 10 前面 */
    private fun naturalKey(name: String): String =
        Regex("\\d+").replace(name) { m -> m.value.padStart(8, '0') }

    // ---------- 教室详情弹窗 ----------

    private fun showRoomDetail(row: RoomRow) {
        val zc = weekZcs.getOrNull(weekSpinner.selectedItemPosition)?.toIntOrNull() ?: return
        val xq = daySpinner.selectedItemPosition + 1
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_room, null)
        val r = row.room

        view.findViewById<TextView>(R.id.dlgTitle).text = r.name
        view.findViewById<TextView>(R.id.dlgSub).text =
            "$term · 第 $zc 周 周${"一二三四五六日"[xq - 1]}"

        // 教室信息（只显示非空项）
        val info = buildString {
            append("${r.jxl} · ${r.campus}")
            if (r.cap.isNotEmpty() && r.cap != "0") append("\n容量 ${r.cap} 人")
            if (r.examCap.isNotEmpty() && r.examCap != "0") append(" · 考试容纳 ${r.examCap} 人")
            if (r.tag.isNotEmpty()) append("\n标签 ${r.tag}")
            if (r.dept.isNotEmpty()) append("\n管理部门 ${r.dept}")
            if (r.manager.isNotEmpty()) append(" · 管理员 ${r.manager}")
        }
        view.findViewById<TextView>(R.id.dlgInfo).text = info

        // 全天 12 节状态条 + 节次数字
        val strip = view.findViewById<LinearLayout>(R.id.dlgStrip)
        val labels = view.findViewById<LinearLayout>(R.id.dlgStripLabels)
        val dm = resources.displayMetrics.density
        for (p in 1..12) {
            val cell = View(this)
            cell.layoutParams = LinearLayout.LayoutParams(0, (14 * dm).toInt(), 1f).apply {
                marginStart = (1 * dm).toInt()
                marginEnd = (1 * dm).toInt()
            }
            cell.background = segDrawable(row.free[p - 1])
            strip.addView(cell)
            val t = TextView(this)
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            t.text = p.toString()
            t.textSize = 9f
            t.setTextColor(Color.parseColor("#9E9E9E"))
            t.gravity = android.view.Gravity.CENTER
            labels.addView(t)
        }

        // 空闲/占用汇总
        view.findViewById<TextView>(R.id.dlgSummary).text =
            "空闲：${ranges(row.free, true)}　占用：${ranges(row.free, false)}"

        // 当天课程：命中缓存瞬时显示；未命中异步查教室课表接口（快），结果缓存整周
        val courseTv = view.findViewById<TextView>(R.id.dlgCourses)
        val dlg = AlertDialog.Builder(this).setView(view).setPositiveButton("关闭", null).show()
        // 借用此教室：跳到教务手机版借用页，并回放本教室 + 当前周次/星期/节次
        view.findViewById<MaterialButton>(R.id.dlgBorrow).setOnClickListener {
            dlg.dismiss()
            openBorrow(row)
        }

        val key = "${r.name}|$zc"
        val cached = roomKbCache[key]
        if (cached != null) {
            courseTv.text = coursesText(cached.filter { it.xq == xq })
        } else {
            courseTv.text = "当天课程：查询中…"
            val c = client
            Thread {
                val text = try {
                    if (c == null) throw RuntimeException("未登录")
                    val jm = jxlIdMap ?: c.kbJxlMap().also { jxlIdMap = it }
                    val jxlId = jm[r.jxl] ?: throw RuntimeException("未找到教学楼「${r.jxl}」")
                    val rm = roomIdCache.getOrPut(jxlId) { c.kbRoomMap(jxlId) }
                    val (rid, xqid2) = rm[r.name] ?: throw RuntimeException("未找到教室「${r.name}」")
                    val rows = parseRoomKb(c.roomKb(term, rid, xqid2, zc.toString()), zc)
                    roomKbCache[key] = rows
                    coursesText(rows.filter { it.xq == xq })
                } catch (e: Exception) {
                    "当天课程：查询失败（${e.message}）"
                }
                runOnUiThread { if (dlg.isShowing) courseTv.text = text }
            }.start()
        }
    }

    /** 聚合教室课表：同课同教师同天合并连续节次；zctype 含 单/双 时按周过滤 */
    private fun parseRoomKb(list: List<JSONObject>, zcWeek: Int): List<CourseRow> {
        val filtered = list.filter {
            val zct = it.optString("zctype")
            !(zct.contains("单") && zcWeek % 2 == 0) && !(zct.contains("双") && zcWeek % 2 == 1)
        }
        return filtered
            .groupBy {
                Triple(
                    it.optInt("xingqi"),
                    stripTags(it.optString("kcmc")),
                    stripTags(it.optString("tmc")),
                )
            }
            .map { (k, v) ->
                val ps = v.map { it.optInt("djc") }.filter { it > 0 }.distinct().sorted()
                CourseRow(k.first, ps.firstOrNull() ?: 99, "第${compress(ps)}节", "${k.second}（${k.third}）")
            }
            .sortedWith(compareBy({ it.xq }, { it.start }))
    }

    private fun coursesText(list: List<CourseRow>): String =
        if (list.isEmpty()) "当天课程：无（若显示占用，可能来自教室借用或考试）"
        else "当天课程：\n" + list.sortedBy { it.start }
            .joinToString("\n") { "${it.time}　${it.label}" }

    private fun stripTags(s: String): String =
        Regex("<[^>]+>").replace(s, "").trim()

    /** 把连续节次压成区间文本，如 [1,2,5,9,10] → "1-2、5、9-10" */
    private fun compress(periods: List<Int>): String {
        if (periods.isEmpty()) return ""
        val out = mutableListOf<String>()
        var start = periods.first()
        var prev = periods.first()
        for (p in periods.drop(1)) {
            if (p == prev + 1) {
                prev = p
            } else {
                out.add(if (start == prev) "$start" else "$start-$prev")
                start = p; prev = p
            }
        }
        out.add(if (start == prev) "$start" else "$start-$prev")
        return out.joinToString("、")
    }

    private fun ranges(free: BooleanArray, want: Boolean): String {
        val ps = (1..12).filter { free[it - 1] == want }
        return if (ps.isEmpty()) "无" else "第${compress(ps)}节"
    }

    private fun segDrawable(isFree: Boolean) = GradientDrawable().apply {
        setColor(Color.parseColor(if (isFree) "#43A047" else "#E53935"))
        cornerRadius = 3f * resources.displayMetrics.density
    }

    // ---------- 列表 ----------

    private class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.headerTitle)
    }

    private class RoomVH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.roomName)
        val cap: TextView = view.findViewById(R.id.roomCap)
        val segs: List<View> = listOf(
            view.findViewById(R.id.seg1),
            view.findViewById(R.id.seg2),
            view.findViewById(R.id.seg3),
            view.findViewById(R.id.seg4),
        )
    }

    private inner class RoomAdapter(
        private val groups: List<Group>,
        private val periods: IntRange,
        private val onRoomClick: (RoomRow) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private fun flatRows(): List<Row> = groups.flatMap { g ->
            if (g.collapsed) listOf(Row.Header(g))
            else listOf(Row.Header(g)) + g.rooms.map { Row.Item(it) }
        }

        private var rows = flatRows()

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Item -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inf.inflate(R.layout.item_room_header, parent, false))
            } else {
                RoomVH(inf.inflate(R.layout.item_room, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> {
                    val h = holder as HeaderVH
                    h.title.text = (if (row.group.collapsed) "▶ " else "▼ ") + row.group.title
                    h.title.setOnClickListener {
                        row.group.collapsed = !row.group.collapsed
                        rows = flatRows()
                        notifyDataSetChanged()
                    }
                }
                is Row.Item -> {
                    val h = holder as RoomVH
                    val rr = row.room
                    h.name.text = rr.room.name
                    h.cap.text = if (rr.room.cap.isNotEmpty() && rr.room.cap != "0")
                        "${rr.room.cap} 座" else ""
                    h.segs.forEachIndexed { i, seg ->
                        val p = periods.first + i
                        seg.background = segDrawable(rr.free[p - 1])
                    }
                    h.itemView.setOnClickListener { onRoomClick(rr) }
                }
            }
        }

        override fun getItemCount() = rows.size
    }

    companion object {
        /** 教学楼显示名（与教务下拉框一致，客户端 contains 过滤） */
        private val JXL_NAMES = listOf(
            "全部教学楼",
            "团东", "团公安楼", "团区东配楼", "团区其它", "团区实验室", "团区实验楼",
            "团区战训馆", "团区综合训练馆", "团区育警中楼", "团区育警西楼", "团区西院平房",
            "团区铸剑楼", "团区阶梯教室", "团区靶场",
            "木区其他", "木区军保", "木区励警楼", "木区科研楼", "木区警英楼", "木区阶梯教室",
        )
    }
}
