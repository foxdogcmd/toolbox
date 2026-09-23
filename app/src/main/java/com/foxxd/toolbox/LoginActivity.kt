// LoginActivity.kt — 开屏统一身份认证登录（不通过不给进）
//
// 目的是把「认证」提到最前面：
//   1. 必须真的过一遍 CAS 统一认证（先清会话，避免旧 Cookie 让错密码蒙混过关）
//   2. 密码错误要明确指出，并提醒「连续输错 10 次账号会被暂时冻结」
//   3. 同屏给出免责声明与「数据都在本机」的说明
//
// 认证走 WebVPN（webvpn.ppsuc.edu.cn 的 CAS），校内外都可用。
package com.foxxd.toolbox

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.foxxd.toolbox.tools.Credentials
import com.foxxd.toolbox.tools.Session
import com.foxxd.toolbox.tools.grades.CasAuthException
import com.foxxd.toolbox.tools.grades.GradeClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class LoginActivity : AppCompatActivity() {

    private lateinit var xhInput: TextInputEditText
    private lateinit var pwdInput: TextInputEditText
    private lateinit var remember: CheckBox
    private lateinit var loginBtn: MaterialButton
    private lateinit var status: TextView

    @Volatile
    private var busy = false

    /** 本次打开应用期间的失败次数，用来在提示里加一句「已失败 N 次」 */
    private var fails = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.authenticated) {
            // 已认证过（例如从设置页返回）就不再重复登录
            goMain()
            return
        }
        // 有存档（学号 + 已加密保存的密码）就直接放行，不再要求重新登录；
        // 各工具自己会用这份凭据按需认证，WebVPN 会话还有效时连认证都会跳过。
        val archivedXh = Credentials.xh(this)
        val archivedPwd = Credentials.password(this)
        if (archivedXh.isNotEmpty() && archivedPwd != null) {
            Session.pass(archivedXh, archivedPwd)
            goMain()
            return
        }
        setContentView(R.layout.activity_login)

        xhInput = findViewById(R.id.lgXh)
        pwdInput = findViewById(R.id.lgPwd)
        remember = findViewById(R.id.lgRemember)
        loginBtn = findViewById(R.id.lgBtn)
        status = findViewById(R.id.lgStatus)

        xhInput.setText(Credentials.xh(this))

        loginBtn.setOnClickListener {
            login(
                xhInput.text.toString().trim(),
                pwdInput.text.toString(),
                remember.isChecked,
            )
        }

        status.text = "请用统一身份认证账号登录后使用"
        // 公告（数据都在本机 + 免责声明）以弹窗形式出现，且只出现一次
        FirstRunNotice.showIfNeeded(this)
    }

    private fun setStatus(msg: String) {
        status.visibility = View.VISIBLE
        status.text = msg
    }

    private fun login(xh: String, pwd: String, rememberPwd: Boolean) {
        if (busy) return
        if (xh.isEmpty() || pwd.isEmpty()) {
            setStatus("请填写学号和密码")
            MaterialAlertDialogBuilder(this)
                .setTitle("还没填完")
                .setMessage("学号和密码都要填才能登录。")
                .setPositiveButton("好", null)
                .show()
            return
        }
        busy = true
        loginBtn.isEnabled = false
        setStatus("正在通过统一身份认证登录…")

        Thread {
            var authError: String? = null
            var otherError: String? = null
            try {
                val client = GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.DEFAULT_PREFIX)
                // 先清掉旧会话：否则上一次的 Cookie 会让 casLogin 跳过认证，
                // 输错密码也能“登进去”
                client.clearSessions()
                client.loginWebVpn(xh, pwd)
                Credentials.save(this, xh, pwd, rememberPwd)
                Session.pass(xh, pwd)
            } catch (e: CasAuthException) {
                authError = e.message ?: "学号或密码错误"
            } catch (e: Exception) {
                otherError = e.message ?: "网络异常"
            }

            runOnUiThread {
                busy = false
                loginBtn.isEnabled = true
                when {
                    Session.authenticated -> goMain()
                    authError != null -> {
                        fails++
                        showAuthError(authError)
                    }
                    else -> showOtherError(otherError ?: "登录失败")
                }
            }
        }.start()
    }

    /** 密码/账号被 CAS 拒绝：明确说清楚，并带上「10 次冻结」的警告 */
    private fun showAuthError(reason: String) {
        setStatus("[-] 学号或密码错误")
        val extra = if (fails > 1) "\n\n本次打开应用已失败 $fails 次。" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("统一身份认证失败")
            .setMessage(
                "学号或密码错误。\n\n" +
                    "系统返回：$reason\n\n" +
                    FREEZE_WARNING + extra
            )
            .setPositiveButton("重新输入") { _, _ ->
                pwdInput.requestFocus()
                pwdInput.selectAll()
            }
            .show()
    }

    private fun showOtherError(msg: String) {
        setStatus("[-] $msg")
        MaterialAlertDialogBuilder(this)
            .setTitle("登录失败")
            .setMessage("$msg\n\n不是密码问题的话，多半是网络或学校系统暂时不可用，稍后重试即可。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val FREEZE_WARNING =
            "注意：统一身份认证密码连续输错 10 次，账号会被暂时冻结（锁定）。" +
                "请先确认密码再试；忘记密码请走学校统一身份认证的「忘记密码」渠道重置，不要反复试。"
    }
}
