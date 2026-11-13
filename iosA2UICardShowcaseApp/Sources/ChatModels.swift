import UIKit

// MARK: - Chat message model

/// Everything that can appear in the AI Chat transcript.
/// We keep a flat enum so the table-view can drive its heights from this alone.
enum ChatMessage {
    /// A user text bubble (right-aligned).
    case user(text: String)
    /// A plain AI text bubble (left-aligned). May be "streaming" for the
    /// typewriter effect used while the "reply" is being generated.
    case aiText(text: String, streaming: Bool)
    /// Scene 1 — diet plan card (Tabs + List).
    case dietCard(DietPlan)
    /// Scene 2 — drink order card (list + quantity + buy button).
    case drinkCard(DrinkMenu)
    /// Scene 3 — short hint banner that will auto-navigate to the album page.
    case albumHint(text: String)
    /// An ephemeral "AI is thinking…" row shown before the real card lands.
    case typing
}

// MARK: - Scene 1: diet plan

struct DietPlan {
    struct Meal {
        let name: String        // e.g. "燕麦藜麦碗"
        let detail: String      // e.g. "牛奶燕麦 + 蓝莓 + 水煮蛋"
        let kcal: Int           // e.g. 320
        let tag: String         // e.g. "高蛋白"
    }
    struct Day {
        let weekday: String     // "周一"
        let highlight: String   // One-line summary for the tab header.
        let meals: [(slot: String, meal: Meal)]  // (早餐/午餐/晚餐, Meal)
    }
    let title: String
    let subtitle: String
    let days: [Day]
}

// MARK: - Scene 2: drink order

struct DrinkMenu {
    struct Drink {
        let name: String        // "无糖茉莉花茶"
        let desc: String        // "0糖0卡·高山茉莉"
        let price: Double       // 15.0
        let kcal: Int           // 0
        let tag: String         // "0糖推荐"
        let emoji: String       // "🍵"
    }
    let shop: String
    let notice: String
    let drinks: [Drink]
}

// MARK: - Scene 3: album photo

struct AlbumPhoto {
    let title: String
    let location: String
    let date: String
    /// Pre-rendered thumbnail (procedurally generated, no external assets).
    let image: UIImage
}

// MARK: - Mock content factory

/// Static source of all demo content — keeps the view-controllers concise.
enum MockContent {

    // Scene 1 ----------------------------------------------------------------

    static let dietPlan = DietPlan(
        title: "一周减脂食谱",
        subtitle: "日均 1400 kcal · 高蛋白 · 均衡搭配",
        days: [
            .init(weekday: "周一", highlight: "开启日 · 清爽入门",
                  meals: [
                    ("早餐", .init(name: "燕麦藜麦碗",
                                   detail: "低脂牛奶 · 蓝莓 · 水煮蛋",
                                   kcal: 320, tag: "高蛋白")),
                    ("午餐", .init(name: "鸡胸肉沙拉",
                                   detail: "牛油果 · 圣女果 · 橄榄油",
                                   kcal: 460, tag: "低脂")),
                    ("晚餐", .init(name: "清蒸鲈鱼糙米饭",
                                   detail: "鲈鱼 · 西兰花 · 糙米",
                                   kcal: 520, tag: "优质蛋白")),
                  ]),
            .init(weekday: "周二", highlight: "补铁日 · 元气满满",
                  meals: [
                    ("早餐", .init(name: "全麦三明治",
                                   detail: "鸡蛋 · 生菜 · 低脂芝士",
                                   kcal: 340, tag: "慢碳水")),
                    ("午餐", .init(name: "藜麦牛肉碗",
                                   detail: "瘦牛肉 · 藜麦 · 菠菜",
                                   kcal: 490, tag: "补铁")),
                    ("晚餐", .init(name: "番茄龙利鱼",
                                   detail: "龙利鱼 · 番茄 · 菌菇汤",
                                   kcal: 450, tag: "低卡")),
                  ]),
            .init(weekday: "周三", highlight: "清肠日 · 多蔬果",
                  meals: [
                    ("早餐", .init(name: "希腊酸奶杯",
                                   detail: "无糖酸奶 · 奇亚籽 · 香蕉",
                                   kcal: 300, tag: "益生菌")),
                    ("午餐", .init(name: "虾仁蔬菜意面",
                                   detail: "全麦意面 · 虾仁 · 时蔬",
                                   kcal: 470, tag: "低GI")),
                    ("晚餐", .init(name: "杂菇豆腐煲",
                                   detail: "北豆腐 · 口蘑 · 油菜",
                                   kcal: 410, tag: "植物蛋白")),
                  ]),
            .init(weekday: "周四", highlight: "增肌日 · 蛋白加倍",
                  meals: [
                    ("早餐", .init(name: "蛋白煎饼",
                                   detail: "蛋清 · 燕麦粉 · 蓝莓酱",
                                   kcal: 360, tag: "高蛋白")),
                    ("午餐", .init(name: "香煎鸡腿排",
                                   detail: "去皮鸡腿 · 彩椒 · 糙米",
                                   kcal: 520, tag: "增肌")),
                    ("晚餐", .init(name: "番茄鸡蛋面",
                                   detail: "番茄 · 鸡蛋 · 小白菜",
                                   kcal: 430, tag: "舒胃")),
                  ]),
            .init(weekday: "周五", highlight: "低碳日 · 控糖",
                  meals: [
                    ("早餐", .init(name: "牛油果鸡蛋盘",
                                   detail: "牛油果 · 水煮蛋 · 小番茄",
                                   kcal: 340, tag: "健康脂肪")),
                    ("午餐", .init(name: "香菇鸡丝汤面",
                                   detail: "鸡胸 · 荞麦面 · 香菇",
                                   kcal: 450, tag: "控糖")),
                    ("晚餐", .init(name: "芦笋三文鱼",
                                   detail: "三文鱼 · 芦笋 · 柠檬",
                                   kcal: 500, tag: "Omega-3")),
                  ]),
            .init(weekday: "周六", highlight: "放纵日 · 轻松一餐",
                  meals: [
                    ("早餐", .init(name: "可可燕麦杯",
                                   detail: "无糖可可 · 燕麦 · 坚果",
                                   kcal: 360, tag: "慢碳水")),
                    ("午餐", .init(name: "寿司拼盘",
                                   detail: "三文鱼 · 金枪鱼 · 鳗鱼",
                                   kcal: 540, tag: "美味奖励")),
                    ("晚餐", .init(name: "清汤番茄锅",
                                   detail: "番茄锅 · 肥牛 · 时蔬",
                                   kcal: 520, tag: "暖胃")),
                  ]),
            .init(weekday: "周日", highlight: "休整日 · 蔬食轻断",
                  meals: [
                    ("早餐", .init(name: "蔬果思慕雪",
                                   detail: "菠菜 · 苹果 · 香蕉",
                                   kcal: 280, tag: "维C")),
                    ("午餐", .init(name: "鹰嘴豆沙拉",
                                   detail: "鹰嘴豆 · 藜麦 · 牛油果",
                                   kcal: 440, tag: "植物蛋白")),
                    ("晚餐", .init(name: "蒸蛋羹配杂粮粥",
                                   detail: "蒸蛋 · 小米 · 红薯",
                                   kcal: 380, tag: "易消化")),
                  ]),
        ]
    )

