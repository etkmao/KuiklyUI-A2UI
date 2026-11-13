import UIKit

/// Global visual theme for the Card Showcase demo app.
/// Keep this tiny and flat so individual views don't recompute colors.
enum AppTheme {

    // MARK: - Palette (soft, bright, modern)

    /// Primary brand / action color — a calm, modern indigo-blue.
    static let primary      = UIColor(hex: 0x3F7CFF)
    static let primarySoft  = UIColor(hex: 0xEAF1FF)

    /// Accent used for highlights & checked states.
    static let accent       = UIColor(hex: 0x4ECDC4)

    /// Backgrounds.
    static let background   = UIColor(hex: 0xF5F8FC)
    static let cardBg       = UIColor.white
    static let subtleBg     = UIColor(hex: 0xF2F5FA)
    static let dividerColor = UIColor(hex: 0xE6EAF2)

    /// Text.
    static let textPrimary   = UIColor(hex: 0x1F2A44)
    static let textSecondary = UIColor(hex: 0x6E7A94)
    static let textTertiary  = UIColor(hex: 0x9AA5BE)

    /// Message bubbles.
    static let userBubble    = UIColor(hex: 0x3F7CFF)
    static let userBubbleTxt = UIColor.white
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
    /// Apply a soft, elevated drop-shadow that reads well on light backgrounds.
    func applyCardShadow(radius: CGFloat = 16, opacity: Float = 0.06) {
        layer.shadowColor = UIColor(hex: 0x0B1E4B).cgColor
        layer.shadowOpacity = opacity
        layer.shadowRadius = radius
        layer.shadowOffset = CGSize(width: 0, height: 4)
    }
}
