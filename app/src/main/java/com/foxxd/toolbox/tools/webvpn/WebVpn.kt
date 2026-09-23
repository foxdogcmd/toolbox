// WebVpn.kt — WebVPN 转接地址编码
//
// wengine WebVPN 的地址格式：
//   {vpnBase}/{scheme}/77726476706e69737468656265737421{AES(host)hex}{path}
// 其中 7772...21 是 "wrdvpnisthebest!" 的 hex，
// host 用 AES-128-CFB(key=IV="wrdvpnisthebest!") 加密后取 hex。
// （已用 ykt8wxpay.ppsuc.edu.cn 的已知前缀验证过算法）
package com.foxxd.toolbox.tools.webvpn

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WebVpn {

    const val BASE = "https://webvpn.ppsuc.edu.cn"
    private const val HEADER = "77726476706e69737468656265737421" // hex("wrdvpnisthebest!")
    private val KEY = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)

    private fun encryptHost(host: String): String {
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(KEY, "AES"),
            IvParameterSpec(KEY),
        )
        return cipher.doFinal(host.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 把用户输入的校内地址转成 WebVPN 转接地址。
     * 接受：完整 URL、缺协议的 host/path。默认 https。
     */
    fun wrap(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return BASE
        if (!s.contains("://")) s = "https://$s"
        val m = Regex("^(https?)://([^/?#]+)(.*)$").find(s)
            ?: return BASE
        val (scheme, authority, rest) = m.destructured
        // 非标准端口没法简单编码（wengine 对端口另有规则），直接忽略端口
        val host = authority.substringBefore(":")
        val path = rest.ifEmpty { "/" }
        return "$BASE/$scheme/$HEADER${encryptHost(host)}$path"
    }
}
