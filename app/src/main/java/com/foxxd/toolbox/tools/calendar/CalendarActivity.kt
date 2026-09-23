package com.foxxd.toolbox.tools.calendar

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.Credentials
import com.foxxd.toolbox.tools.grades.CalendarData
import com.foxxd.toolbox.tools.grades.CalendarWeek
import com.foxxd.toolbox.tools.grades.GradeClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalendarActivity : AppCompatActivity() {

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var calBz: TextView
    private lateinit var refreshBtn: MaterialButton
    private lateinit var recycler: RecyclerView

    private var term = ""
    private var data: CalendarData? = null
    private var currentWeekIdx = -1
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        status = findViewById(R.id.status)
        calBz = findViewById(R.id.calBz)
        refreshBtn = findViewById(R.id.refreshBtn)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        auth = AuthUi(this) { _, _ -> refresh() }
        refreshBtn.setOnClickListener { refresh() }

        loadCache()
        if (auth.credentials(this) != null) {
            auth.hide()
            if (data == null) refresh()
        } else {
            status.text = "请登录教务系统"
        }
    }

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
                val (t, cal) = client.fetchCalendarAuto()
                CalendarCache(filesDir, xh).save(t, cal)
                runOnUiThread {
                    term = t
                    showData(cal, "已更新 · ${fmtNow()}")
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
        CalendarCache(filesDir, xh).load()?.let { (t, cal, updatedAt) ->
            term = t
            showData(cal, "缓存 · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))}")
        }
    }

    private fun showData(cal: CalendarData, statusTip: String) {
        data = cal
        status.text = "$term · ${cal.weeks.size} 周 · $statusTip"
        calBz.text = cal.bz
        calBz.visibility = if (cal.bz.isEmpty()) View.GONE else View.VISIBLE

        // 找今天所在的周
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        currentWeekIdx = cal.weeks.indexOfFirst { w ->
            val dates = w.days.map { it.date }.filter { it.isNotEmpty() }
            dates.contains(today) ||
                    (dates.isNotEmpty() && today >= dates.first() && today <= dates.last())
        }
        recycler.adapter = CalendarAdapter(cal.weeks, currentWeekIdx)
        if (currentWeekIdx >= 0) {
            recycler.post { recycler.scrollToPosition(currentWeekIdx) }
        }
    }
}

private class CalendarAdapter(
    private val weeks: List<CalendarWeek>,
    private val currentWeekIdx: Int,
) : RecyclerView.Adapter<CalendarAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.weekCard)
        val title: TextView = view.findViewById(R.id.weekTitle)
        val days: TextView = view.findViewById(R.id.weekDays)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_cal_week, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val w = weeks[position]
        val isCurrent = position == currentWeekIdx
        val validDays = w.days.filter { it.date.isNotEmpty() }
        val range = if (validDays.isNotEmpty())
            "${validDays.first().date.substring(5)} ~ ${validDays.last().date.substring(5)}" else ""
        holder.title.text = "第 ${w.zc} 周 · $range" + (if (isCurrent) "（本周）" else "")

        // 本周显示全部 7 天，其他周只显示有备注的日期
        val dayNames = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val sb = StringBuilder()
        w.days.forEachIndexed { i, d ->
            if (d.date.isEmpty()) return@forEachIndexed
            if (isCurrent || d.remark.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("周${dayNames.getOrElse(i) { "?" }} ${d.date.takeLast(5)}")
                if (d.remark.isNotEmpty()) sb.append("  ${d.remark}")
            }
        }
        holder.days.text = sb.toString()
        holder.days.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE

        holder.card.strokeColor = if (isCurrent) Color.parseColor("#1565C0") else Color.TRANSPARENT
        holder.card.strokeWidth = if (isCurrent) 3 else 0
    }

    override fun getItemCount() = weeks.size
}

/** 校历缓存：按学号存整个学期 */
private class CalendarCache(filesDir: File, xh: String) {

    data class Entry(val term: String, val data: CalendarData, val updatedAt: Long)

    private val file = File(filesDir, "calendar_cache_v2_$xh.json")

    fun load(): Entry? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val weeksArr = root.getJSONArray("weeks")
            val weeks = mutableListOf<CalendarWeek>()
            for (i in 0 until weeksArr.length()) {
                val w = weeksArr.getJSONObject(i)
                val daysArr = w.getJSONArray("days")
                val days = mutableListOf<com.foxxd.toolbox.tools.grades.CalendarDay>()
                for (j in 0 until daysArr.length()) {
                    val d = daysArr.getJSONObject(j)
                    days.add(com.foxxd.toolbox.tools.grades.CalendarDay(
                        d.optString("date"), d.optString("remark")))
                }
                weeks.add(CalendarWeek(w.optString("zc"), days))
            }
            Entry(
                root.optString("term"),
                CalendarData(root.optString("term"), root.optString("bz"), weeks),
                root.optLong("updatedAt"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(term: String, data: CalendarData) {
        val weeksArr = JSONArray()
        for (w in data.weeks) {
            val wo = JSONObject()
            wo.put("zc", w.zc)
            val daysArr = JSONArray()
            for (d in w.days) {
                daysArr.put(JSONObject().put("date", d.date).put("remark", d.remark))
            }
            wo.put("days", daysArr)
            weeksArr.put(wo)
        }
        val root = JSONObject()
        root.put("term", term)
        root.put("bz", data.bz)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("weeks", weeksArr)
        file.writeText(root.toString(), Charsets.UTF_8)
    }
}
