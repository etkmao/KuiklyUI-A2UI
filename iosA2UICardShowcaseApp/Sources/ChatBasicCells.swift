import UIKit

// MARK: - Suggestion chips strip above the input bar

final class SuggestionBar: UIView {

    var prompts: [String] = [] { didSet { reload() } }
    var onTap: ((String) -> Void)?

    private let scroll = UIScrollView()
    private let stack  = UIStackView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = AppTheme.background

        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.contentInset = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
        addSubview(scroll)

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.spacing = 8
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: topAnchor),
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),

            stack.topAnchor.constraint(equalTo: scroll.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.trailingAnchor),
            stack.heightAnchor.constraint(equalTo: scroll.heightAnchor,
                                          constant: -16),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    private func reload() {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, text) in prompts.enumerated() {
            let b = UIButton(type: .system)
            b.setTitle("✦ \(text)", for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
            b.setTitleColor(AppTheme.primary, for: .normal)
            b.backgroundColor = AppTheme.primarySoft
            b.contentEdgeInsets = UIEdgeInsets(top: 6, left: 14, bottom: 6, right: 14)
            b.layer.cornerRadius = 14
            b.tag = i
            b.addTarget(self, action: #selector(tap(_:)), for: .touchUpInside)
            stack.addArrangedSubview(b)
        }
    }

    @objc private func tap(_ sender: UIButton) {
        guard sender.tag < prompts.count else { return }
        onTap?(prompts[sender.tag])
    }
}

// MARK: - User message cell (right-aligned blue pill)

final class UserMessageCell: UITableViewCell {
    static let reuseID = "UserMessageCell"

    private let bubble = UIView()
    private let label = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        bubble.translatesAutoresizingMaskIntoConstraints = false
        bubble.backgroundColor = AppTheme.userBubble
        bubble.layer.cornerRadius = 18
        contentView.addSubview(bubble)

        label.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 0
        label.textColor = AppTheme.userBubbleTxt
        label.font = .systemFont(ofSize: 15)
        bubble.addSubview(label)

        NSLayoutConstraint.activate([
            bubble.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            bubble.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
            bubble.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            bubble.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 60),

            label.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -10),
            label.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 14),
            label.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -14),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func configure(text: String) { label.text = text }
}

// MARK: - AI plain-text cell (left-aligned white pill, with avatar)

final class AITextMessageCell: UITableViewCell {
    static let reuseID = "AITextMessageCell"

    private let avatar = UILabel()
    private let bubble = UIView()
    private let label = UILabel()
    private let cursor = UIView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        avatar.translatesAutoresizingMaskIntoConstraints = false
        avatar.text = "✨"
        avatar.textAlignment = .center
        avatar.font = .systemFont(ofSize: 20)
        avatar.backgroundColor = AppTheme.primarySoft
        avatar.layer.cornerRadius = 16
        avatar.layer.masksToBounds = true
        contentView.addSubview(avatar)

        bubble.translatesAutoresizingMaskIntoConstraints = false
        bubble.backgroundColor = AppTheme.aiBubble
        bubble.layer.cornerRadius = 18
        bubble.layer.borderWidth = 1
        bubble.layer.borderColor = AppTheme.dividerColor.cgColor
        contentView.addSubview(bubble)

        label.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 0
        label.textColor = AppTheme.aiBubbleTxt
        label.font = .systemFont(ofSize: 15)
        bubble.addSubview(label)

        cursor.translatesAutoresizingMaskIntoConstraints = false
        cursor.backgroundColor = AppTheme.primary
        cursor.layer.cornerRadius = 1.5
        cursor.isHidden = true
        bubble.addSubview(cursor)

        NSLayoutConstraint.activate([
            avatar.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            avatar.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            avatar.widthAnchor.constraint(equalToConstant: 32),
            avatar.heightAnchor.constraint(equalToConstant: 32),

            bubble.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            bubble.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
            bubble.leadingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 8),
            bubble.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -60),

            label.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -10),
            label.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 14),
            label.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -14),

            cursor.widthAnchor.constraint(equalToConstant: 3),
            cursor.heightAnchor.constraint(equalToConstant: 16),
            cursor.leadingAnchor.constraint(equalTo: label.trailingAnchor, constant: 2),
            cursor.bottomAnchor.constraint(equalTo: label.bottomAnchor, constant: -1),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func configure(text: String, streaming: Bool) {
        label.text = text
        cursor.isHidden = !streaming
        if streaming {
            cursor.alpha = 1
            UIView.animate(withDuration: 0.55,
                           delay: 0,
                           options: [.repeat, .autoreverse, .curveEaseInOut]) {
                self.cursor.alpha = 0.1
            }
        } else {
            cursor.layer.removeAllAnimations()
        }
    }
}

// MARK: - Typing indicator cell ("● ● ●")

final class TypingIndicatorCell: UITableViewCell {
    static let reuseID = "TypingIndicatorCell"

    private let avatar = UILabel()
    private let bubble = UIView()
    private let dots: [UIView] = (0..<3).map { _ in UIView() }

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        avatar.translatesAutoresizingMaskIntoConstraints = false
        avatar.text = "✨"
        avatar.textAlignment = .center
        avatar.font = .systemFont(ofSize: 20)
        avatar.backgroundColor = AppTheme.primarySoft
        avatar.layer.cornerRadius = 16
        avatar.layer.masksToBounds = true
        contentView.addSubview(avatar)

        bubble.translatesAutoresizingMaskIntoConstraints = false
        bubble.backgroundColor = AppTheme.aiBubble
        bubble.layer.cornerRadius = 18
        bubble.layer.borderWidth = 1
        bubble.layer.borderColor = AppTheme.dividerColor.cgColor
        contentView.addSubview(bubble)

        let stack = UIStackView(arrangedSubviews: dots)
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.spacing = 6
        stack.alignment = .center
        bubble.addSubview(stack)
        dots.forEach {
            $0.backgroundColor = AppTheme.textTertiary
            $0.layer.cornerRadius = 3
            $0.translatesAutoresizingMaskIntoConstraints = false
            $0.widthAnchor.constraint(equalToConstant: 6).isActive = true
            $0.heightAnchor.constraint(equalToConstant: 6).isActive = true
        }

        NSLayoutConstraint.activate([
            avatar.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            avatar.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            avatar.widthAnchor.constraint(equalToConstant: 32),
            avatar.heightAnchor.constraint(equalToConstant: 32),

            bubble.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            bubble.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
            bubble.leadingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 8),
            bubble.widthAnchor.constraint(equalToConstant: 64),
            bubble.heightAnchor.constraint(equalToConstant: 32),

            stack.centerXAnchor.constraint(equalTo: bubble.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: bubble.centerYAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    /// Starts the "bouncing dots" animation. Safe to call every time
    /// the cell is dequeued.
    func start() {
        for (i, d) in dots.enumerated() {
            d.layer.removeAllAnimations()
            d.alpha = 0.3
            UIView.animate(withDuration: 0.6,
                           delay: Double(i) * 0.15,
                           options: [.repeat, .autoreverse, .curveEaseInOut]) {
                d.alpha = 1.0
            }
        }
    }
}
