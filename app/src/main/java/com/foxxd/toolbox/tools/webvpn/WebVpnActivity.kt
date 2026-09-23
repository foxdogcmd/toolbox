// WebVpnActivity.kt — 校内网站：经 WebVPN 访问任意校内站点
//
// 后台用统一凭据 CAS 登录 WebVPN，会话 Cookie 同步进 WebView 后：
// 1. 默认打开 WebVPN 门户首页（内有学校收录的校内站点列表）；
// 2. 顶部地址栏输入任意校内网址，按 wengine 规则编码成转接地址直达。
package com.foxxd.toolbox.tools.webvpn

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.R
import com.foxxd.toolbox.tools.AuthUi
import com.foxxd.toolbox.tools.grades.GradeClient
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL

class WebVpnActivity : AppCompatActivity() {

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var web: WebView
    private lateinit var urlInput: EditText

    @Volatile
    private var loading = false

    /** 与 LeaveActivity 同理：缓存 /wengine-vpn/cookie 透传响应，防止 DOM 密集型页面 flood 卡死 */
    private val vpnCookieCache = HashMap<String, Pair<Long, ByteArray>>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webvpn)

        status = findViewById(R.id.status)
        web = findViewById(R.id.web)
        urlInput = findViewById(R.id.urlInput)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.visibility = TextView.GONE
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
                        if (System.currentTimeMillis() - ts < COOKIE_TTL) {
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

        val go = {
            val raw = urlInput.text.toString()
            web.loadUrl(WebVpn.wrap(raw))
        }
        findViewById<MaterialButton>(R.id.goBtn).setOnClickListener { go() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { go(); true } else false
        }

        auth = AuthUi(this) { _, _ -> open() }
        if (auth.credentials(this) != null) {
            auth.hide()
            open()
        } else {
            status.text = "请登录后访问校内网站"
        }
    }

    private fun setStatus(msg: String) = runOnUiThread {
        status.visibility = TextView.VISIBLE
        status.text = msg
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
                GradeClient(WebVpn.BASE, "").loginWebVpn(xh, pwd)
                syncCookies()
                runOnUiThread { web.loadUrl(WebVpn.BASE) }
            } catch (e: Exception) {
                setStatus("[-] ${e.message}")
                runOnUiThread { auth.show() }
            } finally {
                loading = false
            }
        }.start()
    }

    /** 把 HttpURLConnection 会话里的 Cookie 搬进 WebView，实现免登 */
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

    @Deprecated("WebView 内优先返回上一页")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val COOKIE_TTL = 15_000L
    }
}
