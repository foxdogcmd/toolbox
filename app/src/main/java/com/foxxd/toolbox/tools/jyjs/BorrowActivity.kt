// BorrowActivity.kt — 空教室借用（教务「教室借用申请」手机版页 jysq）
//
// 直接用 WebView 打开教务手机版借用页（/admin/pkgl/jyjs/mobile/jysq?kjy=1&cpdx=）：
// 后台完成 CAS 认证后把会话 Cookie 同步进 WebView 实现免登直达；页面自身负责
// 空教室筛选、教室借用表单（含审批流程/附件）与提交，避免复刻 saveJysq 的
// token、审批人、附件等易变逻辑。
//
// 从「空教室」详情页跳进来时会带上教学楼/教室/校区/周次/星期/节次，注入 JS
// 在页面里回放这些筛选（点真实 li / 输入框），让用户直接看到目标教室的「借用」。
package com.foxxd.toolbox.tools.jyjs

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.grades.GradeClient
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * 空教室借用页。可选 extras（来自 EmptyRoomActivity 的详情弹窗）：
 * [EXTRA_JXL] 教学楼名、[EXTRA_ROOM] 教室名、[EXTRA_CAMPUS] 校区名、
 * [EXTRA_ZC] 周次、[EXTRA_XQ] 星期、[EXTRA_JC] 节次（逗号分隔）。
 */
class BorrowActivity : AppCompatActivity() {

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var web: WebView
    private lateinit var reloadBtn: MaterialButton
    private lateinit var overlay: LinearLayout
    private lateinit var prefillText: TextView
    private lateinit var skipBtn: MaterialButton

    @Volatile
    private var loading = false

    /** 遮罩兜底自动关闭，防止注入脚本异常时把用户锁死 */
    private val prefillTimeout = Runnable { hidePrefill() }

