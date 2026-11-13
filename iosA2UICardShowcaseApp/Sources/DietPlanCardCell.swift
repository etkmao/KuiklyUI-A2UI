import UIKit

/// Scene 1 — "一周减脂食谱" card.
///
/// Layout mirrors the typical A2UI card shape:
///   ┌─ card ─────────────────────────────┐
///   │  [header]                          │
///   │  [Tab: 周一 周二 周三 … 周日]      │
///   │  ────────────────────────────────  │
///   │  [List of meals for the selected   │
///   │   day — 早餐 / 午餐 / 晚餐 rows]   │
///   │  [footer summary]                  │
///   └────────────────────────────────────┘
final class DietPlanCardCell: UITableViewCell {

    static let reuseID = "DietPlanCardCell"

    // Data & state
    private var plan: DietPlan?
    private var selectedDay = 0

    // Views
    private let card = UIView()
    private let avatar = UILabel()
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let tabScroll = UIScrollView()
    private let tabStack = UIStackView()
    private let divider = UIView()
    private let listStack = UIStackView()
    private let footerLabel = UILabel()
    private let highlightLabel = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        setupCard()
    }
    required init?(coder: NSCoder) { fatalError() }

    // MARK: - Setup

    private func setupCard() {
        card.translatesAutoresizingMaskIntoConstraints = false
        card.backgroundColor = AppTheme.cardBg
        card.layer.cornerRadius = 20
        card.applyCardShadow()
        contentView.addSubview(card)

        avatar.translatesAutoresizingMaskIntoConstraints = false
        avatar.text = "✨"
        avatar.textAlignment = .center
        avatar.font = .systemFont(ofSize: 20)
        avatar.backgroundColor = AppTheme.primarySoft
        avatar.layer.cornerRadius = 16
        avatar.layer.masksToBounds = true
        contentView.addSubview(avatar)

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.textColor = AppTheme.textPrimary

        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.font = .systemFont(ofSize: 12)
        subtitleLabel.textColor = AppTheme.textSecondary

        let headerIcon = UILabel()
        headerIcon.text = "🥗"
        headerIcon.font = .systemFont(ofSize: 28)
        headerIcon.translatesAutoresizingMaskIntoConstraints = false

        let headerV = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        headerV.axis = .vertical
        headerV.spacing = 4
        let header = UIStackView(arrangedSubviews: [headerIcon, headerV])
        header.translatesAutoresizingMaskIntoConstraints = false
        header.alignment = .center
        header.spacing = 10
        card.addSubview(header)

        // Tab bar.
        tabScroll.translatesAutoresizingMaskIntoConstraints = false
        tabScroll.showsHorizontalScrollIndicator = false
        card.addSubview(tabScroll)
        tabStack.translatesAutoresizingMaskIntoConstraints = false
        tabStack.axis = .horizontal
        tabStack.spacing = 6
        tabScroll.addSubview(tabStack)

        highlightLabel.translatesAutoresizingMaskIntoConstraints = false
        highlightLabel.font = .systemFont(ofSize: 13, weight: .medium)
        highlightLabel.textColor = AppTheme.primary
        card.addSubview(highlightLabel)

        divider.translatesAutoresizingMaskIntoConstraints = false
        divider.backgroundColor = AppTheme.dividerColor
        card.addSubview(divider)

        listStack.translatesAutoresizingMaskIntoConstraints = false
        listStack.axis = .vertical
        listStack.spacing = 12
        card.addSubview(listStack)

        footerLabel.translatesAutoresizingMaskIntoConstraints = false
        footerLabel.font = .systemFont(ofSize: 12)
        footerLabel.textColor = AppTheme.textTertiary
        footerLabel.numberOfLines = 0
        card.addSubview(footerLabel)

        NSLayoutConstraint.activate([
            avatar.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            avatar.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            avatar.widthAnchor.constraint(equalToConstant: 32),
            avatar.heightAnchor.constraint(equalToConstant: 32),

            card.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            card.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10),
            card.leadingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 8),
            card.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),

            header.topAnchor.constraint(equalTo: card.topAnchor, constant: 16),
            header.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            header.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),

            tabScroll.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 14),
            tabScroll.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            tabScroll.trailingAnchor.constraint(equalTo: card.trailingAnchor),
            tabScroll.heightAnchor.constraint(equalToConstant: 34),

            tabStack.topAnchor.constraint(equalTo: tabScroll.topAnchor),
            tabStack.bottomAnchor.constraint(equalTo: tabScroll.bottomAnchor),
            tabStack.leadingAnchor.constraint(equalTo: tabScroll.leadingAnchor, constant: 14),
            tabStack.trailingAnchor.constraint(equalTo: tabScroll.trailingAnchor, constant: -14),
            tabStack.heightAnchor.constraint(equalTo: tabScroll.heightAnchor),

            highlightLabel.topAnchor.constraint(equalTo: tabScroll.bottomAnchor, constant: 8),
            highlightLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            highlightLabel.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),

            divider.topAnchor.constraint(equalTo: highlightLabel.bottomAnchor, constant: 10),
            divider.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            divider.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            divider.heightAnchor.constraint(equalToConstant: 0.5),

            listStack.topAnchor.constraint(equalTo: divider.bottomAnchor, constant: 12),
            listStack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            listStack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),

            footerLabel.topAnchor.constraint(equalTo: listStack.bottomAnchor, constant: 12),
            footerLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            footerLabel.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            footerLabel.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
        ])
    }

    // MARK: - Config

    func configure(plan: DietPlan) {
        self.plan = plan
        titleLabel.text = plan.title
        subtitleLabel.text = plan.subtitle
        buildTabs()
        selectDay(0)
    }

    private func buildTabs() {
        tabStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        guard let plan else { return }
        for (i, d) in plan.days.enumerated() {
            let b = UIButton(type: .system)
            b.setTitle(d.weekday, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
            b.contentEdgeInsets = UIEdgeInsets(top: 4, left: 14, bottom: 4, right: 14)
            b.layer.cornerRadius = 15
            b.tag = i
            b.addTarget(self, action: #selector(onTab(_:)), for: .touchUpInside)
            tabStack.addArrangedSubview(b)
        }
        refreshTabStyles()
    }

    @objc private func onTab(_ sender: UIButton) {
        selectDay(sender.tag)
    }

    private func selectDay(_ idx: Int) {
        guard let plan, idx < plan.days.count else { return }
        selectedDay = idx
        refreshTabStyles()
        let day = plan.days[idx]
        highlightLabel.text = "\(day.weekday) · \(day.highlight)"
        buildMeals(for: day)

        let total = day.meals.reduce(0) { $0 + $1.meal.kcal }
        footerLabel.text = "今日总热量约 \(total) kcal · 轻松达成减脂目标 💪"
    }

    private func refreshTabStyles() {
        for case let b as UIButton in tabStack.arrangedSubviews {
            let on = (b.tag == selectedDay)
            b.backgroundColor = on ? AppTheme.primary : AppTheme.subtleBg
            b.setTitleColor(on ? .white : AppTheme.textSecondary, for: .normal)
        }
    }

    private func buildMeals(for day: DietPlan.Day) {
        listStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (slot, meal) in day.meals {
            listStack.addArrangedSubview(makeMealRow(slot: slot, meal: meal))
        }
    }

    private func makeMealRow(slot: String, meal: DietPlan.Meal) -> UIView {
        let row = UIView()

        // Slot badge: 早餐/午餐/晚餐.
        let slotBadge = UILabel()
        slotBadge.text = "  \(slot)  "
        slotBadge.font = .systemFont(ofSize: 11, weight: .semibold)
        slotBadge.textColor = AppTheme.primary
        slotBadge.backgroundColor = AppTheme.primarySoft
        slotBadge.layer.cornerRadius = 10
        slotBadge.layer.masksToBounds = true
        slotBadge.translatesAutoresizingMaskIntoConstraints = false

        let name = UILabel()
        name.text = meal.name
        name.font = .systemFont(ofSize: 15, weight: .semibold)
        name.textColor = AppTheme.textPrimary
        name.translatesAutoresizingMaskIntoConstraints = false

        let detail = UILabel()
        detail.text = meal.detail
        detail.font = .systemFont(ofSize: 13)
        detail.textColor = AppTheme.textSecondary
        detail.numberOfLines = 2
        detail.translatesAutoresizingMaskIntoConstraints = false

        let tag = makePastelChip(text: meal.tag, index: slot.hashValue)
        let kcal = UILabel()
        kcal.text = "\(meal.kcal) kcal"
        kcal.font = .systemFont(ofSize: 12, weight: .medium)
        kcal.textColor = AppTheme.textTertiary
        kcal.translatesAutoresizingMaskIntoConstraints = false

        let tagsRow = UIStackView(arrangedSubviews: [tag, kcal])
        tagsRow.translatesAutoresizingMaskIntoConstraints = false
        tagsRow.spacing = 8
        tagsRow.alignment = .center

        [slotBadge, name, detail, tagsRow].forEach { row.addSubview($0) }

        NSLayoutConstraint.activate([
            slotBadge.topAnchor.constraint(equalTo: row.topAnchor, constant: 2),
            slotBadge.leadingAnchor.constraint(equalTo: row.leadingAnchor),
            slotBadge.heightAnchor.constraint(equalToConstant: 20),

            name.topAnchor.constraint(equalTo: row.topAnchor),
            name.leadingAnchor.constraint(equalTo: slotBadge.trailingAnchor, constant: 8),
            name.trailingAnchor.constraint(lessThanOrEqualTo: row.trailingAnchor),

            detail.topAnchor.constraint(equalTo: name.bottomAnchor, constant: 4),
            detail.leadingAnchor.constraint(equalTo: name.leadingAnchor),
            detail.trailingAnchor.constraint(equalTo: row.trailingAnchor),

            tagsRow.topAnchor.constraint(equalTo: detail.bottomAnchor, constant: 6),
            tagsRow.leadingAnchor.constraint(equalTo: name.leadingAnchor),
            tagsRow.bottomAnchor.constraint(equalTo: row.bottomAnchor),
        ])

        return row
    }

    private func makePastelChip(text: String, index: Int) -> UIView {
        let palette = AppTheme.tagColors[abs(index) % AppTheme.tagColors.count]
        let l = PaddedLabel()
        l.text = text
        l.font = .systemFont(ofSize: 11, weight: .medium)
        l.textColor = palette.fg
        l.backgroundColor = palette.bg
        l.layer.cornerRadius = 9
        l.layer.masksToBounds = true
        l.padding = UIEdgeInsets(top: 3, left: 8, bottom: 3, right: 8)
        return l
    }
}

// MARK: - Small helper: UILabel with built-in padding.

final class PaddedLabel: UILabel {
    var padding = UIEdgeInsets.zero
    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: padding))
    }
    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + padding.left + padding.right,
                      height: s.height + padding.top + padding.bottom)
    }
}
