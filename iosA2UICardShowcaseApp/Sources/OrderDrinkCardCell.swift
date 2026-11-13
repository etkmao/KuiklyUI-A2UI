import UIKit

/// Scene 2 — "点一杯无糖茶饮" card.
///
/// Layout:
///   ┌─ card ─────────────────────────────┐
///   │  [header: shop + notice]           │
///   │  [list of selectable drinks with   │
///   │   radio + emoji + name + price]    │
///   │  [quantity stepper]                │
///   │  [primary CTA "立即下单 ¥XX"]      │
///   └────────────────────────────────────┘
final class OrderDrinkCardCell: UITableViewCell {

    static let reuseID = "OrderDrinkCardCell"

    /// Fired when the user taps the "立即下单" button. Parent VC is expected
    /// to push a checkout screen.
    var onBuy: ((DrinkMenu.Drink, Int) -> Void)?

    // Data & state
    private var menu: DrinkMenu?
    private var selectedIndex = 0
    private var quantity = 1

    // Views
    private let card = UIView()
    private let avatar = UILabel()
    private let shopLabel = UILabel()
    private let noticeLabel = UILabel()
    private let listStack = UIStackView()
    private let qtyLabel = UILabel()
    private let qtyMinus = UIButton(type: .system)
    private let qtyPlus  = UIButton(type: .system)
    private let qtyValue = UILabel()
    private let buyButton = UIButton(type: .system)

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

        // Header
        let headerIcon = UILabel()
        headerIcon.text = "🧋"
        headerIcon.font = .systemFont(ofSize: 28)
        headerIcon.translatesAutoresizingMaskIntoConstraints = false

        shopLabel.translatesAutoresizingMaskIntoConstraints = false
        shopLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        shopLabel.textColor = AppTheme.textPrimary

        noticeLabel.translatesAutoresizingMaskIntoConstraints = false
        noticeLabel.font = .systemFont(ofSize: 12)
        noticeLabel.textColor = AppTheme.textSecondary
        noticeLabel.numberOfLines = 2

        let headerV = UIStackView(arrangedSubviews: [shopLabel, noticeLabel])
        headerV.axis = .vertical
        headerV.spacing = 4
        let header = UIStackView(arrangedSubviews: [headerIcon, headerV])
        header.translatesAutoresizingMaskIntoConstraints = false
        header.alignment = .center
        header.spacing = 10
        card.addSubview(header)

        // List
        listStack.translatesAutoresizingMaskIntoConstraints = false
        listStack.axis = .vertical
        listStack.spacing = 10
        card.addSubview(listStack)

        // Quantity row
        qtyLabel.translatesAutoresizingMaskIntoConstraints = false
        qtyLabel.font = .systemFont(ofSize: 14, weight: .medium)
        qtyLabel.textColor = AppTheme.textPrimary
        qtyLabel.text = "数量"

