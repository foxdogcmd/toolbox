---
version: alpha
name: "PPSUC 工具箱"
description: "面向在校生的原生 Android 校园工具，沿用 Material 的清晰、克制的信息呈现。"
colors:
  primary: "#1565C0"
  primary-dark: "#0D47A1"
  secondary: "#00897B"
  background: "#F5F7FA"
  text: "#172B4D"
  muted-text: "#546E7A"
typography:
  sans:
    fontFamily: "Roboto, Noto Sans CJK SC, sans-serif"
rounded:
  DEFAULT: "12px"
spacing:
  screen-inset: "16px"
  control-gap: "8px"
components:
  button: {}
  card: {}
  input: {}
  dialog: {}
---

# PPSUC 工具箱设计约定

## Overview

面向手机上的短时、明确任务：查看校园信息或完成一次申请。现有应用以浅色 Material 界面、蓝色主操作和系统中文字体回退为基础。请假页沿用这套语言，优先保证字段、状态和提交结果容易辨认。此文件记录现有视觉选择；运行时以 `app/src/main/res/values/themes.xml`、对应布局和原生 Material 组件为准。

## Colors

主操作使用 `#1565C0`；次级选择用蓝色描边按钮，避免多个实心按钮争夺注意力。请假内容背景为 `#F5F7FA`，正文为 `#172B4D`，说明文字为 `#546E7A`。错误说明以文字表达，不单靠颜色。

## Typography

沿用 Android 系统字体与中文回退。页面标题 23sp，列表标题 17sp，正文和输入 14–16sp。日期时间保留完整数字格式，不使用全大写按钮文字。

## Layout

页面内边距 16dp，按钮之间留 8dp。表单使用系统滚动容器，在窄屏与横屏均允许整页滚动；不固定表单高度，也不遮挡系统键盘。

## Elevation & Depth

列表卡片使用轻微阴影，表单主要靠标题、间距与描边分组。加载或错误消息留在页面中，不用装饰性阴影表达状态。

## Shapes

列表卡片圆角 12dp；按钮和对话框跟随项目 Material 主题。

## Components

新建和最终提交是实心主按钮；返回、刷新、类型与时间选择为描边按钮。日期与时间使用 Android 原生选择器，系统负责焦点和无障碍语义。字段错误以可读提示显示；提交时禁用按钮，服务器确认后刷新列表。承诺书与最终提交使用原生对话框。
