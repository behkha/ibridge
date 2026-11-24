import SwiftUI
import UIKit

/// Tokens from the approved A Companion mockups (Main, A-Calls, A-Persian).
enum Palette {
    static let ink = Color(hex: 0x0E1A2B)
    static let accent = Color(hex: 0x1D5BD8)
    static let linked = Color(hex: 0x137A4B)
    static let linkedBg = Color(hex: 0xE6F4EC)
    static let linkedInk = Color(hex: 0x0F5E3A)
    static let away = Color(hex: 0xA65300)
    static let awayBg = Color(hex: 0xFFF1E0)
    static let awayInk = Color(hex: 0x7A3E00)
    static let missed = Color(hex: 0xC4001A)
    static let field = Color(hex: 0xEEF0F3)
    static let secondary = Color(hex: 0x6B7380)
    static let preview = Color(hex: 0x5B6472)
    static let caption = Color(hex: 0x3F4A5A)
    static let separator = Color(hex: 0xE3E6EB)
    static let codeBg = Color(hex: 0xF1F5FE)
    static let tabOff = Color(hex: 0x7A8291)
    static let tabBg = Color(hex: 0xF9FAFB)
    static let disabled = Color(hex: 0xB4BAC4)
    static let numberTint = Color(hex: 0x8A93A3)
    static let camera = Color(hex: 0x1B2330)
    static let scanned = Color(hex: 0x3DDC97)
    static let groupedBg = Color(hex: 0xF2F3F6)
    static let tints: [Color] = [0x5E6AD2, 0xC2569B, 0x2E8C83, 0xB0702A, 0x6A7B2E].map { Color(hex: $0) }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

extension Font {
    static var isPersian: Bool { Locale.current.language.languageCode?.identifier == "fa" }

    /// SF Pro, or Vazirmatn for Persian when the font files are bundled (static weights, see project.yml).
    static func app(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        if isPersian {
            let face: String
            switch weight {
            case .bold, .heavy, .black: face = "Vazirmatn-Bold"
            case .semibold: face = "Vazirmatn-SemiBold"
            case .medium: face = "Vazirmatn-Medium"
            default: face = "Vazirmatn-Regular"
            }
            if UIFont(name: face, size: size) != nil { return .custom(face, size: size) }
        }
        return .system(size: size, weight: weight)
    }
}

enum Fmt {
    /// "14:08" today, "Yesterday", a weekday this week, else a short date. 24-hour as in the mockups.
    static func when(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return time(d) }
        if cal.isDateInYesterday(d) { return String(localized: "Yesterday") }
        if let days = cal.dateComponents([.day], from: d, to: Date()).day, days < 7 { return d.formatted(.dateTime.weekday(.wide)) }
        return d.formatted(date: .numeric, time: .omitted)
    }

    /// Fixed 24-hour "HH:mm" as in the mockups; the locale still supplies the digits (۱۴:۰۸ in Persian).
    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = .current
        f.dateFormat = "HH:mm"
        return f
    }()

    static func time(_ d: Date) -> String { clock.string(from: d) }

    /// Day separator in a conversation: "Today", "Yesterday", a weekday this week, else a date.
    static func day(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return String(localized: "Today") }
        if cal.isDateInYesterday(d) { return String(localized: "Yesterday") }
        if let days = cal.dateComponents([.day], from: d, to: Date()).day, days < 7 { return d.formatted(.dateTime.weekday(.wide)) }
        return d.formatted(date: .abbreviated, time: .omitted)
    }

    /// "0912 111 0001" for an Iranian mobile typed locally, else the number as stored. Locale digits.
    static func phone(_ n: String) -> String {
        let d = n.filter(\.isNumber)
        if n.allSatisfy({ $0.isNumber }), d.hasPrefix("09"), d.count > 4 {
            var parts = [String(d.prefix(4))]
            let rest = d.dropFirst(4)
            if !rest.isEmpty { parts.append(String(rest.prefix(3))) }
            if rest.count > 3 { parts.append(String(rest.dropFirst(3))) }
            return digits(parts.joined(separator: " "))
        }
        if n.hasPrefix("+98"), n.dropFirst().allSatisfy(\.isNumber), d.count == 12 { // +98 912 111 0001
            return digits("+98 \(d.dropFirst(2).prefix(3)) \(d.dropFirst(5).prefix(3)) \(d.dropFirst(8))")
        }
        return digits(n)
    }

    /// Locale digits (Persian digits in Persian).
    static func digits(_ s: String) -> String {
        guard Font.isPersian else { return s }
        return String(s.map { ch in ch.isASCII && ch.isNumber ? Character(UnicodeScalar(0x06F0 + Int(String(ch))!)!) : ch })
    }

    /// "482 913": groups of three.
    static func code(_ c: String) -> String {
        guard c.count == 6 else { return digits(c) }
        return digits(String(c.prefix(3)) + " " + String(c.suffix(3)))
    }

    /// A name inside other-direction text (a Latin phone name in Persian): first-strong isolate.
    static func isolate(_ s: String) -> String { "\u{2068}\(s)\u{2069}" }

    static func initials(_ title: String) -> String {
        let words = title.split(separator: " ").filter { !$0.isEmpty }
        guard let first = words.first else { return "?" }
        if first.allSatisfy({ $0.isNumber || $0 == "+" }) { return String(title.filter(\.isNumber).prefix(2)) }
        if first.unicodeScalars.first.map({ (0x0600...0x06FF).contains(Int($0.value)) }) == true { return String(first.prefix(1)) }
        return words.prefix(2).compactMap(\.first).map { String($0).uppercased() }.joined()
    }
}
