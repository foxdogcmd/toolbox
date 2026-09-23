package com.foxxd.toolbox.tools.leave

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
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
 * 学生请假：后台完成 CAS 认证后，把会话 Cookie 同步进 WebView，
 * 直接打开学校手机版请假页面（含请假申请功能），不做原生列表。
 * 注入 JS：承诺书自动勾选 + 弹窗提速；「快速填充」把设置页维护的值写回表单
 * （选择类字段通过真实 UI 回放选中，保证内部代码正确）。
 */
class LeaveActivity : AppCompatActivity() {

    private lateinit var auth: AuthUi
    private lateinit var status: TextView
    private lateinit var web: WebView
    private lateinit var fillBtn: MaterialButton

    @Volatile
    private var loading = false

    /**
     * WebVPN cookie 透传请求缓存：打开日期组件时页面会瞬间发出 500+ 个
     * /wengine-vpn/cookie?method=get 请求（仅 vpn_timestamp 不同）导致卡死，
     * 这里按去时间戳的 URL 做短 TTL 缓存根治。
     */
    private val vpnCookieCache = HashMap<String, Pair<Long, ByteArray>>()

    /** WebView 文件选择（证明材料）回调 */
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    /** JS → 原生桥：上报当前是否在表单页（控制快速填充按钮显隐） */
    class QuickFillJs(
        private val activity: AppCompatActivity,
        private val onFormPage: (Boolean) -> Unit,
    ) {
        @JavascriptInterface
        fun setFormPage(visible: Boolean) {
            activity.runOnUiThread { onFormPage(visible) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leave)

        status = findViewById(R.id.status)
        web = findViewById(R.id.web)
        fillBtn = findViewById(R.id.fillBtn)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.addJavascriptInterface(QuickFillJs(this) { onForm ->
            // 「快速填充」只在新增请假表单页显示，避免挡住列表页的新增按钮
            fillBtn.visibility = if (onForm) View.VISIBLE else View.GONE
        }, "TbQuickFill")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.visibility = View.GONE
                view?.evaluateJavascript(INJECT_JS, null)
            }

            /**
             * WebVPN 的 JS 会把每次 document.cookie 读取变成一个
             * /wengine-vpn/cookie 网络请求，日期选择器等 DOM 密集型弹窗
             * 一次触发 500+ 个相同请求导致页面假死。这里缓存响应根治。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.contains("/wengine-vpn/cookie")) return null
                if (!url.contains("method=get")) {
                    // 写 cookie 的请求放行，并清掉缓存避免脏数据
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

        fillBtn.setOnClickListener { quickFill() }

        auth = AuthUi(this) { _, _ -> open() }
        if (auth.credentials(this) != null) {
            auth.hide()
            open()
        } else {
            status.text = "请登录后进入请假页面"
        }
    }

    private fun quickFill() {
        val json = QuickFillStore.load(this)
        if (json == null) {
            Toast.makeText(this, "暂无历史填写记录，请先提交一次请假", Toast.LENGTH_SHORT).show()
            return
        }
        web.evaluateJavascript("window.__tbFill(${JSONObject.quote(json)})") { result ->
            val ok = result?.contains("ok") == true
            Toast.makeText(
                this,
                if (ok) "已填充" else "请在「新增请假」表单页使用",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun setStatus(msg: String) = runOnUiThread {
        status.visibility = View.VISIBLE
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
                val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.LEAVE_PREFIX)
                client.loginLeaveWeb(xh, pwd)
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

    @Deprecated("WebView 内优先返回上一页")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val MOBILE_URL = GradeClient.DEFAULT_VPN_BASE + GradeClient.LEAVE_PREFIX +
                "/xsfw/sys/swmxsqjapp/*default/index.do#/qjsq"

        /** VPN cookie 透传响应的缓存时长：flood 集中在几秒内,15s 足以根治 */
        private const val VPN_COOKIE_TTL = 15_000L

        /**
         * 注入到请假 SPA 的增强脚本。组件一律从 Vue 根实例沿 $children 树查找
         * （组件树很小），不做全 DOM 扫描，避免卡顿。
         * 0. MOB_UTIL.getI18nCookie 加缓存：vant 弹窗每格渲染都调它读 cookie，
         *    经 wengine 变成同步网络请求，一次弹窗上千个导致卡死，这是根治手段
         *    （document.cookie 读缓存 + 原生拦截做兜底）；
         * 1. 承诺书页(#/qjcn)：每个 tick 强制清倒计时 + 勾选 + 启用同意按钮（patch 所有匹配 vm，重进必生效）；
         * 2. 表单页(#/xzqjsq)：上报路由给原生，控制「快速填充」悬浮按钮显隐；
         * 3. window.__tbFill(json)：原生悬浮按钮调用。普通字段直接写 formValue；
         *    请假性质/请假类型/所去地点通过真实 UI 回放选中（xg-select/xg-tree 有内部状态，直接 set 不生效）。
         *    不做网页表单自动提取——会覆盖用户在设置页手动维护的数据。
         */
        private val INJECT_JS = """
(function () {
  if (window.__tbInjected) return;
  window.__tbInjected = true;

  // 与 QuickFillStore.BLACKLIST 对应:页面自动加载/不可改的字段不填充
  var SKIP = {${QuickFillStore.BLACKLIST.joinToString(", ") { "\"$it\": 1" }}};

  // ---- 根治弹窗卡死:vant 弹窗每渲染一个格子都调 MOB_UTIL.getI18nCookie,
  // 它经 wengine 存根引用读取 cookie → 每次都发同步网络请求(一次弹窗上千个)。
  // 给 getI18nCookie 加永久缓存: locale 不会变,同名只真正读一次。
  var mobPatched = false;
  function patchMobUtil() {
    if (mobPatched) return;
    var mu = window.MOB_UTIL;
    if (!mu || typeof mu.getI18nCookie !== 'function') return;
    var orig = mu.getI18nCookie;
    var cache = {};
    mu.getI18nCookie = function (name) {
      if (!(name in cache)) cache[name] = orig.call(this, name);
      return cache[name];
    };
    mobPatched = true;
  }
  patchMobUtil();

  // ---- 兜底:WebVPN 还会把 document.cookie 的每次读取变成同步网络请求,
  // 给 cookie getter 加 100ms 读缓存(wengine 用存根引用时拦不住,主要靠上面的补丁)。
  (function wrapCookie() {
    try {
      var d = Object.getOwnPropertyDescriptor(document, 'cookie');
      if (!d && window.Document) d = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
      if (!d && window.HTMLDocument) d = Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie');
      if (!d || !d.get || d.configurable === false) return;
      var cached = null;
      var ts = 0;
      Object.defineProperty(document, 'cookie', {
        configurable: true,
        enumerable: d.enumerable,
        get: function () {
          var now = Date.now();
          if (cached !== null && now - ts < 100) return cached;
          cached = d.get.call(document);
          ts = now;
          return cached;
        },
        set: function (v) {
          cached = null;
          ts = 0;
          d.set.call(document, v);
        },
      });
    } catch (e) {}
  })();

  function eachVm(root, fn) {
    var stack = [root];
    var n = 0;
    while (stack.length && n < 5000) {
      var v = stack.pop(); n++;
      if (!v) continue;
      try { fn(v); } catch (e) {}
      var ch = v.${'$'}children || [];
      for (var i = 0; i < ch.length; i++) stack.push(ch[i]);
    }
  }

  function findVm(pred) {
    var roots = [];
    var ids = ['selectrole', 'app'];
    for (var i = 0; i < ids.length; i++) {
      var el = document.getElementById(ids[i]);
      if (el && el.__vue__) roots.push(el.__vue__);
    }
    if (!roots.length) {
      var any = document.querySelector('div');
      var v = any && any.__vue__;
      while (v && v.${'$'}parent) v = v.${'$'}parent;
      if (v) roots.push(v);
    }
    var found = null;
    for (var j = 0; j < roots.length && !found; j++) {
      eachVm(roots[j], function (vm) { if (!found && pred(vm)) found = vm; });
    }
    return found;
  }

  // ---- 承诺书：每个 tick 都强制清零倒计时 + 勾选 + 启用按钮 ----
  // 页面里可能存在多个带 times/sfty 的 vm(keep-alive 缓存等),必须全部 patch;
  // 按钮的禁用态绑定的是 buttonDisabled,不是 times,必须直接置 false
  function tickCn(hash) {
    if (hash.indexOf('/qjcn') < 0) return;
    var roots = [];
    var ids = ['selectrole', 'app'];
    for (var i = 0; i < ids.length; i++) {
      var el = document.getElementById(ids[i]);
      if (el && el.__vue__) roots.push(el.__vue__);
    }
    for (var j = 0; j < roots.length; j++) {
      eachVm(roots[j], function (vm) {
        if (typeof vm.times === 'number' && typeof vm.sfty === 'boolean') {
          if (vm.timer) { try { clearInterval(vm.timer); } catch (e) {} vm.timer = undefined; }
          vm.times = 0;
          vm.sfty = true;
          if ('buttonDisabled' in vm) vm.buttonDisabled = false;
        }
      });
    }
  }

  // ---- 表单：缓存组件引用，值经桥存到原生 ----
  var formVmCache = null;
  function formVm() {
    if (formVmCache && !formVmCache._isDestroyed && formVmCache.formValue) return formVmCache;
    formVmCache = findVm(function (v) {
      return v.formValue && typeof v.submitApply === 'function';
    });
    return formVmCache;
  }

  // ---- 快速填充:普通字段直接写 formValue;弹窗选择类字段通过真实 UI 回放 ----
  // (xg-select/xg-tree 有内部状态,直接 set formValue 不生效;回放走真实选中逻辑)
  var REPLAY_SKIP = {QJXZ:1, QJXZ_DISPLAY:1, QJLX:1, QJLX_DISPLAY:1, LXQX:1, LXQX_DISPLAY:1};

  function visEl(el) {
    if (!el) return false;
    var cs = getComputedStyle(el);
    return cs.display !== 'none' && cs.visibility !== 'hidden' &&
        el.getBoundingClientRect().width > 0;
  }

  function clickLeafText(text) {
    var all = document.querySelectorAll('span,div,li,p,label');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.children.length === 0 && (e.innerText || '').trim() === text && visEl(e)) {
        e.click();
        return true;
      }
    }
    return false;
  }

  function clickItem(sel, text) {
    var items = document.querySelectorAll(sel);
    for (var i = 0; i < items.length; i++) {
      var e = items[i];
      var t = (e.innerText || '').trim();
      // xg 的 cascader 选项文本是全路径(如 北京市/市辖区/大兴区),按段末级匹配
      if (!visEl(e) || (t !== text && t.slice(-text.length - 1) !== '/' + text)) continue;
      // van-cascader 这类组件把点击 handler 绑在内部叶子上,优先点叶子(事件向上冒泡,通用)
      var target = e;
      var stack = [e];
      while (stack.length) {
        var cur = stack.pop();
        for (var j = 0; j < cur.children.length; j++) {
          var c = cur.children[j];
          if (c.children.length === 0 && (c.innerText || '').trim() === text) target = c;
          else stack.push(c);
        }
      }
      target.click();
      return true;
    }
    return false;
  }

  function runTasks(tasks) {
    if (!tasks.length) return;
    var task = tasks.shift();
    clickLeafText(task.cap); // 点开字段弹窗
    var idx = 0;
    var ticks = 0;
    var iv = setInterval(function () {
      ticks++;
      var sel = task.sheet ? '.van-action-sheet__item' : '.van-cascader__option';
      if (clickItem(sel, task.path[idx])) {
        idx++;
        if (idx >= task.path.length) {
          clearInterval(iv);
          setTimeout(function () {
            // 树选择可能还需要在底部点「确定」收尾
            var btns = document.querySelectorAll('.xg-tree-pop-foot *, .van-popup button, .van-popup span');
            for (var i = 0; i < btns.length; i++) {
              var t = (btns[i].innerText || '').trim();
              if (visEl(btns[i]) && (t === '确定' || t === '确认')) { btns[i].click(); break; }
            }
            runTasks(tasks);
          }, 450);
        }
      } else if (ticks > 40) { // 约 14s 没找到选项,放弃该字段继续下一个
        clearInterval(iv);
        var ov = document.querySelector('.van-overlay');
        if (ov) ov.click();
        setTimeout(function () { runTasks(tasks); }, 400);
      }
    }, 350);
  }

  window.__tbFill = function (json) {
    var vm = formVm();
    if (!vm) return 'novm';
    var saved = null;
    try { saved = JSON.parse(json); } catch (e) {}
    if (!saved) return 'nodata';
    Object.keys(saved).forEach(function (k) {
      if (SKIP[k] || REPLAY_SKIP[k]) return;
      var val = saved[k];
      if (val === null || val === undefined || val === '') return;
      vm.${'$'}set(vm.formValue, k, val);
    });
    var tasks = [];
    if (saved.QJXZ_DISPLAY) tasks.push({cap: '请假性质', path: [saved.QJXZ_DISPLAY], sheet: true});
    if (saved.QJLX_DISPLAY) tasks.push({cap: '请假类型', path: [saved.QJLX_DISPLAY], sheet: true});
    var lx = saved.LXQX_DISPLAY || saved.LXQX;
    if (lx) tasks.push({cap: '所去地点', path: String(lx).split('/'), sheet: false});
    runTasks(tasks);
    return 'ok';
  };

  // 顺手删掉 WebVPN 注入的水印 div(fixed + 高 z-index + 不响应点击),
  // 它会显著拖慢日期组件等弹窗的渲染
  function killWatermark() {
    var kids = document.body ? document.body.children : [];
    for (var i = kids.length - 1; i >= 0; i--) {
      var el = kids[i];
      if (!el || el.tagName !== 'DIV') continue;
      var cs = getComputedStyle(el);
      if (cs.position === 'fixed' && cs.pointerEvents === 'none' &&
          parseInt(cs.zIndex || '0', 10) >= 10000) {
        el.parentNode.removeChild(el);
      }
    }
  }

  setInterval(function () {
    var hash = location.hash || '';
    tickCn(hash);
    killWatermark();
    patchMobUtil();
    if (window.TbQuickFill) {
      try { window.TbQuickFill.setFormPage(hash.indexOf('/xzqjsq') >= 0); } catch (e) {}
    }
  }, 500);
})();
"""
    }
}
