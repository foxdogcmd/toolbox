// Credentials.kt — 各工具共享的教务账号凭据（学号 + Keystore 加密的密码）
package com.foxxd.toolbox.tools

import android.content.Context
import com.foxxd.toolbox.tools.grades.PasswordStore

object Credentials {

    private const val PREFS = "grades"

    fun xh(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("xh", "") ?: ""

    /**
     * 优先取落盘的密码；没落盘时退回本次会话的内存副本
     * （开屏登录时没勾「记住密码」，同一次会话里各工具仍然可用）。
     */
    fun password(context: Context): String? =
        PasswordStore.load(context) ?: Session.passwordOrNull()

    fun has(context: Context): Boolean =
        xh(context).isNotEmpty() && password(context) != null

    fun save(context: Context, xh: String, password: String, remember: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("xh", xh).apply()
        if (remember) PasswordStore.save(context, password) else PasswordStore.clear(context)
    }
}