    /** WebView 文件选择（借用附件）回调 */
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    /**
     * WebVPN 会把每次 document.cookie 读取变成 /wengine-vpn/cookie 网络请求，
     * 借用页的日期选择器/弹窗一次触发数百个相同请求导致假死，这里按去时间戳的
     * URL 做短 TTL 缓存根治（与 LeaveActivity / WebVpnActivity 同一手段）。
     */
    private val vpnCookieCache = HashMap<String, Pair<Long, ByteArray>>()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_borrow)

        status = findViewById(R.id.status)
        web = findViewById(R.id.web)
        reloadBtn = findViewById(R.id.reloadBtn)
        overlay = findViewById(R.id.prefillOverlay)
        prefillText = findViewById(R.id.prefillText)
        skipBtn = findViewById(R.id.prefillSkip)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.setSupportMultipleWindows(false)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.visibility = View.GONE
                reloadBtn.visibility = View.VISIBLE
                // 只对主页面注入；iframe（借用表单 preToForm）不动
                if (url != null && url.contains(MOBILE_PATH)) {
                    val js = prefillJs()
                    if (js.isNotEmpty()) {
                        showPrefill()
                        view?.evaluateJavascript(js, null)
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.contains("/wengine-vpn/cookie")) return null
                if (!url.contains("method=get")) {
                    synchronized(vpnCookieCache) { vpnCookieCache.clear() }
                    return null
                }
                val key = url.replace(Regex("&vpn_timestamp=\\d+"), "")
                synchronized(vpnCookieCache) {
                    vpnCookieCache[key]?.let { (ts, body) ->
                        if (System.currentTimeMillis() - ts < VPN_COOKIE_TTL) {
                            return WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(body)
                            )
                        }
                    }
                }
                return try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                    val body = conn.inputStream.readBytes()
                    conn.disconnect()
                    synchronized(vpnCookieCache) {
                        vpnCookieCache[key] = System.currentTimeMillis() to body
                    }
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(body))
                } catch (e: Exception) {
                    null
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = params?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    filePicker.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(BorrowBridge(), "TBBorrow")
        reloadBtn.setOnClickListener { web.reload() }
        skipBtn.setOnClickListener { hidePrefill() }

        auth = AuthUi(this) { _, _ -> open() }
        if (auth.credentials(this) != null) {
            auth.hide()
            open()
        } else {
            status.text = "请登录后进入教室借用页"
        }
    }

    private fun setStatus(msg: String) = runOnUiThread {
        status.visibility = View.VISIBLE
        status.text = msg
    }

    /** 回放筛选条件期间挡住 WebView：整屏遮罩 + 禁用交互，脚本跑完自行回调关闭 */
    private fun showPrefill(msg: String = "正在按筛选条件准备…") {
        prefillText.text = msg
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        web.isEnabled = false
        web.postDelayed(prefillTimeout, PREFILL_TIMEOUT_MS)
    }

    private fun hidePrefill() {
        overlay.visibility = View.GONE
        web.isEnabled = true
        web.removeCallbacks(prefillTimeout)
    }

    /** 注入脚本收尾时回调（TBBorrow.prefilled()） */
    private inner class BorrowBridge {
        @android.webkit.JavascriptInterface
        fun prefilled() = runOnUiThread { hidePrefill() }
    }

    private fun open() {
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
                val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.DEFAULT_PREFIX)
                client.login(xh, pwd)
                syncCookies()
                runOnUiThread { web.loadUrl(MOBILE_URL) }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
                runOnUiThread { auth.show() }
            } finally {
                loading = false
            }
        }.start()
    }

    /** 把 HttpURLConnection 会话里的 Cookie 搬进 WebView，实现免登直达。 */
    private fun syncCookies() {
        val store = (CookieHandler.getDefault() as? CookieManager)?.cookieStore ?: return
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        for (c in store.cookies) {
            val host = c.domain?.removePrefix(".") ?: "webvpn.ppsuc.edu.cn"
            val sb = StringBuilder().append(c.name).append("=").append(c.value)
            c.path?.let { sb.append("; Path=").append(it) }
            c.domain?.let { sb.append("; Domain=").append(it) }
            cm.setCookie("https://$host", sb.toString())
        }
        cm.flush()
    }

    /** 详情页带过来的筛选条件 → 页面回放脚本（无 extras 时返回空串，不注入） */
    private fun prefillJs(): String {
        val jxl = intent.getStringExtra(EXTRA_JXL).orEmpty()
        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val campus = intent.getStringExtra(EXTRA_CAMPUS).orEmpty()
        val zc = intent.getStringExtra(EXTRA_ZC).orEmpty()
        val xq = intent.getStringExtra(EXTRA_XQ).orEmpty()
        val jc = intent.getStringArrayExtra(EXTRA_JC)?.toList().orEmpty()
        if (jxl.isEmpty() && room.isEmpty() && zc.isEmpty() && jc.isEmpty()) return ""
        val jcLit = if (jc.isEmpty()) "" else jc.joinToString(",") { JSONObject.quote(it) }
        return """
(function () {
  // 收尾回调：关掉原生遮罩，放开 WebView
  var done = function () { try { TBBorrow.prefilled(); } catch (e) {} };
  if (window.__tbBorrowPrefill) { done(); return; }
  window.__tbBorrowPrefill = true;
  var CFG = {
    jxl: ${JSONObject.quote(jxl)}, room: ${JSONObject.quote(room)},
    campus: ${JSONObject.quote(campus)}, zc: ${JSONObject.quote(zc)},
    xq: ${JSONObject.quote(xq)}, jc: [$jcLit]
  };
  var norm = function (s) { return String(s || '').replace(/\s+/g, '').trim(); };
  var txt = function (el) { return norm(el ? el.textContent : ''); };
  var clickBy = function (sel, getter, want) {
    var list = document.querySelectorAll(sel);
    for (var i = 0; i < list.length; i++) {
      if (String(getter(list[i])) === String(want)) { list[i].click(); return true; }
    }
    return false;
  };
  var attr = function (k) { return function (el) { return el.getAttribute(k); }; };

  // 每个动作可以带 wait:选择器 —— 轮询等目标渲染出来再点（节次/周次都是异步渲染的）
  var actions = [];
  if (CFG.jxl && CFG.jxl !== '全部教学楼') {
    actions.push({ wait: '.schoolBulid li', gap: 700, fn: function () {
      return clickBy('.schoolBulid li', txt, norm(CFG.jxl));
    } });
  }
  if (CFG.campus) {
    actions.push({ wait: '.xqList li', gap: 300, fn: function () {
      var key = norm(CFG.campus).replace('校区', '');
      var list = document.querySelectorAll('.xqList li');
      for (var i = 0; i < list.length; i++) {
        if (txt(list[i]).indexOf(key) >= 0) { list[i].click(); return true; }
      }
      return false;
    } });
  }
  if (CFG.room) {
    actions.push({ gap: 500, fn: function () {
      var inp = document.getElementById('jsmc');
      if (inp) inp.value = CFG.room;
      var show = document.getElementById('jsmc-show');
      if (show) show.innerText = CFG.room;
      var btn = document.getElementById('jsmc-btn');
      if (btn) { btn.click(); return true; }
      return false;
    } });
  }
  if (CFG.zc) {
    actions.push({ wait: '.zcList li', gap: 900, fn: function () {
      return clickBy('.zcList li', attr('data-value'), CFG.zc);
    } });
  }
  if (CFG.xq) {
    actions.push({ wait: '.weekList li', gap: 1200, fn: function () {
      return clickBy('.weekList li', attr('data-value'), CFG.xq);
    } });
  }
  // 精确到单节：逐节点击（页面 .jcList 是 multipleList，支持多选）
  CFG.jc.forEach(function (p) {
    actions.push({ wait: '.jcList li', gap: 260, fn: function () {
      return clickBy('.jcList li', attr('data-value'), p);
    } });
  });
  if (actions.length) {
    actions.push({ wait: '.sureBtb', gap: 1300, fn: function () {
      var ok = document.getElementsByClassName('sureBtb');
      if (ok.length) { ok[0].click(); return true; }
      return false;
    } });
    // 高亮是异步轮询的，等它结束（或放弃）再放行，避免列表还没渲染就放开点击
    actions.push({ gap: 0, fn: function () {
      awaitingHighlight = true;
      highlight(done);
    } });
  }

  // 目标教室高亮：渲染是异步的，轮询等它出现；结束（或等不到）后调用 onEnd
  function highlight(onEnd) {
    if (!CFG.room) { if (onEnd) onEnd(); return; }
    var tries = 0;
    var iv = setInterval(function () {
      var items = document.querySelectorAll('.roomItem');
      for (var i = 0; i < items.length; i++) {
        if (txt(items[i]).indexOf(norm(CFG.room)) >= 0) {
          items[i].style.outline = '2px solid #1565C0';
          items[i].style.borderRadius = '8px';
          clearInterval(iv);
          if (onEnd) onEnd();
          return;
        }
      }
      if (++tries > 20) { clearInterval(iv); if (onEnd) onEnd(); }
    }, 400);
  }

  var awaitingHighlight = false;
  var bootTries = 0;
  var boot = setInterval(function () {
    var ready = document.querySelectorAll('.schoolBulid li').length > 0;
    if (!ready) {
      if (++bootTries > 60) { clearInterval(boot); done(); }
      return;
    }
    clearInterval(boot);
    var i = 0;
    var step = function () {
      if (i >= actions.length) { if (!awaitingHighlight) done(); return; }
      var a = actions[i++];
      if (a.wait && document.querySelectorAll(a.wait).length === 0) {
        a.tries = (a.tries || 0) + 1;
        if (a.tries <= 40) { actions.splice(i - 1, 0, a); setTimeout(step, 250); return; }
      }
      try { a.fn(); } catch (e) {}
      setTimeout(step, a.gap || 250);
    };
    step();
  }, 300);
})();
"""
    }

    @Deprecated("WebView 内优先返回上一页")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val MOBILE_PATH = "/admin/pkgl/jyjs/mobile/jysq"
        private const val MOBILE_URL = GradeClient.DEFAULT_VPN_BASE + GradeClient.DEFAULT_PREFIX +
                MOBILE_PATH + "?kjy=1&cpdx="
        private const val VPN_COOKIE_TTL = 15_000L
        private const val PREFILL_TIMEOUT_MS = 30_000L

        const val EXTRA_JXL = "jxl"
        const val EXTRA_ROOM = "room"
        const val EXTRA_CAMPUS = "campus"
        const val EXTRA_ZC = "zc"
        const val EXTRA_XQ = "xq"
        const val EXTRA_JC = "jc"

        /** 供「空教室」详情页跳转使用 */
        fun intent(
            from: android.content.Context,
            jxl: String,
            room: String,
            campus: String,
            zc: String,
            xq: String,
            jc: List<String>,
        ): Intent = Intent(from, BorrowActivity::class.java)
            .putExtra(EXTRA_JXL, jxl)
            .putExtra(EXTRA_ROOM, room)
            .putExtra(EXTRA_CAMPUS, campus)
            .putExtra(EXTRA_ZC, zc)
            .putExtra(EXTRA_XQ, xq)
            .putExtra(EXTRA_JC, jc.toTypedArray())
    }
}
