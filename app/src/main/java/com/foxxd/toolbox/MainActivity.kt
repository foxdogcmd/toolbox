package com.foxxd.toolbox

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foxxd.toolbox.tools.TOOLS
import com.foxxd.toolbox.tools.Tool
import com.foxxd.toolbox.tools.Credentials
import com.foxxd.toolbox.tools.Session
import com.foxxd.toolbox.tools.grades.GradeClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须先在开屏过统一身份认证；否则踢回登录页
        if (!Session.authenticated) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        // 有存档直接放行时不会经过登录页，公告在这里补一次（全局只弹一次）
        FirstRunNotice.showIfNeeded(this)
        setContentView(R.layout.activity_main)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ToolAdapter(TOOLS) { tool ->
            startActivity(Intent(this, tool.activity))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_about -> {
                showAbout()
                return true
            }
            R.id.action_logout -> {
                confirmLogout()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** 免责声明 + 数据都在本机（内容和开屏登录页一致） */
    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.disclaimer_title))
            .setMessage(
                getString(R.string.local_data_title) + "\n" +
                    getString(R.string.local_data_text) + "\n\n" +
                    getString(R.string.disclaimer_title) + "\n" +
                    getString(R.string.disclaimer_text)
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 退出登录：清内存会话 + 磁盘凭据 + 会话 Cookie，回到开屏登录页 */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("退出登录")
            .setMessage(
                "将清除本机保存的学号与密码，并结束当前 WebVPN / 教务会话。\n\n" +
                    "下次打开应用需要重新登录（刚用过的一卡通等页面数据不会丢）。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                Credentials.save(this, "", "", remember = false)
                Session.logout()
                try {
                    GradeClient(GradeClient.DEFAULT_VPN_BASE, GradeClient.DEFAULT_PREFIX)
                        .clearSessions()
                } catch (e: Exception) {
                    // 清不掉也无所谓，内存里的认证状态已经没了
                }
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            }
            .show()
    }
}

private class ToolAdapter(
    private val tools: List<Tool>,
    private val onClick: (Tool) -> Unit,
) : RecyclerView.Adapter<ToolAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.toolName)
        val desc: TextView = view.findViewById(R.id.toolDesc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_tool, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tool = tools[position]
        holder.name.text = tool.name
        holder.desc.text = tool.desc
        holder.itemView.setOnClickListener { onClick(tool) }
    }

    override fun getItemCount() = tools.size
}
