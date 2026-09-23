// YktActivity.kt — 虚拟校园卡（校园一卡通 H5）
//
// 集成原理（真机企业微信抓包结论）：
// 1. 入口 `/` 文档有门禁：无会话且无白名单 Referer 会被 302「资源受限」或返回「系统不支持…」纯文本。
//    但 index.html 是静态的（789 字节），这里直接用 assets 里的副本通过 shouldInterceptRequest 本地应答，绕过门禁。
// 2. 后端 API（/home/openHomePageGuide 等）不校验 JSESSIONID：请求体 datajson 是 AES-128-ECB 加密的
//    {openid, orgid, ...}，服务端解密即信任。openid 是企业微信给的稳定标识，因此带上 openid 的
//    hash 路由 + 本地 index.html 即可完整使用，无需任何会话。
// 3. UA 设成抓包得到的企业微信 WebView UA，避免页面内的环境判断分支。
// 4. 扫一扫 / 微信支付：页面走微信 JSSDK（wx.config → wx.ready → wx.scanQRCode），jWeixin 最终
//    调用 window.WeixinJSBridge.invoke()。注入 WeixinJSBridge 垫片：scanQRCode 与
//    getBrandWCPayRequest（充值支付）都回 fail 并弹原生提示引导去企业微信 —— 都不回 ok
//    （回了页面会误以为成功）。其余 JSAPI 直接回 ok。
//    （原先的 CameraX + ML Kit 原生扫码已删除：功能在应用内不可用，还白占十几 MB 运行内存）
// 5. 通道选择：默认走 WebVPN（校内外都可用）——用设置页统一凭据 CAS 登录 WebVPN，
//    会话 Cookie 同步进 WebView；本地 HTML 注入 XHR/fetch 重写脚本（绝对路径补上加密前缀），
//    图片/动态 chunk 由拦截器代理转发。VPN 登录失败才回退校园网直连。
package com.foxxd.toolbox.tools.ykt

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.R
import com.foxxd.toolbox.SettingsActivity
import com.foxxd.toolbox.tools.Credentials
import com.foxxd.toolbox.tools.grades.GradeClient
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL

class YktActivity : AppCompatActivity() {

    companion object {
        private const val HOST = "ykt8wxpay.ppsuc.edu.cn"
        private const val VPN_BASE = "https://webvpn.ppsuc.edu.cn"
        // ykt8wxpay.ppsuc.edu.cn 在 WebVPN 下的加密前缀
        // （"wrdvpnisthebest!" hex + AES-128-CFB(key=IV="wrdvpnisthebest!") 加密主机名）
        private const val VPN_PREFIX =
            "/https/77726476706e69737468656265737421e9fc55c430287851674699bc8b40207bfc3f2d043243"
        private const val WXWORK_UA =
            "Mozilla/5.0 (Linux; Android 12; M2006J10C Build/SP1A.210812.016; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/146.0.7680.178 " +
                "Mobile Safari/537.36 XWEB/1460263 MMWEBSDK/20250205 MMWEBID/1840 " +
                "wxwork/5.0.10 MicroMessenger/7.0.1 NetType/WIFI Language/zh Lang/zh " +
                "ColorScheme/Light wwmver/3.26.510.636"

        /** 伪装 WeixinJSBridge：scanQRCode 与微信支付都提示「不可用」并回 fail，
         *  其余 JSAPI 回 ok —— 绝不能对支付回 ok，否则页面会误以为支付成功 */
        private const val BRIDGE_JS = """
            (function(){
              if (window.__tbShimmed) return; window.__tbShimmed = true;
              window.WeixinJSBridge = {
                invoke: function(name, args, cb){
                  if (name === 'scanQRCode') {
                    try { TBScan.unavailable(); } catch(e) {}
                    if (cb) cb({errMsg: name + ':fail 应用内不可用'});
                    return;
                  }
                  if (name === 'getBrandWCPayRequest') {
                    try { TBScan.pay(JSON.stringify(args||{})); } catch(e) {}
                    if (cb) cb({errMsg: name + ':fail cancel'});
                    return;
                  }
                  if (cb) cb({errMsg: name + ':ok'});
                },
                on: function(){}, call: function(){}
              };
              if (document.dispatchEvent) {
                document.dispatchEvent(new Event('WeixinJSBridgeReady'));
              }
            })();
        """
    }

