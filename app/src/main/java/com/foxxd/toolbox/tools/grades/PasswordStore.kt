// PasswordStore.kt — 用 Android Keystore 加密保存密码（密钥不出安全硬件）
package com.foxxd.toolbox.tools.grades

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

object PasswordStore {

    private const val KEY_ALIAS = "toolbox_grades_pwd"
    private const val PREFS = "grades"

    private fun getOrCreateKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        gen.generateKey()
    }

    private fun cipher(opmode: Int, iv: ByteArray? = null): Cipher {
        getOrCreateKey()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(opmode, key) else init(opmode, key, GCMParameterSpec(128, iv))
        }
    }

    fun save(context: Context, password: String) {
        val c = cipher(Cipher.ENCRYPT_MODE)
        val ciphertext = c.doFinal(password.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pwd_enc", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("pwd_iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enc = prefs.getString("pwd_enc", null) ?: return null
        val iv = prefs.getString("pwd_iv", null) ?: return null
        return try {
            val c = cipher(Cipher.DECRYPT_MODE, Base64.decode(iv, Base64.NO_WRAP))
            String(c.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("pwd_enc").remove("pwd_iv").apply()
    }
}