    // Scene 2 ----------------------------------------------------------------

    static let drinkMenu = DrinkMenu(
        shop: "喜小茶 · 国贸店",
        notice: "已为你筛选 0 糖 / 低卡选项，预计 25 分钟送达",
        drinks: [
            .init(name: "无糖茉莉花茶",
                  desc: "0糖0卡·高山茉莉·冷萃24h",
                  price: 15.0, kcal: 0,  tag: "0糖推荐", emoji: "🍵"),
            .init(name: "柠檬美式",
                  desc: "0糖·西西里柠檬·萃取18s",
                  price: 18.0, kcal: 5,  tag: "醒神",     emoji: "🍋"),
            .init(name: "无糖乌龙鲜奶",
                  desc: "0糖·乌龙茶底·2%低脂奶",
                  price: 19.0, kcal: 85, tag: "低卡",     emoji: "🧋"),
            .init(name: "无糖桃桃绿",
                  desc: "0糖·水蜜桃果肉·清爽绿茶",
                  price: 17.0, kcal: 30, tag: "果香",     emoji: "🍑"),
            .init(name: "气泡乌龙",
                  desc: "0糖·微气泡·回甘乌龙",
                  price: 16.0, kcal: 0,  tag: "0糖推荐", emoji: "🧋"),
        ]
    )

    // Scene 3 ----------------------------------------------------------------

    /// 7 procedurally drawn photos that represent "National Day travel" shots.
    static let albumPhotos: [AlbumPhoto] = [
        AlbumPhoto(title: "西湖断桥",       location: "杭州 · 西湖",   date: "10-01",
                   image: PhotoFactory.make(.lakeSunset,  caption: "西湖 · 10.1")),
        AlbumPhoto(title: "海边日落",       location: "青岛 · 栈桥",   date: "10-02",
                   image: PhotoFactory.make(.seaside,     caption: "青岛 · 10.2")),
        AlbumPhoto(title: "山间清晨",       location: "黄山 · 光明顶", date: "10-03",
                   image: PhotoFactory.make(.mountain,    caption: "黄山 · 10.3")),
        AlbumPhoto(title: "古城夜色",       location: "西安 · 钟楼",   date: "10-04",
                   image: PhotoFactory.make(.cityNight,   caption: "西安 · 10.4")),
        AlbumPhoto(title: "麦田与风车",     location: "呼伦贝尔",     date: "10-05",
                   image: PhotoFactory.make(.grassland,   caption: "草原 · 10.5")),
        AlbumPhoto(title: "美食日记",       location: "成都 · 宽窄巷子", date: "10-06",
                   image: PhotoFactory.make(.foodie,      caption: "成都 · 10.6")),
        AlbumPhoto(title: "返程的高铁",     location: "返程途中",     date: "10-07",
                   image: PhotoFactory.make(.train,       caption: "返程 · 10.7")),
    ]

    // Chat suggestion chips --------------------------------------------------

    static let suggestionPrompts: [String] = [
        "规划这一周的减肥食谱清单",
        "点一杯无糖茶饮",
        "看下我的相册里十一出行的照片",
    ]
}
