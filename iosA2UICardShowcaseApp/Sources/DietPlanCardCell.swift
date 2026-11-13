import UIKit

/// Scene 1 — "一周减脂食谱" card.
///
/// Layout mirrors the typical A2UI card shape:
///   ┌─ card ─────────────────────────────┐
///   │  [banner header (icon + title)]    │
///   │  [Tab: 周一 周二 … 周日  ← scroll] │
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
    private let avatar = UILabel()
    private let card = UIView()
    private let banner = UIView()
    private let headerIcon = UILabel()
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let tabScroll = UIScrollView()
    private let tabStack = UIStackView()
    private let highlightLabel = UILabel()
    private let divider = UIView()
    private let listStack = UIStackView()
    private let footerLabel = UILabel()

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
        avatar.translatesAutoresizingMaskIntoConstraints = false
        avatar.text = "✨"
        avatar.textAlignment = .center
        avatar.font = .systemFont(ofSize: 20)
        avatar.backgroundColor = AppTheme.primarySoft
        avatar.layer.cornerRadius = 6
        avatar.layer.masksToBounds = true
        contentView.addSubview(avatar)

        card.translatesAutoresizingMaskIntoConstraints = false
        card.backgroundColor = AppTheme.cardBg
        card.layer.cornerRadius = 14
        card.layer.borderWidth = 1
        card.layer.borderColor = AppTheme.cardBorder.cgColor
        card.layer.masksToBounds = true
        contentView.addSubview(card)

        // ── Banner header ───────────────────────────────────────────────
        banner.translatesAutoresizingMaskIntoConstraints = false
        banner.backgroundColor = AppTheme.cardBanner
        card.addSubview(banner)

        headerIcon.translatesAutoresizingMaskIntoConstraints = false
        headerIcon.text = "🥗"
        headerIcon.font = .systemFont(ofSize: 24)
        headerIcon.textAlignment = .center
        headerIcon.backgroundColor = .white
        headerIcon.layer.cornerRadius = 8
        headerIcon.layer.masksToBounds = true
        headerIcon.layer.borderWidth = 1
        headerIcon.layer.borderColor = AppTheme.cardBorder.cgColor
        banner.addSubview(headerIcon)

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        titleLabel.textColor = AppTheme.textPrimary
        banner.addSubview(titleLabel)

        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.font = .systemFont(ofSize: 12)
        subtitleLabel.textColor = AppTheme.textSecondary
        banner.addSubview(subtitleLabel)

        // ── Tab bar (horizontally scrollable, full-bleed) ───────────────
        tabScroll.translatesAutoresizingMaskIntoConstraints = false
        tabScroll.showsHorizontalScrollIndicator = false
        tabScroll.clipsToBounds = true
        card.addSubview(tabScroll)
        tabStack.translatesAutoresizingMaskIntoConstraints = false
        tabStack.axis = .horizontal
        tabStack.spacing = 8
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
        listStack.spacing = 10
        card.addSubview(listStack)

        footerLabel.translatesAutoresizingMaskIntoConstraints = false
        footerLabel.font = .systemFont(ofSize: 12)
        footerLabel.textColor = AppTheme.textTertiary
        footerLabel.numberOfLines = 0
        card.addSubview(footerLabel)

        NSLayoutConstraint.activate([
            avatar.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            avatar.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            avatar.widthAnchor.constraint(equalToConstant: 34),
            avatar.heightAnchor.constraint(equalToConstant: 34),

            card.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            card.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10),
            card.leadingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 8),
            card.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -60),

            // Banner
            banner.topAnchor.constraint(equalTo: card.topAnchor),
            banner.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            banner.trailingAnchor.constraint(equalTo: card.trailingAnchor),

            headerIcon.leadingAnchor.constraint(equalTo: banner.leadingAnchor, constant: 14),
            headerIcon.topAnchor.constraint(equalTo: banner.topAnchor, constant: 12),
            headerIcon.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: -12),
            headerIcon.widthAnchor.constraint(equalToConstant: 36),
            headerIcon.heightAnchor.constraint(equalToConstant: 36),

            titleLabel.leadingAnchor.constraint(equalTo: headerIcon.trailingAnchor, constant: 10),
            titleLabel.topAnchor.constraint(equalTo: headerIcon.topAnchor, constant: -1),
            titleLabel.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -14),

            subtitleLabel.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            subtitleLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 2),
            subtitleLabel.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -14),

            // Tabs — scroll is full-bleed, stack is only constrained to
            // leading/top/bottom (NOT trailing) so it can grow horizontally.
            tabScroll.topAnchor.constraint(equalTo: banner.bottomAnchor, constant: 10),
            tabScroll.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            tabScroll.trailingAnchor.constraint(equalTo: card.trailingAnchor),
            tabScroll.heightAnchor.constraint(equalToConstant: 32),

            tabStack.topAnchor.constraint(equalTo: tabScroll.topAnchor),
            tabStack.bottomAnchor.constraint(equalTo: tabScroll.bottomAnchor),
            tabStack.leadingAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.leadingAnchor, constant: 14),
            tabStack.trailingAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.trailingAnchor, constant: -14),
            tabStack.heightAnchor.constraint(equalTo: tabScroll.heightAnchor),

            highlightLabel.topAnchor.constraint(equalTo: tabScroll.bottomAnchor, constant: 10),
            highlightLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            highlightLabel.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),

            divider.topAnchor.constraint(equalTo: highlightLabel.bottomAnchor, constant: 8),
            divider.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            divider.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),
            divider.heightAnchor.constraint(equalToConstant: 0.5),

            listStack.topAnchor.constraint(equalTo: divider.bottomAnchor, constant: 10),
            listStack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            listStack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),

            footerLabel.topAnchor.constraint(equalTo: listStack.bottomAnchor, constant: 10),
            footerLabel.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            footerLabel.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),
            footerLabel.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -14),
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
            b.contentEdgeInsets = UIEdgeInsets(top: 4, left: 12, bottom: 4, right: 12)
            b.layer.cornerRadius = 14
            b.tag = i
            b.addTarget(self, action: #selector(onTab(_:)), for: .touchUpInside)
            tabStack.addArrangedSubview(b)
        }
        refreshTabStyles()
    }

    @objc private func onTab(_ sender: UIButton) {
        selectDay(sender.tag)
        // Make sure the selected tab is visible (helpful for the last few).
        if let btn = tabStack.arrangedSubviews[safe: sender.tag] {
            let target = btn.convert(btn.bounds, to: tabScroll)
                            .insetBy(dx: -14, dy: 0)
            tabScroll.scrollRectToVisible(target, animated: true)
        }
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
            b.layer.borderWidth = on ? 0 : 1
            b.layer.borderColor = AppTheme.cardBorder.cgColor
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
        row.backgroundColor = AppTheme.subtleBg
        row.layer.cornerRadius = 10

        // Slot badge: 早餐/午餐/晚餐.
        let slotBadge = PaddedLabel()
        slotBadge.text = slot
        slotBadge.font = .systemFont(ofSize: 11, weight: .semibold)
        slotBadge.textColor = AppTheme.primary
        slotBadge.backgroundColor = AppTheme.primarySoft
        slotBadge.layer.cornerRadius = 9
        slotBadge.layer.masksToBounds = true
        slotBadge.padding = UIEdgeInsets(top: 3, left: 8, bottom: 3, right: 8)
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
            slotBadge.topAnchor.constraint(equalTo: row.topAnchor, constant: 12),
            slotBadge.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 12),
            slotBadge.heightAnchor.constraint(equalToConstant: 20),

            name.topAnchor.constraint(equalTo: row.topAnchor, constant: 10),
            name.leadingAnchor.constraint(equalTo: slotBadge.trailingAnchor, constant: 8),
            name.trailingAnchor.constraint(lessThanOrEqualTo: row.trailingAnchor, constant: -12),

            detail.topAnchor.constraint(equalTo: name.bottomAnchor, constant: 4),
            detail.leadingAnchor.constraint(equalTo: name.leadingAnchor),
            detail.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -12),

            tagsRow.topAnchor.constraint(equalTo: detail.bottomAnchor, constant: 8),
            tagsRow.leadingAnchor.constraint(equalTo: name.leadingAnchor),
            tagsRow.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -12),
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
        l.layer.cornerRadius = 8
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

// MARK: - Safe array subscript

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
