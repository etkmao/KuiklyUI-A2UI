import UIKit

/// Procedurally generated "photo" thumbnails. This keeps the demo entirely
/// self-contained (no external image assets), while still letting us show a
/// realistic-looking album grid during the screen recording.
enum PhotoFactory {

    enum Kind {
        case lakeSunset     // 湖面日落
        case seaside        // 海边日落
        case mountain       // 山间清晨
        case cityNight      // 古城夜色
        case grassland      // 草原
        case foodie         // 美食
        case train          // 高铁
    }

    static func make(_ kind: Kind, caption: String) -> UIImage {
        let size = CGSize(width: 600, height: 600)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            let rect = CGRect(origin: .zero, size: size)
            drawBackground(for: kind, in: rect, ctx: ctx.cgContext)
            drawForeground(for: kind, in: rect, ctx: ctx.cgContext)
            drawCaptionBadge(caption, in: rect)
        }
    }

    // MARK: - Backgrounds (gradients)

    private static func drawBackground(for kind: Kind, in rect: CGRect, ctx: CGContext) {
        let colors: [UIColor]
        switch kind {
        case .lakeSunset:
            colors = [UIColor(hex: 0xFFB47A), UIColor(hex: 0xFF7E7E), UIColor(hex: 0x6D5BBE)]
        case .seaside:
            colors = [UIColor(hex: 0xFFD28A), UIColor(hex: 0xFF8E6E), UIColor(hex: 0x2A4D8F)]
        case .mountain:
            colors = [UIColor(hex: 0xC8E6FF), UIColor(hex: 0x7FB3E6), UIColor(hex: 0x3D6AA3)]
        case .cityNight:
            colors = [UIColor(hex: 0x3B2C5E), UIColor(hex: 0x1F1740), UIColor(hex: 0x0B0A2A)]
        case .grassland:
            colors = [UIColor(hex: 0xDFF2C1), UIColor(hex: 0x90C97E), UIColor(hex: 0x3F7A45)]
        case .foodie:
            colors = [UIColor(hex: 0xFFE2B4), UIColor(hex: 0xFFB37C), UIColor(hex: 0xC06A3B)]
        case .train:
            colors = [UIColor(hex: 0xDCE7FF), UIColor(hex: 0x8CA6E6), UIColor(hex: 0x3F5BA3)]
        }
        drawVerticalGradient(colors: colors, in: rect, ctx: ctx)
    }

    private static func drawVerticalGradient(colors: [UIColor], in rect: CGRect, ctx: CGContext) {
        let space = CGColorSpaceCreateDeviceRGB()
        let cg = colors.map { $0.cgColor } as CFArray
        let locs: [CGFloat] = stride(from: 0, through: 1, by: 1.0 / Double(colors.count - 1)).map { CGFloat($0) }
        if let grad = CGGradient(colorsSpace: space, colors: cg, locations: locs) {
            ctx.drawLinearGradient(grad,
                                   start: CGPoint(x: rect.midX, y: rect.minY),
                                   end:   CGPoint(x: rect.midX, y: rect.maxY),
                                   options: [])
        }
    }

    // MARK: - Foreground silhouettes (very stylized, flat shapes)

    private static func drawForeground(for kind: Kind, in rect: CGRect, ctx: CGContext) {
        switch kind {
        case .lakeSunset:
            drawSun(center: CGPoint(x: rect.midX, y: rect.midY - 40), radius: 70,
                    color: UIColor(white: 1, alpha: 0.85), ctx: ctx)
            drawWaterRipples(in: rect, ctx: ctx)
        case .seaside:
            drawSun(center: CGPoint(x: rect.midX + 80, y: rect.midY - 20), radius: 60,
                    color: UIColor(hex: 0xFFEBB0), ctx: ctx)
            drawWaterRipples(in: rect, ctx: ctx)
        case .mountain:
            drawMountains(in: rect, ctx: ctx)
            drawSun(center: CGPoint(x: rect.minX + 110, y: rect.minY + 130), radius: 45,
                    color: UIColor(white: 1, alpha: 0.9), ctx: ctx)
        case .cityNight:
            drawCityLights(in: rect, ctx: ctx)
        case .grassland:
            drawWindmill(in: rect, ctx: ctx)
        case .foodie:
            drawBowl(in: rect, ctx: ctx)
        case .train:
            drawTrain(in: rect, ctx: ctx)
        }
    }

    private static func drawSun(center: CGPoint, radius: CGFloat, color: UIColor, ctx: CGContext) {
        ctx.setFillColor(color.cgColor)
        ctx.addEllipse(in: CGRect(x: center.x - radius, y: center.y - radius,
                                  width: radius * 2, height: radius * 2))
        ctx.fillPath()
    }

    private static func drawWaterRipples(in rect: CGRect, ctx: CGContext) {
        ctx.setStrokeColor(UIColor(white: 1, alpha: 0.35).cgColor)
        ctx.setLineWidth(3)
        let baseY = rect.midY + 60
        for i in 0..<6 {
            let y = baseY + CGFloat(i) * 22
            ctx.move(to: CGPoint(x: rect.minX + 40, y: y))
            ctx.addLine(to: CGPoint(x: rect.maxX - 40, y: y))
            ctx.strokePath()
        }
    }

    private static func drawMountains(in rect: CGRect, ctx: CGContext) {
        let peaks: [(CGPoint, UIColor)] = [
            (CGPoint(x: rect.minX,            y: rect.maxY - 180), UIColor(hex: 0x3D6AA3).withAlphaComponent(0.85)),
            (CGPoint(x: rect.midX,            y: rect.maxY - 260), UIColor(hex: 0x27548A)),
            (CGPoint(x: rect.maxX - 80,       y: rect.maxY - 200), UIColor(hex: 0x1F3F6E)),
        ]
        for (peak, color) in peaks {
            ctx.setFillColor(color.cgColor)
            ctx.beginPath()
            ctx.move(to: CGPoint(x: peak.x - 220, y: rect.maxY))
            ctx.addLine(to: peak)
            ctx.addLine(to: CGPoint(x: peak.x + 220, y: rect.maxY))
            ctx.closePath()
            ctx.fillPath()
        }
    }

    private static func drawCityLights(in rect: CGRect, ctx: CGContext) {
        // skyline
        ctx.setFillColor(UIColor(hex: 0x05061A).cgColor)
        let base = rect.maxY - 180
        var x: CGFloat = rect.minX
        while x < rect.maxX {
            let w = CGFloat.random(in: 45...80)
            let h = CGFloat.random(in: 60...180)
            ctx.fill(CGRect(x: x, y: base - h + 180, width: w - 4, height: h + 4))
            x += w
        }
        // window dots
        ctx.setFillColor(UIColor(hex: 0xFFD678).cgColor)
        for _ in 0..<70 {
            let rx = CGFloat.random(in: rect.minX...rect.maxX)
            let ry = CGFloat.random(in: (rect.maxY - 170)...(rect.maxY - 10))
            ctx.fill(CGRect(x: rx, y: ry, width: 5, height: 5))
        }
        // moon
        drawSun(center: CGPoint(x: rect.maxX - 110, y: rect.minY + 120), radius: 42,
                color: UIColor(white: 1, alpha: 0.9), ctx: ctx)
    }

    private static func drawWindmill(in rect: CGRect, ctx: CGContext) {
        // rolling hill
        ctx.setFillColor(UIColor(hex: 0x3F7A45).withAlphaComponent(0.8).cgColor)
        ctx.beginPath()
        ctx.move(to: CGPoint(x: rect.minX, y: rect.maxY - 100))
        ctx.addCurve(to: CGPoint(x: rect.maxX, y: rect.maxY - 100),
                     control1: CGPoint(x: rect.midX - 100, y: rect.maxY - 220),
                     control2: CGPoint(x: rect.midX + 100, y: rect.maxY - 50))
        ctx.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        ctx.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        ctx.closePath()
        ctx.fillPath()
        // windmill
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fill(CGRect(x: rect.midX - 10, y: rect.maxY - 320, width: 20, height: 240))
        ctx.saveGState()
        ctx.translateBy(x: rect.midX, y: rect.maxY - 320)
        for i in 0..<3 {
            ctx.saveGState()
            ctx.rotate(by: CGFloat(i) * (.pi * 2 / 3))
            ctx.addRect(CGRect(x: -8, y: -110, width: 16, height: 100))
            ctx.fillPath()
            ctx.restoreGState()
        }
        ctx.restoreGState()
    }

    private static func drawBowl(in rect: CGRect, ctx: CGContext) {
        // plate
        ctx.setFillColor(UIColor.white.withAlphaComponent(0.95).cgColor)
        ctx.addEllipse(in: CGRect(x: rect.midX - 180, y: rect.midY - 80, width: 360, height: 220))
        ctx.fillPath()
        // noodle spiral
        ctx.setStrokeColor(UIColor(hex: 0xE2A555).cgColor)
        ctx.setLineWidth(10)
        ctx.setLineCap(.round)
        for i in 0..<12 {
            let a = CGFloat(i) * .pi / 6
            let cx = rect.midX + cos(a) * 40
            let cy = rect.midY + sin(a) * 25
            ctx.addArc(center: CGPoint(x: cx, y: cy), radius: 30,
                       startAngle: 0, endAngle: .pi * 2, clockwise: false)
            ctx.strokePath()
        }
        // chopsticks
        ctx.setFillColor(UIColor(hex: 0x8B5A2B).cgColor)
        ctx.fill(CGRect(x: rect.midX + 120, y: rect.midY - 160, width: 8, height: 260))
        ctx.fill(CGRect(x: rect.midX + 140, y: rect.midY - 160, width: 8, height: 260))
    }

    private static func drawTrain(in rect: CGRect, ctx: CGContext) {
        // tracks
        ctx.setStrokeColor(UIColor.white.withAlphaComponent(0.6).cgColor)
        ctx.setLineWidth(3)
        for i in 0..<3 {
            let y = rect.maxY - 80 + CGFloat(i) * 18
            ctx.move(to: CGPoint(x: rect.minX, y: y))
            ctx.addLine(to: CGPoint(x: rect.maxX, y: y))
            ctx.strokePath()
        }
        // train body
        ctx.setFillColor(UIColor.white.cgColor)
        let body = CGRect(x: rect.minX + 60, y: rect.midY - 40, width: rect.width - 120, height: 140)
        let path = UIBezierPath(roundedRect: body, cornerRadius: 36)
        ctx.addPath(path.cgPath); ctx.fillPath()
        // nose
        ctx.setFillColor(UIColor(hex: 0x3F7CFF).cgColor)
        ctx.addEllipse(in: CGRect(x: rect.maxX - 140, y: rect.midY - 30, width: 100, height: 120))
        ctx.fillPath()
        // windows
        ctx.setFillColor(UIColor(hex: 0x1F3F6E).cgColor)
        for i in 0..<5 {
            ctx.fill(CGRect(x: rect.minX + 90 + CGFloat(i) * 70, y: rect.midY - 20,
                            width: 54, height: 40))
        }
    }

    // MARK: - Caption badge in the bottom-left corner

    private static func drawCaptionBadge(_ text: String, in rect: CGRect) {
        let font = UIFont.systemFont(ofSize: 34, weight: .semibold)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.white,
        ]
        let ns = text as NSString
        let size = ns.size(withAttributes: attrs)
        let padding: CGFloat = 20
        let bg = CGRect(x: rect.minX + 24,
                        y: rect.maxY - size.height - 24 - padding,
                        width: size.width + padding * 2,
                        height: size.height + padding)
        let bgPath = UIBezierPath(roundedRect: bg, cornerRadius: bg.height / 2)
        UIColor.black.withAlphaComponent(0.35).setFill()
        bgPath.fill()
        ns.draw(at: CGPoint(x: bg.minX + padding, y: bg.minY + padding / 2),
                withAttributes: attrs)
    }
}
