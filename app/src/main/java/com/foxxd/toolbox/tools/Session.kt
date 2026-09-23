// Session.kt — 进程内的「已通过统一身份认证」标记
package com.foxxd.toolbox.tools

/**
 * 只有开屏登录真正通过统一身份认证后才置位。
 * 只存在内存里：进程被杀 / 应用重启后自动失效，下次启动必须重新认证，
 * 保证「认证通过才给进去用」。
 */
object Session {
    @Volatile
    var authenticated: Boolean = false

    /** 本次登录用的学号，方便各处展示 */
    @Volatile
    var xh: String = ""

    /**
     * 本次登录用的密码，**只放内存**。
     * 开屏登录时用户不勾「记住密码」就不落盘，但同一次会话里各工具还得能用，
     * 所以留一份内存副本，进程结束即消失。
     */
    @Volatile
    private var password: String? = null

    fun pass(xh: String, pwd: String) {
        this.xh = xh
        password = pwd
        authenticated = true
    }

    fun passwordOrNull(): String? = password

    /** 退出登录：清掉内存里的认证状态与密码副本（磁盘上的凭据由调用方负责清） */
    fun logout() {
        authenticated = false
        xh = ""
        password = null
    }
}
