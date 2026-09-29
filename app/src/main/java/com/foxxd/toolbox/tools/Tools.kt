package com.foxxd.toolbox.tools

import android.app.Activity
import com.foxxd.toolbox.tools.calendar.CalendarActivity
import com.foxxd.toolbox.tools.emptyroom.EmptyRoomActivity
import com.foxxd.toolbox.tools.grades.GradesActivity
import com.foxxd.toolbox.tools.huping.HupingActivity
import com.foxxd.toolbox.tools.leave.LeaveActivity
import com.foxxd.toolbox.tools.leave.LeaveVueActivity
import com.foxxd.toolbox.tools.schedule.ScheduleActivity
import com.foxxd.toolbox.tools.webvpn.WebVpnActivity
import com.foxxd.toolbox.tools.ykt.YktActivity

/** 工具注册表：新增工具在这里加一行即可出现在首页列表 */
data class Tool(val name: String, val desc: String, val activity: Class<out Activity>)

val TOOLS = listOf(
    Tool("成绩查询", "教务系统成绩（含分项明细）", GradesActivity::class.java),
    Tool("我的课表", "按周查看课程安排", ScheduleActivity::class.java),
    Tool("校历", "学期周历与备注", CalendarActivity::class.java),
    Tool("请假系统（原生）", "填写关键信息后通过学校接口提交", LeaveActivity::class.java),
    Tool("请假系统（Vue）", "原版手机版请假页面", LeaveVueActivity::class.java),
    Tool("虚拟校园卡", "校园一卡通（付款码/余额/充值）", YktActivity::class.java),
    Tool("校内网站", "经 WebVPN 访问任意校内站点", WebVpnActivity::class.java),
    Tool("空教室", "按教学楼和时间查空闲教室", EmptyRoomActivity::class.java),
    Tool("班级互评", "综合测评互评打分（学工系统）", HupingActivity::class.java),
)
