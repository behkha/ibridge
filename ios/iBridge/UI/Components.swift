import SwiftUI

/// Two dots joined by a line: solid when linked, broken and hollow when not.
struct LinkGlyph: View {
    var linked: Bool
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            let y = size.height / 2
            if linked {
                ctx.fill(Path(ellipseIn: CGRect(x: 1, y: y - 4, width: 8, height: 8)), with: .color(color))
                ctx.fill(Path(ellipseIn: CGRect(x: 21, y: y - 4, width: 8, height: 8)), with: .color(color))
                ctx.stroke(Path { $0.move(to: .init(x: 9, y: y)); $0.addLine(to: .init(x: 21, y: y)) }, with: .color(color), lineWidth: 2)
            } else {
                let s = StrokeStyle(lineWidth: 2)
                ctx.stroke(Path(ellipseIn: CGRect(x: 1, y: y - 4, width: 8, height: 8)), with: .color(color), style: s)
                ctx.stroke(Path(ellipseIn: CGRect(x: 21, y: y - 4, width: 8, height: 8)), with: .color(color), style: s)
                ctx.stroke(Path { $0.move(to: .init(x: 9, y: y)); $0.addLine(to: .init(x: 13, y: y)) }, with: .color(color), style: s)
                ctx.stroke(Path { $0.move(to: .init(x: 17, y: y)); $0.addLine(to: .init(x: 21, y: y)) }, with: .color(color), style: s)
            }
        }
        .frame(width: 30, height: 12)
        .accessibilityHidden(true)
    }
}

/// Top-right status: green with the phone's name when linked, grey "Out of reach" when not.
struct LinkPill: View {
    @Environment(Store.self) private var store
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                switch store.link {
                case .linked:
                    LinkGlyph(linked: true, color: Palette.linked)
                    Text(store.peerName).environment(\.layoutDirection, .leftToRight)
                case .outOfReach:
                    LinkGlyph(linked: false, color: Palette.secondary)
                    Text("Out of reach")
                case .connecting:
                    LinkGlyph(linked: false, color: Palette.secondary)
                    Text("Connecting")
                case .unpaired:
                    Text("Pair")
                }
            }
            .font(.app(14, .semibold))
            .foregroundStyle(foreground)
            .padding(.leading, 10).padding(.trailing, 12)
            .frame(height: 32)
            .background(background, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var foreground: Color {
        switch store.link {
        case .linked: Palette.linkedInk
        case .unpaired: .white
        default: Color(hex: 0x4A5260)
        }
    }

    private var background: Color {
        switch store.link {
        case .linked: Palette.linkedBg
        case .unpaired: Palette.accent
        default: Palette.field
        }
    }

    private var label: String {
        switch store.link {
        case .linked: String(localized: "Linked to \(store.peerName), encrypted")
        case .outOfReach: String(localized: "\(store.peerName) out of reach")
        case .connecting: String(localized: "Connecting to \(store.peerName)")
        case .unpaired: String(localized: "Pair with your Android phone")
        }
    }
}

/// The shared tab header: pill on top, large title, then whatever the tab adds.
struct TabHeader<Extra: View>: View {
    var title: LocalizedStringKey
    var onPill: () -> Void
    @ViewBuilder var extra: Extra

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Spacer(); LinkPill(onTap: onPill) }
            Text(title)
                .font(.app(Font.isPersian ? 32 : 34, .bold))
                .foregroundStyle(Palette.ink)
            extra
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 8)
    }
}

struct SearchField: View {
    @Binding var text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").foregroundStyle(Palette.secondary).font(.system(size: 15, weight: .semibold))
            TextField("Search", text: $text)
                .font(.app(Font.isPersian ? 16 : 17))
                .foregroundStyle(Palette.ink)
        }
        .padding(.horizontal, 8)
        .frame(height: Font.isPersian ? 38 : 36)
        .background(Palette.field, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// Newest one-time code, on top of Messages.
struct CodeCard: View {
    var code: OneTimeCode
    @State private var copied = false

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Code from \(Fmt.digits(code.from)), \(age)")
                    .font(.app(13)).foregroundStyle(Palette.caption)
                Text(Fmt.code(code.code))
                    .font(.app(32, .bold)).tracking(3).monospacedDigit()
                    .foregroundStyle(Palette.ink)
                    .environment(\.layoutDirection, .leftToRight)
                    .frame(maxWidth: .infinity, alignment: .leading) // leading = right edge in Persian
            }
            Button {
                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: code.code]],
                                              options: [.expirationDate: Date().addingTimeInterval(120)])
                copied = true
            } label: {
                Text(copied ? "Copied" : "Copy")
                    .font(.app(16, .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 18).frame(height: 40)
                    .background(Palette.accent, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16).padding(.vertical, Font.isPersian ? 12 : 14)
        .background(Palette.codeBg, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 10)
    }

    private var age: String {
        let s = Date().timeIntervalSince(code.ts)
        return s < 60 ? String(localized: "just now") : code.ts.formatted(.relative(presentation: .named))
    }
}

/// Orange strip, shown only while the Android phone is out of reach.
struct OutOfReachStrip: View {
    var name: String
    var since: Date
    var message: LocalizedStringKey

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(Palette.away).font(.system(size: 17, weight: .semibold))
            Text(message).font(.app(15)).foregroundStyle(Palette.awayInk).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Palette.awayBg, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 6)
        .accessibilityElement(children: .combine)
    }
}

/// Accent capsule whose disabled state is one 40% fade (a plain style dims the label a second time).
struct FilledButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .compositingGroup() // fade the button as one layer, not the label and fill separately
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4)
    }
}

struct Avatar: View {
    var title: String
    var tint: Color
    var size: CGFloat = 48

    var body: some View {
        let initials = Fmt.initials(title)
        Group {
            if initials.isEmpty {
                Image(systemName: "person.fill").font(.system(size: size * 0.42))
            } else {
                Text(initials).font(.app(18, .semibold))
            }
        }
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(tint, in: Circle())
            .accessibilityHidden(true)
    }
}

extension Store {
    func tintColor(for addr: String, title: String) -> Color {
        if title.allSatisfy({ $0.isNumber || $0 == "+" || $0 == " " }) { return Palette.numberTint }
        // Read-only here: views must not write to the store. Colours are assigned when a text arrives.
        let digits = String(addr.filter(\.isNumber).suffix(9))
        let i = tints[addr]
            ?? (digits.count >= 6 ? tints.first { $0.key.filter(\.isNumber).hasSuffix(digits) }?.value : nil)
            ?? digits.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return Palette.tints[i % Palette.tints.count]
    }
}
