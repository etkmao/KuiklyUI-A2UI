import UIKit

/// Scene 2 — checkout page pushed from the "立即下单" button.
///
/// This page is mocked:
///   • Shows a nice top summary with the chosen drink.
///   • Fake address block.
///   • Fake payment method rows.
///   • "确认支付" button triggers a success alert and pops back to chat.
final class CheckoutViewController: UIViewController {

    private let drink: DrinkMenu.Drink
    private let count: Int

    init(drink: DrinkMenu.Drink, count: Int) {
        self.drink = drink
        self.count = count
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "确认订单"
        view.backgroundColor = AppTheme.background

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.alwaysBounceVertical = true
        view.addSubview(scroll)

        let content = UIStackView()
        content.translatesAutoresizingMaskIntoConstraints = false
        content.axis = .vertical
        content.spacing = 16
        content.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        content.isLayoutMarginsRelativeArrangement = true
        scroll.addSubview(content)

        // Pay button (floating at bottom)
        let payBar = makePayBar()
        view.addSubview(payBar)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: payBar.topAnchor),

            content.topAnchor.constraint(equalTo: scroll.topAnchor),
            content.bottomAnchor.constraint(equalTo: scroll.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: scroll.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: scroll.trailingAnchor),
            content.widthAnchor.constraint(equalTo: scroll.widthAnchor),

            payBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            payBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            payBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            payBar.heightAnchor.constraint(equalToConstant: 92),
        ])

        // Address block
        content.addArrangedSubview(makeAddressCard())
        // Item block
        content.addArrangedSubview(makeItemCard())
        // Payment-method block
        content.addArrangedSubview(makePaymentCard())
        // Subtotal block
        content.addArrangedSubview(makeSummaryCard())
    }

    // MARK: - Cards

    private func cardContainer() -> UIStackView {
        let v = UIStackView()
        v.axis = .vertical
        v.spacing = 10
        v.backgroundColor = AppTheme.cardBg
        v.layer.cornerRadius = 16
        v.layoutMargins = UIEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        v.isLayoutMarginsRelativeArrangement = true
        v.applyCardShadow()
        return v
    }

    private func makeAddressCard() -> UIView {
        let card = cardContainer()
        let title = UILabel()
        title.text = "📍 配送地址"
        title.font = .systemFont(ofSize: 13)
        title.textColor = AppTheme.textSecondary

        let name = UILabel()
        name.text = "办公室 · 国贸大厦 A 座 32F"
        name.font = .systemFont(ofSize: 16, weight: .semibold)
        name.textColor = AppTheme.textPrimary

        let phone = UILabel()
        phone.text = "李先生  138-****-8812"
        phone.font = .systemFont(ofSize: 13)
        phone.textColor = AppTheme.textSecondary

        [title, name, phone].forEach { card.addArrangedSubview($0) }
        return card
    }

    private func makeItemCard() -> UIView {
        let card = cardContainer()
        let title = UILabel()
        title.text = "🧋 \(MockContent.drinkMenu.shop)"
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.textColor = AppTheme.textPrimary

        let row = UIStackView()
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 12

        let emoji = UILabel()
        emoji.text = drink.emoji
        emoji.font = .systemFont(ofSize: 40)
        emoji.textAlignment = .center
        emoji.backgroundColor = AppTheme.subtleBg
        emoji.layer.cornerRadius = 12
        emoji.layer.masksToBounds = true
        emoji.translatesAutoresizingMaskIntoConstraints = false
        emoji.widthAnchor.constraint(equalToConstant: 60).isActive = true
        emoji.heightAnchor.constraint(equalToConstant: 60).isActive = true

        let info = UIStackView()
        info.axis = .vertical
        info.spacing = 4
        let name = UILabel()
        name.text = drink.name
        name.font = .systemFont(ofSize: 15, weight: .semibold)
        name.textColor = AppTheme.textPrimary
        let desc = UILabel()
        desc.text = drink.desc
        desc.font = .systemFont(ofSize: 12)
        desc.textColor = AppTheme.textSecondary
        desc.numberOfLines = 2
        info.addArrangedSubview(name)
        info.addArrangedSubview(desc)

        let qty = UILabel()
        qty.text = "x\(count)"
        qty.font = .systemFont(ofSize: 15, weight: .semibold)
        qty.textColor = AppTheme.textSecondary

        row.addArrangedSubview(emoji)
        row.addArrangedSubview(info)
        row.addArrangedSubview(qty)

        card.addArrangedSubview(title)
        card.addArrangedSubview(row)
        return card
    }

    private func makePaymentCard() -> UIView {
        let card = cardContainer()
        let title = UILabel()
        title.text = "💳 支付方式"
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.textColor = AppTheme.textPrimary
        card.addArrangedSubview(title)

        func row(_ icon: String, _ name: String, _ selected: Bool) -> UIView {
            let r = UIView()
            let iconL = UILabel()
            iconL.text = icon
            iconL.font = .systemFont(ofSize: 20)
            iconL.translatesAutoresizingMaskIntoConstraints = false
            let nameL = UILabel()
            nameL.text = name
            nameL.font = .systemFont(ofSize: 14)
            nameL.textColor = AppTheme.textPrimary
            nameL.translatesAutoresizingMaskIntoConstraints = false
            let check = UILabel()
            check.text = selected ? "✓" : ""
            check.textColor = AppTheme.primary
            check.font = .systemFont(ofSize: 16, weight: .bold)
            check.translatesAutoresizingMaskIntoConstraints = false
            [iconL, nameL, check].forEach { r.addSubview($0) }
            NSLayoutConstraint.activate([
                iconL.leadingAnchor.constraint(equalTo: r.leadingAnchor),
                iconL.centerYAnchor.constraint(equalTo: r.centerYAnchor),
                nameL.leadingAnchor.constraint(equalTo: iconL.trailingAnchor, constant: 10),
                nameL.centerYAnchor.constraint(equalTo: r.centerYAnchor),
                check.trailingAnchor.constraint(equalTo: r.trailingAnchor),
                check.centerYAnchor.constraint(equalTo: r.centerYAnchor),
                r.heightAnchor.constraint(equalToConstant: 32),
            ])
            return r
        }
        card.addArrangedSubview(row("🟢", "微信支付", true))
        card.addArrangedSubview(row("🔵", "支付宝", false))
        card.addArrangedSubview(row("🟡", "银行卡 (**** 8812)", false))
        return card
    }

    private func makeSummaryCard() -> UIView {
        let card = cardContainer()
        let subtotal = drink.price * Double(count)
        let delivery = 3.0
        let discount = 2.0
        let total = subtotal + delivery - discount

        func line(_ k: String, _ v: String, highlight: Bool = false) -> UIView {
            let r = UIView()
            let kL = UILabel()
            kL.text = k
            kL.font = .systemFont(ofSize: highlight ? 15 : 13,
                                  weight: highlight ? .semibold : .regular)
            kL.textColor = highlight ? AppTheme.textPrimary : AppTheme.textSecondary
            kL.translatesAutoresizingMaskIntoConstraints = false
            let vL = UILabel()
            vL.text = v
            vL.font = .systemFont(ofSize: highlight ? 17 : 13,
                                  weight: highlight ? .bold : .regular)
            vL.textColor = highlight ? AppTheme.primary : AppTheme.textPrimary
            vL.translatesAutoresizingMaskIntoConstraints = false
            [kL, vL].forEach { r.addSubview($0) }
            NSLayoutConstraint.activate([
                kL.leadingAnchor.constraint(equalTo: r.leadingAnchor),
                kL.centerYAnchor.constraint(equalTo: r.centerYAnchor),
                vL.trailingAnchor.constraint(equalTo: r.trailingAnchor),
                vL.centerYAnchor.constraint(equalTo: r.centerYAnchor),
                r.heightAnchor.constraint(equalToConstant: 28),
            ])
            return r
        }
        card.addArrangedSubview(line("商品金额", String(format: "¥ %.0f", subtotal)))
        card.addArrangedSubview(line("配送费",    String(format: "¥ %.0f", delivery)))
        card.addArrangedSubview(line("优惠",      String(format: "- ¥ %.0f", discount)))
        let sep = UIView()
        sep.backgroundColor = AppTheme.dividerColor
        sep.translatesAutoresizingMaskIntoConstraints = false
        sep.heightAnchor.constraint(equalToConstant: 0.5).isActive = true
        card.addArrangedSubview(sep)
        card.addArrangedSubview(line("合计", String(format: "¥ %.0f", total), highlight: true))
        return card
    }

    private func makePayBar() -> UIView {
        let bar = UIView()
        bar.translatesAutoresizingMaskIntoConstraints = false
        bar.backgroundColor = .white
        bar.layer.shadowColor = UIColor.black.cgColor
        bar.layer.shadowOpacity = 0.05
        bar.layer.shadowOffset = CGSize(width: 0, height: -2)
        bar.layer.shadowRadius = 6

        let total = drink.price * Double(count) + 3.0 - 2.0

        let totalLabel = UILabel()
        totalLabel.translatesAutoresizingMaskIntoConstraints = false
        let txt = NSMutableAttributedString(
            string: "应付 ",
            attributes: [
                .font: UIFont.systemFont(ofSize: 13),
                .foregroundColor: AppTheme.textSecondary,
            ])
        txt.append(NSAttributedString(
            string: String(format: "¥ %.0f", total),
            attributes: [
                .font: UIFont.systemFont(ofSize: 22, weight: .bold),
                .foregroundColor: AppTheme.primary,
            ]))
        totalLabel.attributedText = txt

        let pay = UIButton(type: .system)
        pay.translatesAutoresizingMaskIntoConstraints = false
        pay.setTitle("确认支付", for: .normal)
        pay.setTitleColor(.white, for: .normal)
        pay.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        pay.backgroundColor = AppTheme.primary
        pay.layer.cornerRadius = 22
        pay.addTarget(self, action: #selector(onPay), for: .touchUpInside)

        bar.addSubview(totalLabel)
        bar.addSubview(pay)
        NSLayoutConstraint.activate([
            totalLabel.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 16),
            totalLabel.topAnchor.constraint(equalTo: bar.topAnchor, constant: 12),

            pay.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -16),
            pay.topAnchor.constraint(equalTo: bar.topAnchor, constant: 10),
            pay.widthAnchor.constraint(equalToConstant: 140),
            pay.heightAnchor.constraint(equalToConstant: 44),
        ])
        return bar
    }

    @objc private func onPay() {
        let alert = UIAlertController(title: "支付成功 🎉",
                                      message: "预计 25 分钟后送达，可以回去继续聊天啦～",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好的", style: .default) { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        })
        present(alert, animated: true)
    }
}
