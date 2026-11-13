# A2UI Card Showcase (独立 iOS Demo)

一个**完全独立**的 iOS App，用来演示 A2UI 风格 AI Chat 卡片的三个关键使用场景，供录屏/期望效果展示。

> 这个工程**不依赖** A2UI、KuiklyUI 或任何 CocoaPods，纯 UIKit + 本地 Mock 数据，`xcodegen` 一键生成工程后即可运行。

---

## 快速开始

```bash
# 安装 xcodegen（如未安装）
brew install xcodegen

# 在项目目录下一键生成 .xcodeproj
cd iosA2UICardShowcaseApp
./setup.sh

# 打开工程并运行
open A2UICardShowcaseApp.xcodeproj
```

- iOS 15.0+
- Swift 5.0
- 仅 iPhone

---

## 三个演示场景

全部发生在首页 **AI Chat** 聊天界面，输入框上方有对应的 3 条「建议问题」，可一键触发。

### 场景 1 · 规划这一周的减肥食谱清单
- 输入：`规划这一周的减肥食谱清单`
- AI 实时流式回复文本，随后出一张卡片：
  - 卡片头部「一周减脂食谱 / 日均 1400 kcal …」
  - **Tab 组件**：周一～周日，可切换
  - **List 组件**：当前日的 早餐 / 午餐 / 晚餐，含名称、描述、彩色标签 chip、热量
  - 底部总热量汇总

### 场景 2 · 点一杯无糖茶饮
- 输入：`点一杯无糖茶饮`
- AI 回复一张「点单卡片」：
  - 店铺信息 + 配送说明
  - 5 款 0 糖/低卡茶饮可单选（radio + emoji + 名称 + 价格）
  - 数量 +/-
  - 底部「立即下单 ¥XX」CTA
- 点击「立即下单」→ 跳转 **结账页面**（地址 / 商品 / 支付方式 / 金额汇总）→ 支付成功弹窗返回聊天。

### 场景 3 · 看下我的相册里十一出行的照片
- 输入：`看下我的相册里十一出行的照片`
- AI 回复流式文字后，出现「找到 7 张照片，正在打开相册…」横条，自动跳转到 **相册页**：
  - 顶部大标题「十一出行 / AI 智能相册」
  - 3 列照片网格（照片均由 Core Graphics 运行时生成，无外部图片资源）
  - 点击任意照片进入详情页

---

## 工程结构

```
iosA2UICardShowcaseApp/
├─ project.yml                     # XcodeGen 定义，无任何 pod 依赖
├─ setup.sh                        # 一键生成 Xcode 工程
├─ Resources/
│  ├─ Info.plist
│  ├─ LaunchScreen.storyboard      # 简洁的启动页
│  └─ Assets.xcassets/
└─ Sources/
   ├─ AppDelegate.swift            # App 入口 → ChatViewController
   ├─ AppTheme.swift               # 统一主题色/阴影/标签色板
   ├─ ChatModels.swift             # 消息枚举 + 场景数据模型 + MockContent
   ├─ ChatViewController.swift     # 聊天主界面（输入+建议+流式回复）
   ├─ ChatBasicCells.swift         # 用户气泡/AI 气泡/Typing 指示器/建议 chips
   ├─ DietPlanCardCell.swift       # 场景 1 卡片（Tab + List）
   ├─ OrderDrinkCardCell.swift     # 场景 2 卡片（单选 + 数量 + CTA）
   ├─ CheckoutViewController.swift # 场景 2 结账页
   ├─ AlbumHintCardCell.swift      # 场景 3 提示横条
   ├─ AlbumViewController.swift    # 场景 3 相册页 + 照片详情
   └─ PhotoFactory.swift           # Core Graphics 生成相册占位图
```

---

## 视觉风格

- 主色：柔和的 indigo-blue `#3F7CFF`，背景 `#F5F8FC`
- 卡片：纯白 + 20pt 圆角 + 淡阴影，边距与行高宽裕
- 标签：浅色 pastel 系 chip
- 流式打字：AI 气泡字符逐个显现 + 末尾闪烁光标
- 相册：进场轻微缩放+弹跳动画，适合录屏呈现

整体保证「简洁、清亮、舒服」的观感，适合直接做 1~2 分钟的效果展示录屏。
