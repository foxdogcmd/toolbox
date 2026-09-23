// FirstRunNotice.kt — 首次进入时弹一次「数据都在本机 + 免责声明」
//
// 以前这两段是直接铺在登录页上的，占屏又每次都要看。改成弹窗、只弹一次；
// 之后想看可以走主界面菜单里的「免责声明」。
package com.foxxd.toolbox

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object FirstRunNotice {

    private const val PREFS = "app"
    private const val KEY = "notice_shown_v1"

    /** 只在第一次调用时弹；登录页和主界面都调用它，谁先出现谁弹，合计只弹一次 */
    fun showIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        // 先置位再弹，避免配置变更等导致弹两次
        prefs.edit().putBoolean(KEY, true).apply()
        MaterialAlertDialogBuilder(activity)
            .setTitle("使用须知")
            .setMessage(
                activity.getString(R.string.local_data_title) + "\n" +
                    activity.getString(R.string.local_data_text) + "\n\n" +
                    activity.getString(R.string.disclaimer_title) + "\n" +
                    activity.getString(R.string.disclaimer_text)
            )
            .setPositiveButton("我已知悉", null)
            .setCancelable(false)
            .show()
    }

    /** 供调试/测试用：清掉「已弹过」标记 */
    fun reset(activity: Activity) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
