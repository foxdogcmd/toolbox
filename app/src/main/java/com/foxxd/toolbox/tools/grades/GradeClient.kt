// GradeClient.kt — 教务系统成绩查询客户端（CAS 统一认证 + WebVPN 通道）
// 移植自 go/client.go，逻辑保持一致；拆分为 登录 / 列表 / 明细 三步以支持增量刷新
package com.foxxd.toolbox.tools.grades

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SubItem(val name: String, val zb: String, val score: String)

/**
 * 统一身份认证（CAS）明确拒绝了凭据：账号或密码错误、账号被锁定、需要验证码等。
 * 与网络/解析类错误区分开，好让开屏登录能给出「密码错了」这种准确提示。
 */
class CasAuthException(message: String) : RuntimeException(message)

data class Course(
    val id: String,
    val xnxq: String, val kcmc: String, val xf: String,
    val zhcj: String, val jd: String, val sfbk: String,
    var bkbj: String = "",
    val subs: MutableList<SubItem> = mutableListOf(),
    var error: String? = null,
)

class GradeClient(
    private val vpnBase: String = DEFAULT_VPN_BASE,
    private val prefix: String = DEFAULT_PREFIX,
    private val log: (String) -> Unit = {},
) {

    companion object {
        const val DEFAULT_VPN_BASE = "https://webvpn.ppsuc.edu.cn"
        // 教务系统在 WebVPN 下的加密前缀（地址栏 /https/ 后面那段十六进制）
        const val DEFAULT_PREFIX = "/https/77726476706e69737468656265737421fae00f8c37237d53300d8db9d6562d"

        /** 学工系统(xsfw)在 WebVPN 下的加密前缀，与教务系统前缀不同。 */
        const val LEAVE_PREFIX = "/https/77726476706e69737468656265737421e8f00f8c37237d53300d8db9d6562d"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val TIMEOUT = 30_000
        private const val CAS_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        private val random = SecureRandom()
        @Volatile private var cookieInited = false

        private fun initCookies() {
            if (!cookieInited) {
                synchronized(this) {
                    if (!cookieInited) {
                        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        cookieInited = true
                    }
                }
            }
        }
    }

    init {
        initCookies()
    }

    private fun url(path: String) = vpnBase.trimEnd('/') + prefix + path

    // ---------- HTTP 辅助 ----------

    /** 发起请求并跟随重定向，返回 (最终 URL, 响应体) */
    private fun http(
        method: String, url: String,
        form: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<String, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.setRequestProperty("User-Agent", UA)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.requestMethod = method
        if (form != null) {
            val body = form.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code >= 400) {
            // 带上响应体里的可读文本（ stripped 标签），方便定位 401/403 的真实原因
            // （如「账号未激活」「已被锁定」「禁止访问」等提示都写在正文里）
            val plain = Regex("<script(?s).*?</script>").replace(text, "")
                .let { Regex("<[^>]+>").replace(it, "") }
                .split(Regex("\\s+")).filter { it.isNotEmpty() }
                .joinToString(" ").take(150)
            throw IOException(
                "HTTP $code: $url" + if (plain.isNotEmpty()) "\n$plain" else ""
            )
        }
        return conn.url.toString() to text
    }

    private fun get(url: String) = http("GET", url)

    // ---------- CAS 密码加密 ----------

    private fun randStr(n: Int) = buildString(n) {
        repeat(n) { append(CAS_CHARS[random.nextInt(CAS_CHARS.length)]) }
    }

    // 复刻 CAS 登录页的 encryptAES(): AES-CBC，密钥=salt，IV=随机16字符，
    // 明文 = 随机64字符 + 密码，PKCS7 填充，输出 base64
    private fun casEncryptPwd(password: String, salt: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(randStr(16).toByteArray(Charsets.UTF_8)),
        )
        val data = (randStr(64) + password).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(cipher.doFinal(data))
    }

    private fun fieldValue(html: String, name: String): String {
        for (re in listOf(
            Regex("""id="$name"[^>]*value="([^"]*)""""),
            Regex("""name="$name"[^>]*value="([^"]*)""""),
        )) {
            re.find(html)?.let { return it.groupValues[1] }
        }
        return ""
    }

    private fun casErrorMsg(html: String): String {
        // 金智 CAS 新版页面用 id="showErrorTip" / id="formErrorTip" 显示错误，
        // 老版用 id="msg" / class="auth_error"；两种都试，取第一个非空的
        for (re in listOf(
            Regex("""(?s)id="showErrorTip"[^>]*>(.*?)</span>"""),
            Regex("""(?s)id="formErrorTip"[^>]*>(.*?)</div>"""),
            Regex("""(?s)id="pwdErrorTip"[^>]*>(.*?)</div>"""),
            Regex("""(?s)id="msg"[^>]*>(.*?)</span>"""),
            Regex("""(?s)class="auth_error[^"]*"[^>]*>(.*?)</"""),
        )) {
            for (m in re.findAll(html)) {
                val s = Regex("<[^>]+>").replace(m.groupValues[1], "")
                    .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                if (s.isNotEmpty()) return s
            }
        }
        return "用户名或密码错误，或账号需要验证码/已被锁定"
    }

    // ---------- 登录 ----------

    // 访问 startURL，若被重定向到 CAS 登录页则提交凭据并跟随回跳
    private fun casLogin(username: String, password: String, startURL: String, label: String) {
        val (loginURL, html) = get(startURL)
        if (!loginURL.contains("authserver/login")) return // 已有有效会话，未触发 CAS
        val execution = fieldValue(html, "execution")
        val salt = fieldValue(html, "pwdEncryptSalt")
        if (execution.isEmpty()) {
            throw RuntimeException("未能解析 CAS 登录页（$label），页面结构可能已变化")
        }
        val form = mapOf(
            "username" to username,
            "password" to (if (salt.isNotEmpty()) casEncryptPwd(password, salt) else password),
            "captcha" to "",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to "",
            "execution" to execution,
            "rmShown" to "1",
        )
        // POST 到当前 URL（保留 ?service= 参数，CAS 据此回跳）
        val (finalURL, body) = http("POST", loginURL, form)
        if (finalURL.contains("authserver/login")) {
            throw CasAuthException(
                casErrorMsg(body) + "。如提示验证码，请先在浏览器正常登录一次统一认证。"
            )
        }
        log("[+] CAS 统一认证成功（$label）")
    }

    /**
     * 清掉本进程里所有 CAS / WebVPN 会话 Cookie。
     * 开屏登录必须先调用它：否则上一次登录留下的有效会话会让 casLogin 直接跳过认证，
     * 输错密码也能「登进去」。
     */
    fun clearSessions() {
        try {
            (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
        } catch (e: Exception) {
            // 忽略：清不掉时后面那次认证仍会照常跑
        }
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
        }
    }

    /** 仅登录 WebVPN（CAS 通道）；会话 Cookie 存于共享 CookieManager，供 WebView 免登使用 */
    fun loginWebVpn(username: String, password: String) {
        casLogin(username, password, "$vpnBase/login?cas_login=true", "WebVPN")
    }

    /** 第一步 + 第二步：登录 WebVPN（CAS 通道），再进教务系统（CAS 单点登录） */
    fun login(username: String, password: String) {
        casLogin(username, password, "$vpnBase/login?cas_login=true", "WebVPN")
        val (finalURL, _) = get(url("/admin/caslogin"))
        if (finalURL.contains("authserver/login")) {
            throw RuntimeException("教务系统跳转 CAS 后未能自动登录（TGC 票据未生效）")
        }
        if (finalURL.contains("/admin/login") || finalURL.contains("/admin/caslogin")) {
            throw RuntimeException("教务系统登录失败：仍停留在登录页，请确认浏览器里能正常进入教务系统")
        }
        log("[+] 教务系统登录成功")
    }

    // ---------- 成绩接口 ----------

    private fun postJson(path: String, form: Map<String, String>): JSONObject {
        val (_, body) = http(
            "POST", url(path), form,
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url("/fe/"),
            ),
        )
        val resp = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RuntimeException("成绩接口未返回 JSON（会话可能已失效）")
        }
        if (resp.optInt("ret", -1) != 0) {
            throw RuntimeException("成绩查询失败：${resp.optString("msg")}")
        }
        return resp
    }

    /** 成绩列表（原始行，含隐藏 id；不含分项明细，请求量小，适合每次刷新先比对） */
    fun listRows(start: String = "2000-2000-1", end: String = "2099-2099-2"): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        var pn = 1
        while (true) {
            val resp = postJson(
                "/admin/xsd/xsdcjcx/xsdQueryXscjList?fxbz=0&gridtype=jqgrid",
                mapOf(
                    "page.pn" to pn.toString(),
                    "page.size" to "200",
                    "sort" to "xnxq",
                    "order" to "desc",
                    "startXnxq" to start,
                    "endXnxq" to end,
                ),
            )
            val arr = resp.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) results.add(arr.getJSONObject(i))
            if (pn >= resp.optInt("totalPages", 1)) break
            pn++
        }
        log("[+] 共查到 ${results.size} 条成绩记录")
        return results
    }

    /** 由列表行构造 Course（不含明细） */
    fun courseFromRow(r: JSONObject) = Course(
        id = r.optString("id"),
        xnxq = r.optString("xnxq"),
        kcmc = r.optString("kcmc"),
        xf = r.optString("hdxf"),
        zhcj = r.optString("zhcj"),
        jd = r.optString("jd"),
        sfbk = r.optString("sfbk"),
    )

    /** 拉取单门课的分项明细并填进 course */
    fun fillDetail(course: Course) {
        var fx: JSONArray? = null
        var mx: JSONArray? = null
        for ((i, ep) in listOf(
            "/admin/xsd/xsdzgcjcx/getCjfxList",
            "/admin/xsd/xsdzgcjcx/getXscjmxDetail",
        ).withIndex()) {
            try {
                val resp = postJson("$ep?zhcjid=${URLEncoder.encode(course.id, "UTF-8")}", emptyMap())
                if (i == 0) fx = resp.optJSONArray("data") else mx = resp.optJSONArray("data")
            } catch (_: Exception) {
            }
        }
        // 分项代码 -> (名称, 占比)，保持接口返回顺序
        val fxmap = LinkedHashMap<String, Pair<String, String>>()
        fx?.let { arr ->
            for (j in 0 until arr.length()) {
                val f = arr.getJSONObject(j)
                fxmap[f.optString("gcfxdm")] = f.optString("gcfxmc") to f.optString("zb")
            }
        }
        if (mx != null && mx.length() > 0) {
            val row = mx.getJSONObject(0)
            course.bkbj = row.optString("bkbj")
            for ((code, info) in fxmap) {
                val v = row.optString(code)
                if (v.isNotEmpty()) course.subs.add(SubItem(info.first, info.second, v))
            }
        }
    }

    // ---------- 课表 ----------

    /** 当前数据学期（如 2026-2027-1） */
    fun getCurrentXnxq(): String {
        val (_, body) = http(
            "GET", url("/admin/api/jcsj/xnxq/getCurrentXnxq"),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        return try {
            JSONObject(body).optJSONObject("data")?.optString("dataXnxq") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- 空教室 ----------

    /**
     * 空教室查询（借用页的「空闲周次/星期/节次」筛选接口）：
     * 指定周次/星期/节次，返回该时段空闲的教室原始行
     * （jsmc 教室名、jxlmc 教学楼、xqmc 校区、zdskrnrs 容量等）。
     */
    fun freeRooms(xnxq: String, zc: String, xq: String, jc: String): List<JSONObject> {
        val resp = postJson(
            "/admin/pkgl/jyjs/jsxx?pxfs=5",
            mapOf(
                "queryFields" to "id,jsbh,jsmc,jstp,jslx,glymc,zdskrnrs,ksrnrs,jxlmc,szlc,gnqmc,xqmc,jsbq,jsglbmmc,syyx,jyzt,zylx,",
                "xnxq" to xnxq,
                "zcStr" to zc,
                "xqStr" to xq,
                "jcStr" to jc,
                "page.pn" to "1",
                "page.size" to "500",
                "sort" to "",
                "order" to "",
            ),
        )
        val arr = resp.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * 某教室某天的课程（全校总课表 querylist，jsmc + 周次 + 星期筛选）。
     * 关键参数：sort 必须给字段名（空值服务端会 500）、kklx 固定全开、xsqbkb=0。
     * 行字段：kcmc/skjs（带 HTML，需 stripHtml）、schooltime（如「第3周 星期三 9-10节」）、jxbmc。
     */
    // ---------- 教室课表（/admin/jwxtgld/kbcx，与学习通同源） ----------

    /** 教学楼：jxlmc（如"团区铸剑楼"）→ jxlId */
    fun kbJxlMap(): Map<String, String> {
        val (_, body) = http(
            "GET", url("/admin/jwxtgld/kbcx/getJxlList"),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url("/admin/jwxtgld/kbcx/roomkblist"),
            ),
        )
        val arr = JSONArray(body)
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("jxlmc").replace(Regex("^\\[\\d+\\]\\s*"), "")
            if (name.isNotEmpty()) out[name] = o.optString("id")
        }
        return out
    }

    /** 某教学楼教室：rmc（如"团铸剑楼108"）→ (encodeId, xqdm) */
    fun kbRoomMap(jxlId: String): Map<String, Pair<String, String>> {
        val (_, body) = http(
            "POST", url("/admin/jwxtgld/kbcx/getRommList"),
            form = mapOf("id" to jxlId, "jslx" to ""),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url("/admin/jwxtgld/kbcx/roomkblist"),
            ),
        )
        val arr = JSONArray(body)
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("rmc")
            if (name.isNotEmpty()) out[name] = o.optString("encodeId") to o.optString("xqdm")
        }
        return out
    }

    /** 教室当周课表（服务端按 week 过滤，返回整周 7 天；djc=节次，每节一条） */
    fun roomKb(xnxq: String, encodeId: String, xqid: String, week: String): List<JSONObject> {
        val q = "xnxq=${URLEncoder.encode(xnxq, "UTF-8")}" +
                "&rid=${URLEncoder.encode(encodeId, "UTF-8")}" +
                "&xqid=${URLEncoder.encode(xqid, "UTF-8")}" +
                "&week=${URLEncoder.encode(week, "UTF-8")}"
        val (_, body) = http(
            "GET", url("/admin/jwxtgld/kbcx/getRoomkb?$q"),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url("/admin/jwxtgld/kbcx/roomkblist"),
            ),
        )
        val arr = JSONArray(body)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private class SchedulePage(val terms: List<String>, val xhid: String, val xqdm: String)

    /** 课表页：解析隐藏字段（xhid 为学生加密标识）和可选学期列表 */
    private fun fetchSchedulePage(term: String): SchedulePage {
        val (_, html) = http(
            "GET",
            url("/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=${URLEncoder.encode(term, "UTF-8")}"),
            headers = mapOf("Referer" to url("/admin/")),
        )
        if (html.length < 5000) throw RuntimeException("课表页打开失败（可能需要先在浏览器访问一次）")
        val xhid = fieldValue(html, "xhid")
        if (xhid.isEmpty()) throw RuntimeException("课表页解析失败（页面结构可能已变化）")
        val xqdm = fieldValue(html, "xqdm")
        val terms = Regex("""<option value="(\d{4}-\d{4}-\d)"""")
            .findAll(html).map { it.groupValues[1] }.distinct().toList()
        return SchedulePage(terms, xhid, xqdm)
    }

    private fun fetchScheduleEntries(term: String, xhid: String, xqdm: String): List<ScheduleEntry> {
        val q = "xnxq=${URLEncoder.encode(term, "UTF-8")}&xhid=$xhid&xqdm=$xqdm" +
                "&zdzc=&zxzc=&xskbxslx=0"
        val (_, body) = http(
            "GET", url("/admin/xsd/pkgl/xskb/sdpkkbList?$q"),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url("/admin/"),
            ),
        )
        val data = try {
            JSONObject(body).optJSONArray("data")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = mutableListOf<ScheduleEntry>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val weeks = parseWeeks(o.optString("zcstr"), o.optString("zc"))
            out.add(
                ScheduleEntry(
                    day = o.optInt("xingqi"),
                    jie = o.optInt("djc"),
                    name = stripHtml(o.optString("kcmc")),
                    room = stripHtml(o.optString("croommc")),
                    teacher = stripHtml(o.optString("tmc")),
                    weeks = weeks,
                    weeksText = o.optString("zc"),
                    campus = o.optString("jsxq"),
                    jxbmc = o.optString("jxbmc"),
                )
            )
        }
        return out
    }

    /** 查课表：先按当前学期查，为空则回退到可选学期里最新的一个。返回 (学期, 课表) */
    fun fetchScheduleAuto(): Pair<String, List<ScheduleEntry>> {
        var term = getCurrentXnxq().ifEmpty { throw RuntimeException("获取当前学期失败") }
        var page = fetchSchedulePage(term)
        var entries = fetchScheduleEntries(term, page.xhid, page.xqdm)
        if (entries.isEmpty()) {
            val alt = page.terms.firstOrNull()
            if (alt != null && alt != term) {
                page = fetchSchedulePage(alt)
                entries = fetchScheduleEntries(alt, page.xhid, page.xqdm)
                if (entries.isNotEmpty()) {
                    term = alt
                    log("[+] 当前学期暂无课表，已回退到 $alt")
                }
            }
        }
        log("[+] 共 ${entries.size} 条课表记录（$term）")
        return term to entries
    }

    /** 今天是当前学期第几周（假期返回空串） */
    fun getTodayZc(): String {
        val today = java.text.SimpleDateFormat("yyyy-M-d", java.util.Locale.US)
            .format(java.util.Date())
        val (_, body) = http(
            "GET", url("/admin/xsd/jcsj/getDayBz?rq=$today"),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        return try {
            JSONObject(body).optJSONObject("data")
                ?.optJSONObject("xlrq")?.optString("zc") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- 校历 ----------

    private fun prevTerm(term: String): String {
        val m = Regex("""(\d{4})-(\d{4})-(\d)""").matchEntire(term) ?: return ""
        val (y1, y2, s) = m.destructured
        return if (s == "1") "${y1.toInt() - 1}-${y2.toInt() - 1}-2" else "$y1-$y2-1"
    }

    /** 校历：返回 (学期, 数据)。当前学期无数据时回退上一学期 */
    fun fetchCalendarAuto(): Pair<String, CalendarData> {
        var term = getCurrentXnxq().ifEmpty { throw RuntimeException("获取当前学期失败") }
        var cal = fetchCalendar(term)
        if (cal.weeks.isEmpty()) {
            val prev = prevTerm(term)
            if (prev.isNotEmpty()) {
                val c2 = fetchCalendar(prev)
                if (c2.weeks.isNotEmpty()) {
                    term = prev
                    cal = c2
                    log("[+] 当前学期暂无校历，已回退到 $prev")
                }
            }
        }
        log("[+] 校历共 ${cal.weeks.size} 周（$term）")
        return term to cal
    }

    fun fetchCalendar(xnxqh: String): CalendarData {
        val dayKeys = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val (_, body) = http(
            "GET", url("/admin/xsd/jcsj/xlgl/getData/$xnxqh"),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val arr = try {
            JSONArray(body)
        } catch (e: Exception) {
            JSONArray()
        }
        // 跨月的周会被拆成两行（每行只填本月的日期，其余为 null），按周次合并
        val slotsByZc = LinkedHashMap<String, Pair<Array<String?>, Array<String>>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ny = o.optString("ny")
            val zc = o.optString("zc")
            val (dates, remarks) = slotsByZc.getOrPut(zc) {
                arrayOfNulls<String>(7) to Array(7) { "" }
            }
            dayKeys.forEachIndexed { idx, k ->
                val d = o.optString(k).trim()
                if (d.isNotEmpty()) dates[idx] = "$ny-${d.padStart(2, '0')}"
                val r = o.optString("${k}remark").replace("无备注信息", "").trim()
                if (r.isNotEmpty()) remarks[idx] = r
            }
        }
        val weeks = mutableListOf<CalendarWeek>()
        for ((zc, pair) in slotsByZc) {
            val (dates, remarks) = pair
            weeks.add(
                CalendarWeek(
                    zc,
                    dates.mapIndexed { idx, dt -> CalendarDay(dt ?: "", remarks[idx]) }
                )
            )
        }
        var bz = ""
        try {
            val (_, bzBody) = http(
                "GET", url("/admin/xsd/jcsj/xlgl/getBz/$xnxqh"),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            )
            bz = JSONObject(bzBody).optString("xlbz")
        } catch (_: Exception) {
        }
        return CalendarData(xnxqh, bz, weeks)
    }

    // ---------- 学生请假（学工系统 xsfw，需用 LEAVE_PREFIX 实例化） ----------

    /**
     * 手机版请假页(swmxsqjapp)免登：WebVPN 统一认证 + 验证 CAS 单点可达。
     * 成功后调用方把共享 CookieManager 里的 Cookie 同步给 WebView，即可直接打开页面。
     */
    fun loginLeaveWeb(username: String, password: String) {
        casLogin(username, password, "$vpnBase/login?cas_login=true", "WebVPN")
        val (finalURL, _) = get(url("/xsfw/sys/swmxsqjapp/*default/index.do"))
        if (finalURL.contains("authserver/login")) {
            throw RuntimeException("单点登录学工系统失败")
        }
    }

    // ---------- 学工系统（xsfw）综合测评 / 班级互评 ----------

    /**
     * 学工系统子应用免登：CAS 登录 WebVPN → 进入 app → 解析 APPID → 切换应用角色。
     * 学工系统大部分 modules 下的 do 接口按「应用+角色」鉴权，未切角色会直接 403，
     * 所以这一步不能省（浏览器里是打开 app 时自动做的）。
     */
    fun loginXsfw(username: String, password: String, appName: String): String {
        casLogin(username, password, "$vpnBase/login?cas_login=true", "WebVPN")
        val (finalURL, html) = get(url("/xsfw/sys/$appName/*default/index.do"))
        if (finalURL.contains("authserver/login")) {
            throw RuntimeException("单点登录学工系统失败")
        }
        val appId = Regex("APPID:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val roleId = if (appId.isNotEmpty()) appRoleId(appId, appName) else ""
        if (roleId.isNotEmpty()) {
            get(url("/xsfw/sys/funauthapp/api/changeAppRole/$appName/$roleId.do"))
            log("[+] 已切换角色：$roleId")
        }
        return roleId
    }

    /** 从 getAppConfig 里取第一个可用角色的 id */
    private fun appRoleId(appId: String, appName: String): String {
        return try {
            val (_, body) = http(
                "GET",
                url(
                    "/xsfw/sys/swpubapp/indexmenu/getAppConfig.do?appId=" +
                            URLEncoder.encode(appId, "UTF-8") +
                            "&appName=" + URLEncoder.encode(appName, "UTF-8") + "&roleIdparam="
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            )
            JSONObject(body).optJSONObject("HEADER")?.optJSONArray("dropMenu")
                ?.optJSONObject(0)?.optString("id").orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /** 学工系统 modules 接口：body 是 data=<JSON>，返回原始 JSON */
    private fun postData(path: String, json: String): JSONObject {
        val (_, body) = http(
            "POST", url(path), mapOf("data" to json),
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url("/xsfw/sys/zhcptybbapp/*default/index.do"),
            ),
        )
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RuntimeException("接口未返回 JSON（会话可能已失效）")
        }
    }

    /** 当前测评批次参数（学年学期、批次代码 PCDM、截止时间、学生信息） */
    fun hpInfo(): HpInfo {
        val resp = postData(
            "/xsfw/sys/zhcptybbapp/modules/evaluationApplyController/getPageInitParams.do",
            "{}",
        )
        val data = resp.optJSONObject("data")
            ?: throw RuntimeException("读取测评参数失败：${resp.optString("description")}")
        val sz = data.optJSONObject("ZHCP_SZ") ?: JSONObject()
        val stu = data.optJSONObject("STUDENT_INFO") ?: JSONObject()
        return HpInfo(
            cpxn = sz.optString("CPXN"),
            cpxq = sz.optString("CPXQ"),
            pcdm = sz.optString("PCDM"),
            zbkmc = sz.optString("ZBKMC"),
            dxmc = sz.optString("DXMC"),
            termText = sz.optString("CPXN_DISPLAY") + sz.optString("CPXQ_DISPLAY"),
            window = "${sz.optString("XSKSSJ")} ~ ${sz.optString("XSJSSJ")}",
            studentName = stu.optString("XM"),
            xh = stu.optString("XH"),
            bjdm = stu.optString("BJDM_DISPLAY"),
            // 本次测评是否包含班级互评环节（IS_BJHPSJ），以及是否允许评（SFXYBJHP）
            jbBjhp = data.optBoolean("IS_BJHPSJ", false),
            allowBjhp = data.optBoolean("SFXYBJHP", false),
        )
    }

    /**
     * 班级互评当前是否已提交：两个接口口径不同，都要看。
     * SHZT：审核状态（evaluationBjhpController）；SFTJ：是否提交（ekdz）。
     * 已提交后页面会变成只读，改分必须去学工系统。
     */
    fun hpSubmitStatus(pcdm: String): Pair<String, String> {
        val shzt = try {
            postData(
                "/xsfw/sys/zhcptybbapp/modules/evaluationBjhpController/getDqShzt.do",
                JSONObject().put("PCDM", pcdm).toString(),
            ).optJSONObject("data")?.optString("SHZT").orEmpty()
        } catch (e: Exception) {
            ""
        }
        val sftj = try {
            postData(
                "/xsfw/sys/zhcptybbapp/modules/ekdz/getDqShzt.do",
                JSONObject().put("PCDM", pcdm).toString(),
            ).optJSONObject("data")?.optString("SFTJ").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return shzt to sftj
    }

    /** 班级互评待评学生（EMAP 卡片数据源 cxddfxsxx；同一 PCDM 返回全班） */
    fun hpStudents(pcdm: String): List<JSONObject> {
        val (_, body) = http(
            "POST", url("/xsfw/sys/zhcptybbapp/modules/cpsq/cxddfxsxx.do"),
            mapOf(
                "PCDM" to pcdm, "PCDM1" to pcdm, "PCDM2" to pcdm,
                "querySetting" to "[]", "pageSize" to "500", "pageNumber" to "1",
            ),
            mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val rows = try {
            JSONObject(body).optJSONObject("datas")
                ?.optJSONObject("cxddfxsxx")?.optJSONArray("rows")
        } catch (e: Exception) {
            null
        } ?: JSONArray()
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    /**
     * 某学生的互评评分项。ZBLB=9（打分项）且 SFSY=1 的才是可填分项，
     * 其余是计算项（如「德育 M1」「现实表现」），提交时不要带上。
     */
    fun hpScoreItems(xsid: String, pcdm: String): List<HpItem> {
        val payload = JSONObject().put("XSID", xsid).put("PCDM", pcdm)
        val resp = postData(
            "/xsfw/sys/zhcptybbapp/modules/evaluationBjhpController/getBjhbZb.do",
            payload.toString(),
        )
        if (resp.optString("code") != "0") {
            throw RuntimeException("读取评分项失败：${resp.optString("msg")}")
        }
        val arr = resp.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<HpItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("ZBLB") == "9" && o.optString("SFSY") == "1") {
                out.add(
                    HpItem(
                        zbdm = o.optString("ZBDM"),
                        name = o.optString("ZBMC"),
                        max = o.optString("ZDZ"),
                        min = o.optString("ZXZ"),
                        fs = o.optString("FS", ""),
                    )
                )
            }
        }
        return out
    }

    /**
     * 保存某学生的班级互评分数（evaluationBjhpController/saveHpScore.do）。
     *
     * 注意：这个接口就是页面上的「保存」，只写草稿，**不是提交**。
     * 提交是另一个接口（ekdz/submithp.do），本工具刻意不实现 —— 提交必须由用户
     * 自己去学工系统确认，避免误提交。返回空串表示成功，否则是错误信息。
     */
    fun saveHpScore(pcdm: String, xsid: String, scores: List<Pair<String, String>>): String {
        val list = JSONArray()
        for ((zb, fs) in scores) list.put(JSONObject().put("ZBDM", zb).put("FS", fs))
        val payload = JSONObject()
            .put("PCDM", pcdm)
            .put("CPDF_LIST", list)
            .put("XSID", xsid)
        val resp = postData(
            "/xsfw/sys/zhcptybbapp/modules/evaluationBjhpController/saveHpScore.do",
            payload.toString(),
        )
        return if (resp.optString("code") == "0") "" else resp.optString("msg", "提交失败")
    }
}

data class HpInfo(
    val cpxn: String, val cpxq: String, val pcdm: String, val zbkmc: String,
    val dxmc: String,
    val termText: String, val window: String, val studentName: String, val xh: String,
    val bjdm: String, val jbBjhp: Boolean, val allowBjhp: Boolean,
)

data class HpItem(
    val zbdm: String, val name: String, val max: String,
    val min: String = "0", val fs: String = "",
)

private fun stripHtml(s: String) =
    Regex("<[^>]+>").replace(s, "").trim()

private fun parseWeeks(zcstr: String, zc: String): List<Int> {
    val set = sortedSetOf<Int>()
    zcstr.split(",").forEach { it.trim().toIntOrNull()?.let(set::add) }
    if (set.isEmpty()) {
        Regex("""(\d+)\s*-\s*(\d+)""").find(zc)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            for (w in a..b) set.add(w)
        }
    }
    return set.toList()
}

data class ScheduleEntry(
    val day: Int,          // 星期几 1-7
    val jie: Int,          // 第几大节
    val name: String,
    val room: String,
    val teacher: String,
    val weeks: List<Int>,
    val weeksText: String,
    val campus: String,
    val jxbmc: String,
)

data class CalendarDay(val date: String, val remark: String)

data class CalendarWeek(val zc: String, val days: List<CalendarDay>)

data class CalendarData(val xnxqh: String, val bz: String, val weeks: List<CalendarWeek>)
