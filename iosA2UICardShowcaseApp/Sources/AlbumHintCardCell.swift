import UIKit

/// Scene 3 — short hint banner, shown as an AI reply right before the chat
/// view-controller auto-navigates to the album screen.
final class AlbumHintCardCell: UITableViewCell {

    static let reuseID = "AlbumHintCardCell"

    private let avatar = UILabel()
    private let card = UIView()
    private let icon = UILabel()
    private let messageLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)

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

        card.translatesAutoresizingMaskIntoConstraints = false
        // A gentle green/teal banner so it reads as a confirmation.
        card.backgroundColor = UIColor(hex: 0xE8F7F4)
        card.layer.cornerRadius = 16
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor(hex: 0xA6E2D6).cgColor
        contentView.addSubview(card)

        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.text = "🖼️"
        icon.font = .systemFont(ofSize: 20)

        messageLabel.translatesAutoresizingMaskIntoConstraints = false
        messageLabel.font = .systemFont(ofSize: 14, weight: .medium)
        messageLabel.textColor = UIColor(hex: 0x1F6D5F)
        messageLabel.numberOfLines = 0

        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.color = UIColor(hex: 0x2BB9A5)
        spinner.startAnimating()

        [icon, messageLabel, spinner].forEach { card.addSubview($0) }

        NSLayoutConstraint.activate([
            avatar.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            avatar.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            avatar.widthAnchor.constraint(equalToConstant: 32),
            avatar.heightAnchor.constraint(equalToConstant: 32),

            card.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            card.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10),
            card.leadingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 8),
            card.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),

            icon.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            icon.centerYAnchor.constraint(equalTo: card.centerYAnchor),

            messageLabel.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 10),
            messageLabel.topAnchor.constraint(equalTo: card.topAnchor, constant: 14),
            messageLabel.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -14),
            messageLabel.trailingAnchor.constraint(equalTo: spinner.leadingAnchor, constant: -10),

            spinner.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),
            spinner.centerYAnchor.constraint(equalTo: card.centerYAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func configure(text: String) {
        messageLabel.text = text
    }
}
