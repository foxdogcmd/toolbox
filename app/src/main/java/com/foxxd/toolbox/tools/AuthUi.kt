// AuthUi.kt — 工具页共用的登录卡片逻辑（自动填充已保存凭据）
package com.foxxd.toolbox.tools

import android.app.Activity
import android.view.View
import android.widget.CheckBox
import com.foxxd.toolbox.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class AuthUi(
    activity: Activity,
    onSubmit: (xh: String, pwd: String) -> Unit,
) {
    private val card: View = activity.findViewById(R.id.loginCard)
    private val xhInput: TextInputEditText = activity.findViewById(R.id.xhInput)
    private val pwdInput: TextInputEditText = activity.findViewById(R.id.pwdInput)
    private val rememberPwd: CheckBox = activity.findViewById(R.id.rememberPwd)

    var lastError: String? = null

    init {
        xhInput.setText(Credentials.xh(activity))
        Credentials.password(activity)?.let {
            pwdInput.setText(it)
            rememberPwd.isChecked = true
        }
        activity.findViewById<MaterialButton>(R.id.loginBtn).setOnClickListener {
            val xh = xhInput.text.toString().trim()
            val pwd = pwdInput.text.toString()
            if (xh.isEmpty() || pwd.isEmpty()) return@setOnClickListener
            Credentials.save(activity, xh, pwd, rememberPwd.isChecked)
            onSubmit(xh, pwd)
        }
    }

    fun credentials(activity: Activity): Pair<String, String>? {
        val xh = Credentials.xh(activity)
        val pwd = Credentials.password(activity)
        return if (xh.isNotEmpty() && pwd != null) xh to pwd else null
    }

    fun show() {
        card.visibility = View.VISIBLE
    }

    fun hide() {
        card.visibility = View.GONE
    }
}