    private lateinit var web: WebView
    private var vpnMode = false
    private var webReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedOpenid().isEmpty()) {
            showGuide()
            return
        }
        startWebView()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页配好 openid 返回后自动进入卡片页
        if (!webReady && savedOpenid().isNotEmpty()) startWebView()
    }

    private fun savedOpenid(): String =
        getSharedPreferences("ykt", MODE_PRIVATE).getString("openid", "") ?: ""

    /** 未配置 openid 时的引导页：链接是每人专属的，不能内置默认账号 */
    private fun showGuide() {
        setContentView(R.layout.activity_ykt_guide)
        findViewById<Button>(R.id.goSettingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun startWebView() {
        webReady = true
        web = WebView(this)
        setContentView(web)

        val openid = savedOpenid()
        val page = "/pages/homepage/guidePage/guidePage?openid=$openid&usertype=1&data=1"

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WXWORK_UA
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(ScanBridge(), "TBScan")
        web.webViewClient = YktClient()

        // 默认走 WebVPN（校内外都可用、最稳定）；VPN 登录失败才回退直连探测
        Thread {
            var viaVpn = tryVpnLogin()
            var direct = false
            if (!viaVpn) {
                direct = probeDirect()
            }
            val useVpn = viaVpn
            runOnUiThread {
                if (!viaVpn && !direct) {
                    Toast.makeText(this, "WebVPN 登录失败且直连不可达，请检查网络/凭据", Toast.LENGTH_LONG).show()
                }
                vpnMode = useVpn
                if (useVpn) {
                    web.loadUrl("$VPN_BASE$VPN_PREFIX/#$page")
                } else {
                    Toast.makeText(this, "WebVPN 不可用，回退校园网直连", Toast.LENGTH_SHORT).show()
                    web.loadUrl("https://$HOST/#$page")
                }
            }
        }.start()
    }

    /** 直连探测：拉一个静态小图，5 秒超时 */
    private fun probeDirect(): Boolean = try {
        val conn = URL("https://$HOST/images/defaulthead.png").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = false
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    /** CAS 登录 WebVPN 并同步会话 Cookie 到 WebView */
    private fun tryVpnLogin(): Boolean {
        val xh = Credentials.xh(this)
        val pwd = Credentials.password(this)
        if (xh.isEmpty() || pwd.isNullOrEmpty()) return false
        return try {
            GradeClient(VPN_BASE, VPN_PREFIX).loginWebVpn(xh, pwd)
            val store = (CookieHandler.getDefault() as? CookieManager)?.cookieStore ?: return false
            val cm = android.webkit.CookieManager.getInstance()
            for (c in store.cookies) {
                val sb = StringBuilder().append(c.name).append("=").append(c.value)
                c.path?.let { sb.append("; Path=").append(it) }
                c.domain?.let { sb.append("; Domain=").append(it) }
                val host = c.domain?.removePrefix(".") ?: "webvpn.ppsuc.edu.cn"
                cm.setCookie("https://$host/", sb.toString())
            }
            cm.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 拦截器代理 GET（WebVPN 模式下入口文档及未带前缀的 /static、/images 请求，无 body 可直接转发） */
    private fun proxyGet(fullUrl: String): WebResourceResponse? = try {
        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", WXWORK_UA)
        android.webkit.CookieManager.getInstance().getCookie(VPN_BASE)?.let {
            conn.setRequestProperty("Cookie", it)
        }
        val bytes = conn.inputStream.readBytes()
        val mime = conn.contentType?.substringBefore(";") ?: "application/octet-stream"
        conn.disconnect()
        WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    }

    private inner class YktClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: android.webkit.WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url
            if (!vpnMode) {
                // 直连模式：仅拦截入口文档，用本地静态副本应答；其余请求直连
                if (url.host == HOST && (url.path.isNullOrEmpty() || url.path == "/")) {
                    return WebResourceResponse("text/html", "utf-8", assets.open("ykt_index.html"))
                }
                return null
            }
            // WebVPN 模式
            if (url.host != "webvpn.ppsuc.edu.cn") return null
            val path = url.path ?: return null
            if (path == VPN_PREFIX || path == "$VPN_PREFIX/") {
                // 入口文档必须代理 webvpn 包装过的版本：被改写的 JS 是 vpn_eval(...) 形式，
                // 依赖包装 HTML 内联脚本里的 vpn_eval/__vpn_hostname 机制；
                // 本地 URL 不带 isError，SPA 不会进入失败页
                return proxyGet("$VPN_BASE$VPN_PREFIX/")
            }
            if (path.startsWith("/static/") || path.startsWith("/images/")) {
                val query = url.encodedQuery?.let { "?$it" } ?: ""
                return proxyGet("$VPN_BASE$VPN_PREFIX$path$query")
            }
            return null
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(BRIDGE_JS, null)
        }
    }

    /** 应用内没有微信/企业微信环境，扫码与支付一律走这个「不可用」提示 */
    private fun showUnavailable(title: String, message: String) {
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private inner class ScanBridge {
        /** 页面请求扫一扫：没有微信/企业微信环境，直接提示不可用 */
        @JavascriptInterface
        fun unavailable() {
            showUnavailable(
                "扫一扫不可用",
                "应用内没有微信 / 企业微信环境，无法调起扫码。\n\n" +
                    "请前往 企业微信 → 工作台 → 虚拟校园卡 使用扫码。",
            )
        }

        /** 页面请求微信支付：应用内无微信环境，记录参数并引导去企业微信完成 */
        @JavascriptInterface
        fun pay(json: String) {
            android.util.Log.d("YktPay", "chooseWXPay args: $json")
            try {
                getExternalFilesDir(null)?.resolve("pay_args.txt")?.writeText(json)
            } catch (_: Exception) {
            }
            showUnavailable(
                "无法调起微信支付",
                "应用内没有微信支付环境，充值无法在这里完成（刚才并未真正扣款）。\n\n" +
                    "请前往 企业微信 → 工作台 → 虚拟校园卡 进行充值。",
            )
        }
    }
}