        qtyMinus.setTitle("−", for: .normal)
        qtyPlus.setTitle("+", for: .normal)
        [qtyMinus, qtyPlus].forEach { b in
            b.translatesAutoresizingMaskIntoConstraints = false
            b.backgroundColor = AppTheme.subtleBg
            b.setTitleColor(AppTheme.primary, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 20, weight: .semibold)
            b.layer.cornerRadius = 15
            b.widthAnchor.constraint(equalToConstant: 30).isActive = true
            b.heightAnchor.constraint(equalToConstant: 30).isActive = true
        }
        qtyMinus.addTarget(self, action: #selector(onMinus), for: .touchUpInside)
        qtyPlus.addTarget(self, action: #selector(onPlus), for: .touchUpInside)

        qtyValue.translatesAutoresizingMaskIntoConstraints = false
        qtyValue.font = .systemFont(ofSize: 15, weight: .semibold)
        qtyValue.textColor = AppTheme.textPrimary
        qtyValue.textAlignment = .center
        qtyValue.widthAnchor.constraint(equalToConstant: 30).isActive = true

        let qtyRow = UIStackView(arrangedSubviews: [qtyLabel, UIView(), qtyMinus, qtyValue, qtyPlus])
        qtyRow.translatesAutoresizingMaskIntoConstraints = false
        qtyRow.axis = .horizontal
        qtyRow.alignment = .center
        qtyRow.spacing = 8
        card.addSubview(qtyRow)

        // Buy button
        buyButton.translatesAutoresizingMaskIntoConstraints = false
        buyButton.backgroundColor = AppTheme.primary
        buyButton.setTitleColor(.white, for: .normal)
        buyButton.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        buyButton.layer.cornerRadius = 24
        buyButton.addTarget(self, action: #selector(onBuyTap), for: .touchUpInside)
        card.addSubview(buyButton)

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

            listStack.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 14),
            listStack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            listStack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),

            qtyRow.topAnchor.constraint(equalTo: listStack.bottomAnchor, constant: 14),
            qtyRow.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            qtyRow.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            qtyRow.heightAnchor.constraint(equalToConstant: 34),

            buyButton.topAnchor.constraint(equalTo: qtyRow.bottomAnchor, constant: 12),
            buyButton.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            buyButton.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            buyButton.heightAnchor.constraint(equalToConstant: 48),
            buyButton.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
        ])
    }

    // MARK: - Config

    func configure(menu: DrinkMenu) {
        self.menu = menu
        shopLabel.text = menu.shop
        noticeLabel.text = menu.notice
        selectedIndex = 0
        quantity = 1
        rebuildList()
        refreshBuyButton()
    }

    private func rebuildList() {
        listStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        guard let menu else { return }
        for (i, drink) in menu.drinks.enumerated() {
            listStack.addArrangedSubview(makeDrinkRow(drink, index: i))
        }
    }

    private func makeDrinkRow(_ drink: DrinkMenu.Drink, index: Int) -> UIView {
        let row = UIControl()
        row.translatesAutoresizingMaskIntoConstraints = false
        row.heightAnchor.constraint(equalToConstant: 64).isActive = true
        row.backgroundColor = (index == selectedIndex) ? AppTheme.primarySoft : AppTheme.subtleBg
        row.layer.cornerRadius = 14
        row.layer.borderWidth = 1
        row.layer.borderColor = (index == selectedIndex
                                 ? AppTheme.primary.withAlphaComponent(0.4).cgColor
                                 : UIColor.clear.cgColor)
        row.tag = index
        row.addTarget(self, action: #selector(onSelectDrink(_:)), for: .touchUpInside)

        // Radio ring
        let ring = UIView()
        ring.translatesAutoresizingMaskIntoConstraints = false
        ring.layer.borderWidth = 1.5
        ring.layer.borderColor = (index == selectedIndex
                                  ? AppTheme.primary.cgColor
                                  : AppTheme.textTertiary.cgColor)
        ring.layer.cornerRadius = 9
        row.addSubview(ring)
        if index == selectedIndex {
            let dot = UIView()
            dot.translatesAutoresizingMaskIntoConstraints = false
            dot.backgroundColor = AppTheme.primary
            dot.layer.cornerRadius = 4
            ring.addSubview(dot)
            NSLayoutConstraint.activate([
                dot.centerXAnchor.constraint(equalTo: ring.centerXAnchor),
                dot.centerYAnchor.constraint(equalTo: ring.centerYAnchor),
                dot.widthAnchor.constraint(equalToConstant: 8),
                dot.heightAnchor.constraint(equalToConstant: 8),
            ])
        }

        let emoji = UILabel()
        emoji.text = drink.emoji
        emoji.font = .systemFont(ofSize: 28)
        emoji.translatesAutoresizingMaskIntoConstraints = false

        let name = UILabel()
        name.text = drink.name
        name.font = .systemFont(ofSize: 15, weight: .semibold)
        name.textColor = AppTheme.textPrimary
        name.translatesAutoresizingMaskIntoConstraints = false

        let desc = UILabel()
        desc.text = drink.desc
        desc.font = .systemFont(ofSize: 11)
        desc.textColor = AppTheme.textSecondary
        desc.translatesAutoresizingMaskIntoConstraints = false

        let price = UILabel()
        price.text = String(format: "¥ %.0f", drink.price)
        price.font = .systemFont(ofSize: 15, weight: .semibold)
        price.textColor = AppTheme.primary
        price.translatesAutoresizingMaskIntoConstraints = false

        [ring, emoji, name, desc, price].forEach { row.addSubview($0) }

        NSLayoutConstraint.activate([
            ring.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 12),
            ring.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            ring.widthAnchor.constraint(equalToConstant: 18),
            ring.heightAnchor.constraint(equalToConstant: 18),

            emoji.leadingAnchor.constraint(equalTo: ring.trailingAnchor, constant: 8),
            emoji.centerYAnchor.constraint(equalTo: row.centerYAnchor),

            name.leadingAnchor.constraint(equalTo: emoji.trailingAnchor, constant: 10),
            name.topAnchor.constraint(equalTo: row.topAnchor, constant: 12),
            name.trailingAnchor.constraint(lessThanOrEqualTo: price.leadingAnchor, constant: -8),

            desc.leadingAnchor.constraint(equalTo: name.leadingAnchor),
            desc.topAnchor.constraint(equalTo: name.bottomAnchor, constant: 2),
            desc.trailingAnchor.constraint(lessThanOrEqualTo: price.leadingAnchor, constant: -8),

            price.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -14),
            price.centerYAnchor.constraint(equalTo: row.centerYAnchor),
        ])
        return row
    }

    // MARK: - Actions

    @objc private func onSelectDrink(_ sender: UIControl) {
        selectedIndex = sender.tag
        rebuildList()
        refreshBuyButton()
    }

    @objc private func onMinus() {
        quantity = max(1, quantity - 1)
        refreshBuyButton()
    }
    @objc private func onPlus() {
        quantity = min(9, quantity + 1)
        refreshBuyButton()
    }

    @objc private func onBuyTap() {
        guard let menu, selectedIndex < menu.drinks.count else { return }
        onBuy?(menu.drinks[selectedIndex], quantity)
    }

    private func refreshBuyButton() {
        qtyValue.text = "\(quantity)"
        guard let menu, selectedIndex < menu.drinks.count else { return }
        let drink = menu.drinks[selectedIndex]
        let total = drink.price * Double(quantity)
        buyButton.setTitle(String(format: "立即下单  ¥ %.0f", total), for: .normal)
    }
}
