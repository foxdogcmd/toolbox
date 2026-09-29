package com.foxxd.toolbox.tools.leave

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.R
import com.foxxd.toolbox.SettingsActivity
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.grades.GradeClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** 不加载学校 SPA 的学生请假页面。所有业务请求仍通过学校 WebVPN 和本人 CAS 会话。 */
class LeaveActivity : AppCompatActivity() {
    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var client: GradeClient? = null
    private var setting = JSONObject()
    private var types = JSONArray()
    private var photo = JSONObject()
    private var records = JSONArray()
    private var onForm = false
    private var submitting = false
    private var agreedAt = ""
    private var selectedType = JSONObject()
    private var startAt: LocalDateTime? = null
    private var endAt: LocalDateTime? = null
    private lateinit var typeButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var reasonInput: EditText
    private lateinit var regionButton: MaterialButton
    private var selectedRegion = ""
    private lateinit var addressInput: EditText
    private lateinit var remarkInput: EditText
    private lateinit var leaveBeijing: SwitchMaterial
    private lateinit var overnight: SwitchMaterial
    private lateinit var injurySection: LinearLayout
    private lateinit var injuryCertificateButton: MaterialButton
    private lateinit var injuryReasonInput: EditText
    private var injuryCertificate = ""
    private lateinit var offCampus: SwitchMaterial
    private lateinit var submitButton: MaterialButton
    private lateinit var imagesHint: TextView
    private lateinit var clearImagesButton: MaterialButton
    private val chosenImages = mutableListOf<Uri>()
    private var missingHint: TextView? = null
    private var settingsButton: MaterialButton? = null

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val accepted = uris.filter { contentResolver.getType(it)?.startsWith("image/") == true }.take(3)
        if (accepted.size != uris.size) {
            Toast.makeText(this, "仅支持最多 3 张图片", Toast.LENGTH_SHORT).show()
        }
        chosenImages.clear()
        chosenImages.addAll(accepted)
        if (::imagesHint.isInitialized) updateImages()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave)
        status = findViewById(R.id.status)
        content = findViewById(R.id.leaveContent)
        scroll = findViewById(R.id.leaveScroll)
        auth = AuthUi(this) { _, _ -> open() }
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra("preview_leave", false)) {
            // 无账号的模拟器可预览原生表单；发布版本不会进入此分支。
            setting = JSONObject().put("ZDQJTS", 100)
            types = JSONArray().put(JSONObject()
                .put("LXMC", "私事").put("LXDM", "eb26c6135ed3411ea30ff6660927707c")
                .put("QJSJLX", "2"))
            auth.hide()
            status.visibility = View.GONE
            showForm()
            return
        }
        if (auth.credentials(this) != null) {
            auth.hide()
            open()
        } else {
            status.text = "请先登录，再查看和申请请假"
        }
    }

    private fun <T> request(message: String, work: (GradeClient) -> T, done: (T) -> Unit) {
        val active = client ?: return
        status.text = message
        status.visibility = View.VISIBLE
        Thread {
            try {
                val result = work(active)
                runOnUiThread {
                    status.visibility = View.GONE
                    done(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = e.message ?: "请求失败，请稍后重试"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                    submitting = false
                    if (onForm) submitButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun open() {
        val credentials = auth.credentials(this)
        if (credentials == null) {
            auth.show()
            status.text = "请先登录"
            return
        }
        status.text = "正在连接学工系统…"
        status.visibility = View.VISIBLE
        Thread {
            try {
                val api = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.LEAVE_PREFIX)
                api.loginLeaveApi(credentials.first, credentials.second)
                val rules = api.leavePost("MobileServiceStudentApply", "getSettingInfo")
                val leaveTypes = api.leavePost("MobileServiceStudentApply", "getStuLeaveType")
                val list = api.leavePost("MobileServiceStudentApply", "getStudentLeaveRecords")
                runOnUiThread {
                    client = api
                    setting = rules.optJSONObject("data") ?: JSONObject()
                    types = leaveTypes.optJSONArray("data") ?: JSONArray()
                    records = list.optJSONArray("data") ?: JSONArray()
                    auth.hide()
                    status.visibility = View.GONE
                    showList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = e.message ?: "连接失败"
                    auth.show()
                }
            }
        }.start()
    }

    private fun refresh() {
        request("正在刷新请假记录…", {
            it.leavePost("MobileServiceStudentApply", "getStudentLeaveRecords")
        }) {
            records = it.optJSONArray("data") ?: JSONArray()
            showList()
        }
    }

    private fun showList() {
        onForm = false
        content.removeAllViews()
        heading("学生请假")
        hint("申请会直接提交到学校学工系统，记录以学校返回的数据为准。")
        button("新建请假申请", primary = true) { beginApply() }
        button("刷新记录") { refresh() }
        if (records.length() == 0) {
            hint("暂无请假记录")
        } else {
            for (index in 0 until records.length()) {
                val row = records.optJSONObject(index) ?: continue
                val card = card()
                val label = row.optString("QJLX_DISPLAY").ifEmpty { "请假申请" }
                card.addView(text(label, 17f, true))
                card.addView(text(
                    "${row.optString("QJKSRQ").take(16)}  至  ${row.optString("QJJSRQ").take(16)}",
                    13f,
                ))
                val state = row.optString("SHZT_DISPLAY").ifEmpty { "状态 ${row.optString("SHZT")}" }
                card.addView(text(state, 13f))
                val sqbh = row.optString("SQBH")
                if (sqbh.isNotEmpty()) card.setOnClickListener { showDetail(sqbh) }
            }
        }
        scroll.scrollTo(0, 0)
    }

    private fun showDetail(sqbh: String) {
        request("正在读取详情…", {
            it.leavePost("MobileServiceStudentApply", "getStudentLeaveRecordDetails",
                JSONObject().put("SQBH", sqbh))
        }) { response ->
            val info = response.optJSONObject("data") ?: JSONObject()
            val message = listOf("QJLX_DISPLAY", "QJSY", "QJKSRQ", "QJJSRQ", "LXQX_DISPLAY", "XXDZ")
                .mapNotNull { key -> info.optString(key).takeIf { it.isNotEmpty() } }
                .joinToString("\n")
            AlertDialog.Builder(this).setTitle("请假详情")
                .setMessage(message.ifEmpty { "学校没有返回可显示的详情" })
                .setPositiveButton("关闭", null).show()
        }
    }

    private fun beginApply() {
        request("正在检查申请资格并读取资料…", { api ->
            val allowed = api.leavePost("MobileServiceStudentApply", "checkWhetherStudentCanApply")
                .optJSONObject("data") ?: JSONObject()
            if (!allowed.optBoolean("applicable")) {
                throw IllegalStateException(allowed.optString("reason").ifEmpty { "当前不能申请请假" })
            }
            val rules = api.leavePost("MobileServiceStudentApply", "getSettingInfo")
            val profile = api.leavePost("MobileServiceStudentApply", "getStuPhoto")
            rules to profile
        }) { (rules, profile) ->
            setting = rules.optJSONObject("data") ?: JSONObject()
            photo = profile.optJSONObject("data") ?: JSONObject()
            agreedAt = setting.optString("CURTIME")
            val key = "pledge_${auth.credentials(this)?.first.orEmpty()}"
            if (setting.optString("SFSYQJCN") == "1" &&
                !getSharedPreferences("leave_prefs", MODE_PRIVATE).getBoolean(key, false)) {
                showPromise(key)
            } else showForm()
        }
    }

    private fun showPromise(preferenceKey: String) {
        AlertDialog.Builder(this)
            .setTitle("请假承诺书")
            .setMessage(android.text.Html.fromHtml(
                setting.optString("QJCN").ifEmpty { "请阅读学校请假承诺书" },
                android.text.Html.FROM_HTML_MODE_COMPACT,
            ))
            .setNegativeButton("取消", null)
            .setPositiveButton("同意并记住") { _, _ ->
                getSharedPreferences("leave_prefs", MODE_PRIVATE)
                    .edit().putBoolean(preferenceKey, true).apply()
                showForm()
            }
            .show()
    }

    private fun showForm() {
        onForm = true
        startAt = null
        endAt = null
        injuryCertificate = ""
        chosenImages.clear()
        val saved = QuickFillStore.loadMap(this)
        content.removeAllViews()
        missingHint = null
        settingsButton = null
        heading("新建请假")
        hint("常用联系人与偏好从设置读取。请确认本次请假的类型、时间和去向。")
        button("‹ 返回记录") { showList() }

        val contactKeys = listOf("JJLXR", "LXDH", "SJH", "DZ_LXRGX", "DZ_SSL", "DZ_FJH", "DZ_LXDH")
        val missing = contactKeys.filter { saved[it].isNullOrBlank() && photo.optString(it).isBlank() }
        if (missing.isNotEmpty()) {
            missingHint = hint("常用联系人信息尚未填全。请先到设置页填写，保存后返回这里。")
            settingsButton = button("打开设置") { startActivity(Intent(this, SettingsActivity::class.java)) }
        }

        val available = (0 until types.length()).mapNotNull { types.optJSONObject(it) }
        selectedType = available.firstOrNull { it.optString("LXMC") == "私事" } ?: JSONObject()
        typeButton = button("请假类型：${selectedType.optString("LXMC").ifEmpty { "请选择" }}") {
            val options = (0 until types.length()).mapNotNull { types.optJSONObject(it) }
            AlertDialog.Builder(this).setTitle("请假类型")
                .setItems(options.map { it.optString("LXMC") }.toTypedArray()) { _, which ->
                    selectedType = options[which]
                    typeButton.text = "请假类型：${selectedType.optString("LXMC")}"
                    startAt = null
                    endAt = null
                    updateTimeButtons()
                    updateInjurySection()
                }.show()
        }
        startButton = button("开始时间：请选择") { pickTime(true) }
        endButton = button("结束时间：请选择") { pickTime(false) }
        reasonInput = input("请假事由 *", saved["QJSY"].orEmpty(), true)
        selectedRegion = saved["LXQX_DISPLAY"].orEmpty()
        content.addView(text("所去地区 *", 14f, true))
        regionButton = button("") {
            RegionPicker.show(this) {
                selectedRegion = it
                updateRegionButton()
            }
        }
        updateRegionButton()
        addressInput = input("具体地点 *", saved["XXDZ"].orEmpty())
        leaveBeijing = switch("离京", saved["DZ_SFLJ"] == "1")
        overnight = switch("隔夜", saved["DZ_SFGYJ"] == "1")
        injurySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        injurySection.addView(text("伤病补充信息", 14f, true))
        injuryCertificateButton = MaterialButton(this).apply {
            isAllCaps = false
            text = "校医院转诊证明：请选择"
            setOnClickListener {
                AlertDialog.Builder(this@LeaveActivity).setTitle("是否由校医院开具转诊证明")
                    .setItems(arrayOf("是", "否")) { _, which ->
                        injuryCertificate = if (which == 0) "1" else "0"
                        text = "校医院转诊证明：${if (which == 0) "是" else "否"}"
                    }.show()
            }
        }
        injurySection.addView(injuryCertificateButton)
        injurySection.addView(text("转诊证明说明或未开具原因 *", 14f, true))
        injuryReasonInput = EditText(this).apply { minLines = 2 }
        injurySection.addView(injuryReasonInput)
        content.addView(injurySection)
        updateInjurySection()
        offCampus = switch("离开学校所在地", true)
        remarkInput = input("备注", saved["DZ_BZ"].orEmpty(), true)
        button("添加备注图片（最多 3 张）") { imagePicker.launch(arrayOf("image/*")) }
        imagesHint = hint("未添加图片")
        clearImagesButton = button("清除已选图片") {
            chosenImages.clear()
            updateImages()
        }
        updateImages()
        submitButton = button("提交请假申请", primary = true) { submit() }
        scroll.scrollTo(0, 0)
    }

    override fun onResume() {
        super.onResume()
        if (!onForm || !::reasonInput.isInitialized) return
        val saved = QuickFillStore.loadMap(this)
        if (reasonInput.text.isBlank()) reasonInput.setText(saved["QJSY"].orEmpty())
        if (selectedRegion.isBlank()) {
            selectedRegion = saved["LXQX_DISPLAY"].orEmpty()
            updateRegionButton()
        }
        if (addressInput.text.isBlank()) addressInput.setText(saved["XXDZ"].orEmpty())
        val required = listOf("JJLXR", "LXDH", "SJH", "DZ_LXRGX", "DZ_SSL", "DZ_FJH", "DZ_LXDH")
        val complete = required.all { !saved[it].isNullOrBlank() || photo.optString(it).isNotBlank() }
        if (complete) {
            missingHint?.visibility = View.GONE
            settingsButton?.visibility = View.GONE
        }
    }

    private fun pickTime(start: Boolean) {
        val calendar = Calendar.getInstance()
        val initial = if (start) startAt else endAt
        if (initial != null) {
            calendar.set(initial.year, initial.monthValue - 1, initial.dayOfMonth,
                initial.hour, initial.minute)
        }
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val value = LocalDateTime.of(year, month + 1, day, hour, minute)
                if (start) startAt = value else endAt = value
                updateTimeButtons()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateTimeButtons() {
        startButton.text = "开始时间：${startAt?.format(timeFormat)?.take(16) ?: "请选择"}"
        endButton.text = "结束时间：${endAt?.format(timeFormat)?.take(16) ?: "请选择"}"
    }

    private fun updateRegionButton() {
        regionButton.text = "所去地区：${selectedRegion.ifEmpty { "请选择省、市、区" }}"
    }

    private fun updateInjurySection() {
        injurySection.visibility = if (selectedType.optString("LXDM") ==
            "354420380c6c4ceea05ebd214e194dae") View.VISIBLE else View.GONE
    }

    private fun updateImages() {
        imagesHint.text = if (chosenImages.isEmpty()) "未添加图片" else "已选择 ${chosenImages.size} 张图片"
        clearImagesButton.visibility = if (chosenImages.isEmpty()) View.GONE else View.VISIBLE
    }

    private data class Draft(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val reason: String,
        val region: String,
        val address: String,
        val remark: String,
        val leaveBeijing: Boolean,
        val overnight: Boolean,
        val offCampus: Boolean,
        val type: JSONObject,
        val saved: Map<String, String>,
        val images: List<Uri>,
        val injuryCertificate: String,
        val injuryReason: String,
    )

    private fun collectDraft(): Draft {
        val start = startAt ?: throw IllegalArgumentException("请选择开始时间")
        val end = endAt ?: throw IllegalArgumentException("请选择结束时间")
        if (!end.isAfter(start)) throw IllegalArgumentException("结束时间必须晚于开始时间")
        val type = selectedType
        if (type.optString("LXDM").isEmpty()) throw IllegalArgumentException("请选择有效的请假类型")
        if (type.optString("QJSJLX", "2") != "2") {
            throw IllegalArgumentException("此类型采用按天选取，请先在学校官方页面申请")
        }
        if (type.optString("SFSCCL") == "1" && chosenImages.isEmpty())
            throw IllegalArgumentException("此类型要求上传证明材料，请添加图片")
        if (type.optString("LXDM") == "354420380c6c4ceea05ebd214e194dae" &&
            (injuryCertificate.isEmpty() || injuryReasonInput.text.isBlank())) {
            throw IllegalArgumentException("请补全伤病转诊证明信息")
        }
        val reason = reasonInput.text.toString().trim()
        val region = selectedRegion
        val address = addressInput.text.toString().trim()
        if (reason.isEmpty()) throw IllegalArgumentException("请填写请假事由")
        if (region.isEmpty() || address.isEmpty()) throw IllegalArgumentException("请填写所去地区和具体地点")
        val saved = QuickFillStore.loadMap(this)
        val required = listOf("JJLXR", "LXDH", "SJH", "DZ_LXRGX", "DZ_SSL", "DZ_FJH", "DZ_LXDH")
        val missing = required.filter { saved[it].isNullOrBlank() && photo.optString(it).isBlank() }
        if (missing.isNotEmpty()) throw IllegalArgumentException("请先在设置页补全常用联系人信息")
        val dayCount = Duration.between(start, end).toMinutes().toDouble() / 1440.0
        if (dayCount < 0.005) throw IllegalArgumentException("请假时间过短，学校按两位小数计算天数")
        val maximum = setting.optDouble("ZDQJTS", 100.0)
        if (dayCount > maximum) throw IllegalArgumentException("请假天数超过学校允许的上限")
        if (type.optString("LXDM") == "0cbf975b28f945d482f09ac1a520e914") {
            if (!start.toLocalDate().isAfter(LocalDateTime.now().toLocalDate()) ||
                start.toLocalDate() != end.toLocalDate() || start.hour < 5 ||
                end.hour >= 22 && end.minute > 0 || end.hour > 22
            ) throw IllegalArgumentException("白日假须从次日起、在同一天 05:00–22:00 内")
        } else if (!overnight.isChecked && start.toLocalDate() != end.toLocalDate()) {
            throw IllegalArgumentException("跨天请假请打开“隔夜”")
        }
        return Draft(start, end, reason, region, address,
            remarkInput.text.toString().trim(), leaveBeijing.isChecked, overnight.isChecked,
            offCampus.isChecked, type, saved, chosenImages.toList(), injuryCertificate,
            injuryReasonInput.text.toString().trim())
    }

    private fun submit() {
        if (submitting) return
        val draft = try { collectDraft() } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("确认提交请假")
            .setMessage("${draft.type.optString("LXMC")}\n${draft.start.format(timeFormat)} 至 ${draft.end.format(timeFormat)}\n${draft.reason}")
            .setNegativeButton("返回修改", null)
            .setPositiveButton("提交") { _, _ -> sendDraft(draft) }
            .show()
    }

    private fun sendDraft(draft: Draft) {
        if (client == null) {
            Toast.makeText(this, "预览模式不会提交申请", Toast.LENGTH_SHORT).show()
            return
        }
        submitting = true
        submitButton.isEnabled = false
        request("正在向学校提交，请勿重复点击…", { api ->
            val regions = api.leaveRegions().optJSONObject("datas")?.optJSONObject("code")
                ?.optJSONArray("rows") ?: throw IllegalStateException("无法读取学校地区代码")
            val allRegions = (0 until regions.length()).mapNotNull { regions.optJSONObject(it) }
            val exact = allRegions.filter { it.optString("name").trimStart('/') == draft.region }
            // 学校码表有少数旧行省略省名；只在完整路径无匹配且后缀唯一时接受。
            val candidates = if (exact.isNotEmpty()) exact else allRegions.filter {
                draft.region.endsWith("/${it.optString("name").trimStart('/')}")
            }
            if (candidates.size != 1) throw IllegalArgumentException("所去地区无法唯一匹配学校地区代码")
            val regionCode = candidates.single().optString("id")

            val params = JSONObject(photo.toString())
            for ((key, value) in draft.saved) {
                if (key !in QuickFillStore.BLACKLIST && !key.endsWith("_DISPLAY")) params.put(key, value)
            }
            val days = BigDecimal(Duration.between(draft.start, draft.end).toMinutes())
                .divide(BigDecimal(1440), 2, RoundingMode.HALF_UP)
            params.put("QJLX", draft.type.optString("LXDM"))
            params.put("QJLX_DISPLAY", draft.type.optString("LXMC"))
            params.put("QJSJLX", "2")
            val nature = if (draft.saved["QJXZ_DISPLAY"] == "因公请假") "因公请假" else "因私请假"
            params.put("QJXZ", if (nature == "因公请假") "2" else "1")
            params.put("QJXZ_DISPLAY", nature)
            params.put("QJSY", draft.reason)
            params.put("QJKSRQ", draft.start.format(timeFormat))
            params.put("QJJSRQ", draft.end.format(timeFormat))
            params.put("QJTS", days.toPlainString())
            params.put("LXQX", regionCode)
            params.put("LXQX_DISPLAY", draft.region)
            params.put("XXDZ", draft.address)
            params.put("DZ_BZ", draft.remark)
            params.put("DZ_SFLJ", if (draft.leaveBeijing) "1" else "0")
            params.put("DZ_SFGYJ", if (draft.overnight) "1" else "0")
            params.put("DZ_ZZZM", if (draft.type.optString("LXDM") ==
                "354420380c6c4ceea05ebd214e194dae") draft.injuryCertificate else "")
            params.put("DZ_ZZZM_YY", if (draft.type.optString("LXDM") ==
                "354420380c6c4ceea05ebd214e194dae") draft.injuryReason else "")
            params.put("SFLKSZD", if (draft.offCampus) "1" else "0")
            // 学校新建表单把“离校假”锁定为 1，旧快速填充设置中的 0 不应覆盖它。
            params.put("SFLX", "1")
            params.put("SJH", draft.saved["SJH"] ?: params.optString("SJH"))
            params.put("SFYDCNS", if (setting.optString("SFSYQJCN") == "1") "1" else "0")
            params.put("YDSJ", agreedAt)
            if (draft.images.isNotEmpty()) {
                val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                val random = SecureRandom()
                val token = buildString(32) { repeat(32) { append(chars[random.nextInt(chars.length)]) } }
                for (uri in draft.images) api.leaveUploadImage(this, uri, token)
                params.put("ZMCL", token)
            }
            api.leavePost("MobileServiceStudentApply", "saveStudentLeaveRecord", params)
        }) {
            submitting = false
            Toast.makeText(this, "申请已提交，正在刷新记录", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun heading(value: String) {
        val view = text(value, 23f, true)
        view.setPadding(0, 0, 0, dp(10))
        content.addView(view)
    }

    private fun hint(value: String): TextView {
        val view = text(value, 14f)
        view.setTextColor(0xff546e7a.toInt())
        view.setPadding(0, dp(6), 0, dp(12))
        content.addView(view)
        return view
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xff172b4d.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = MaterialButton(
        this, null,
        if (primary) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun input(label: String, value: String, multiline: Boolean = false): EditText {
        content.addView(text(label, 14f, true))
        return EditText(this).apply {
            setText(value)
            textSize = 16f
            if (multiline) {
                minLines = 2
                gravity = android.view.Gravity.TOP
            }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun switch(label: String, checked: Boolean) = SwitchMaterial(this).apply {
        text = label
        isChecked = checked
        content.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun card(): LinearLayout {
        val panel = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        panel.addView(inner)
        content.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return inner
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (onForm) showList() else super.onBackPressed()
    }
}
