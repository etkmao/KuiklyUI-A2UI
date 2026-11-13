import UIKit

/// Global visual theme for the Card Showcase demo app.
/// Keep this tiny and flat so individual views don't recompute colors.
enum AppTheme {

    // MARK: - Palette (WeChat-inspired, soft & clean)

    /// Primary brand / action color — a calm, modern indigo-blue.
    static let primary      = UIColor(hex: 0x3F7CFF)
    static let primarySoft  = UIColor(hex: 0xEAF1FF)

    /// Accent used for highlights & checked states.
    static let accent       = UIColor(hex: 0x07C160)   // WeChat green

    /// Backgrounds — chat uses WeChat-style warm grey.
    static let background   = UIColor(hex: 0xEDEDED)
    static let cardBg       = UIColor.white
    static let subtleBg     = UIColor(hex: 0xF4F6FA)
    static let dividerColor = UIColor(hex: 0xE6E8EC)

    /// Soft tinted banners that sit at the top of cards.
    static let cardBanner   = UIColor(hex: 0xF3F7FF)
    static let cardBorder   = UIColor(hex: 0xE3E6EC)

    /// Text.
    static let textPrimary   = UIColor(hex: 0x1F2A44)
    static let textSecondary = UIColor(hex: 0x6E7A94)
    static let textTertiary  = UIColor(hex: 0x9AA5BE)

    /// Message bubbles — WeChat look.
    static let userBubble    = UIColor(hex: 0x95EC69)   // WeChat green bubble
    static let userBubbleTxt = UIColor(hex: 0x111111)
    static let aiBubble      = UIColor.white
    static let aiBubbleTxt   = UIColor(hex: 0x1F2A44)

    /// Tag chip colors (category -> fg/bg). Kept pastel for a soft feel.
    static let tagColors: [(bg: UIColor, fg: UIColor)] = [
        (UIColor(hex: 0xEAF1FF), UIColor(hex: 0x3F7CFF)),
        (UIColor(hex: 0xFFF0E8), UIColor(hex: 0xFF7A45)),
        (UIColor(hex: 0xE8F7F4), UIColor(hex: 0x2BB9A5)),
        (UIColor(hex: 0xFCE8F3), UIColor(hex: 0xD6336C)),
        (UIColor(hex: 0xF3ECFF), UIColor(hex: 0x7A4DFF)),
    ]
}

// MARK: - Convenience

extension UIColor {
    /// Build a UIColor from a 0xRRGGBB integer literal.
    convenience init(hex: UInt32, alpha: CGFloat = 1.0) {
        let r = CGFloat((hex >> 16) & 0xFF) / 255.0
        let g = CGFloat((hex >>  8) & 0xFF) / 255.0
        let b = CGFloat( hex        & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }
}

extension UIView {
    /// Apply a subtle, cleaner shadow — small radius so cards look crisp
    /// (not glowy), paired with a 1px light border.
    func applyCardShadow(radius: CGFloat = 6, opacity: Float = 0.05) {
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = opacity
        layer.shadowRadius = radius
        layer.shadowOffset = CGSize(width: 0, height: 2)
    }
}

/// A chat-bubble shape with one "tail" corner flattened (like WeChat).
/// `tailOnLeft = true`  -> AI bubble (tail at top-left)
/// `tailOnLeft = false` -> user bubble (tail at top-right)
final class BubbleView: UIView {

    var tailOnLeft: Bool = true { didSet { setNeedsLayout() } }
    var cornerRadius: CGFloat = 12 { didSet { setNeedsLayout() } }
    var tailCornerRadius: CGFloat = 3 { didSet { setNeedsLayout() } }

    private let shape = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.mask = shape
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let r = cornerRadius
        let tr = tailCornerRadius
        let b = bounds
        let path = UIBezierPath()

        if tailOnLeft {
            // top-left tail (small radius), others normal.
            path.move(to: CGPoint(x: tr, y: 0))
            path.addLine(to: CGPoint(x: b.width - r, y: 0))
            path.addArc(withCenter: CGPoint(x: b.width - r, y: r),
                        radius: r, startAngle: -.pi/2, endAngle: 0, clockwise: true)
            path.addLine(to: CGPoint(x: b.width, y: b.height - r))
            path.addArc(withCenter: CGPoint(x: b.width - r, y: b.height - r),
                        radius: r, startAngle: 0, endAngle: .pi/2, clockwise: true)
            path.addLine(to: CGPoint(x: r, y: b.height))
            path.addArc(withCenter: CGPoint(x: r, y: b.height - r),
                        radius: r, startAngle: .pi/2, endAngle: .pi, clockwise: true)
            path.addLine(to: CGPoint(x: 0, y: tr))
            path.addArc(withCenter: CGPoint(x: tr, y: tr),
                        radius: tr, startAngle: .pi, endAngle: -.pi/2, clockwise: true)
        } else {
            // top-right tail.
            path.move(to: CGPoint(x: r, y: 0))
            path.addLine(to: CGPoint(x: b.width - tr, y: 0))
            path.addArc(withCenter: CGPoint(x: b.width - tr, y: tr),
                        radius: tr, startAngle: -.pi/2, endAngle: 0, clockwise: true)
            path.addLine(to: CGPoint(x: b.width, y: b.height - r))
            path.addArc(withCenter: CGPoint(x: b.width - r, y: b.height - r),
                        radius: r, startAngle: 0, endAngle: .pi/2, clockwise: true)
            path.addLine(to: CGPoint(x: r, y: b.height))
            path.addArc(withCenter: CGPoint(x: r, y: b.height - r),
                        radius: r, startAngle: .pi/2, endAngle: .pi, clockwise: true)
            path.addLine(to: CGPoint(x: 0, y: r))
            path.addArc(withCenter: CGPoint(x: r, y: r),
                        radius: r, startAngle: .pi, endAngle: -.pi/2, clockwise: true)
        }
        path.close()
        shape.path = path.cgPath
        shape.frame = bounds
    }
}
