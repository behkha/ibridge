import SwiftUI

// Approved round-2 boards (canvas page "Screens round 2"): K1 Text pad, N2 Quick text, S1 Phone card.
// Phase 2 (design/mockups/P2-Calls.html): the call key, and the Settings row while calls aren't ready.

/// Round keys. When Android relays calls: a green call key with Text to its left; otherwise the Text pad of phase 1.
struct KeypadView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var onPill: () -> Void
    @State private var number = ""
    @State private var path: [Chat] = []
    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "⌫"]
    private let letters = ["2": "ABC", "3": "DEF", "4": "GHI", "5": "JKL", "6": "MNO", "7": "PQRS", "8": "TUV", "9": "WXYZ", "0": "+"]

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                TabHeader(title: "Keypad", onPill: onPill) { EmptyView() }
                Spacer(minLength: 8)
                VStack(spacing: 4) {
                    Text(Fmt.phone(number.isEmpty ? " " : number))
                        .font(.app(36, .medium)).tracking(1).foregroundStyle(Palette.ink)
                        .lineLimit(1).minimumScaleFactor(0.5)
                        .environment(\.layoutDirection, .leftToRight)
                    let name = store.contactName(for: number)
                    Button { open() } label: {
                        Text(name ?? " ").font(.app(17, .semibold)).foregroundStyle(Palette.accent)
                    }
                    .buttonStyle(.plain)
                    .disabled(name == nil)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 22)
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(78), spacing: 26), count: 3), spacing: 14) {
                    ForEach(keys, id: \.self) { k in key(k) }
                }
                .environment(\.layoutDirection, .leftToRight)
                if store.callsReady { callKeys } else { textKey }
                Spacer(minLength: 8)
            }
            .background(Color.white)
            .navigationTitle("Keypad")
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Chat.self) { ConversationView(chat: $0) }
            .onAppear { if let n = store.demoNumber, number.isEmpty { number = n } }
        }
    }

    private var textKey: some View {
        Button { open() } label: {
            Label("Text", systemImage: "message")
                .font(.app(17, .semibold)).foregroundStyle(.white)
                .padding(.horizontal, 30).frame(height: 56)
                .background(Palette.accent, in: Capsule())
        }
        .buttonStyle(FilledButtonStyle())
        .disabled(number.count < 3)
        .padding(.top, 22)
    }

    /// Under the pad's middle column, like the Phone app; Text sits under the left column.
    private var callKeys: some View {
        HStack(spacing: 26) {
            Button { open() } label: {
                Image(systemName: "message").font(.system(size: 26)).foregroundStyle(Palette.accent)
                    .frame(width: 78, height: 78)
                    .contentShape(Circle())
            }
            .buttonStyle(FilledButtonStyle())
            .disabled(number.count < 3)
            .accessibilityLabel("Text")
            Button { central?.calls.dial(number) } label: {
                Image(systemName: "phone.fill").font(.system(size: 32)).foregroundStyle(.white)
                    .frame(width: 78, height: 78)
                    .background(Palette.call, in: Circle())
            }
            .buttonStyle(FilledButtonStyle())
            .disabled(number.count < 3 || !store.canCall)
            .accessibilityLabel("Call")
            Color.clear.frame(width: 78, height: 78)
        }
        .padding(.top, 22)
    }

    private func open() { if number.count >= 3 { path = [Chat(thread: nil, addr: number)] } }

    @ViewBuilder private func key(_ k: String) -> some View {
        if k == "⌫" {
            Button { if !number.isEmpty { number.removeLast() } } label: {
                Image(systemName: "delete.left").font(.system(size: 26)).foregroundStyle(Palette.ink)
                    .frame(width: 78, height: 78)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete")
            .opacity(number.isEmpty ? 0 : 1)
        } else {
            Button { number.append(k) } label: {
                VStack(spacing: 0) {
                    Text(Fmt.digits(k)).font(.app(34)).frame(height: 38)
                    Text(letters[k] ?? " ").font(.system(size: 10, weight: .semibold)).tracking(2).frame(height: 12)
                }
                .foregroundStyle(Palette.ink)
                .frame(width: 78, height: 78)
                .background(Palette.field, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(k)
        }
    }
}

/// Quick text: recent people on top; a row opens the conversation (a picker when there are several numbers).
struct ContactsView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var onPill: () -> Void
    @State private var query = ""
    @State private var path: [Chat] = []
    @State private var picking: Contact?

    private var rows: [Contact] {
        let all = store.contacts.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? all : all.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.phones.contains { $0.n.contains(q) } }
    }

    /// The five most recent named conversations.
    private var recents: [ThreadRow] {
        Array(store.threads.filter { store.contactName(for: $0.addr) != nil || $0.last.name != nil }.prefix(5))
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                TabHeader(title: "Contacts", onPill: onPill) { SearchField(text: $query) }
                List {
                    if query.isEmpty && !recents.isEmpty {
                        HStack(alignment: .top, spacing: 10) {
                            ForEach(recents) { r in
                                Button { path = [Chat(thread: r.id, addr: r.addr)] } label: {
                                    VStack(spacing: 6) {
                                        Avatar(title: r.title, tint: store.tintColor(for: r.addr, title: r.title), size: 52)
                                        Text(Fmt.firstName(r.title))
                                            .font(.app(13)).foregroundStyle(Palette.ink).lineLimit(1)
                                    }
                                    .frame(width: 60)
                                }
                                .buttonStyle(.plain)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 12)
                        .listRowInsets(EdgeInsets())
                        .listRowSeparatorTint(Palette.separator)
                        .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
                        .alignmentGuide(.listRowSeparatorTrailing) { d in d.width }
                    }
                    ForEach(rows) { c in
                        Button { open(c) } label: { ContactRow(contact: c) }
                            .buttonStyle(.plain)
                            .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                            .listRowSeparatorTint(Palette.separator)
                            .alignmentGuide(.listRowSeparatorLeading) { _ in 52 }
                    }
                }
                .listStyle(.plain)
                .refreshable { central?.refreshContacts() }
            }
            .background(Color.white)
            .navigationTitle("Contacts")
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Chat.self) { ConversationView(chat: $0) }
            .confirmationDialog(picking?.name ?? "", isPresented: Binding(get: { picking != nil }, set: { if !$0 { picking = nil } }), titleVisibility: .visible) {
                ForEach(picking?.phones ?? [], id: \.n) { p in
                    let title = (p.label.isEmpty ? "" : p.label + "  ") + Fmt.ltr(Fmt.phone(p.n))
                    Button(title) { path = [Chat(thread: nil, addr: p.n)] }
                }
            }
        }
    }

    private func open(_ c: Contact) {
        if c.phones.count > 1 { picking = c } else if let p = c.phones.first { path = [Chat(thread: nil, addr: p.n)] }
    }
}

private struct ContactRow: View {
    @Environment(Store.self) private var store
    var contact: Contact

    var body: some View {
        let c = contact
        HStack(spacing: 12) {
            Avatar(title: c.name, tint: store.tintColor(for: c.phones.first?.n ?? c.id, title: c.name), size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(c.name).font(.app(17, .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                if let p = c.phones.first {
                    Text(Fmt.phone(p.n))
                        .font(.app(14)).foregroundStyle(Palette.secondary)
                        .environment(\.layoutDirection, .leftToRight)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "message").font(.system(size: 18)).foregroundStyle(Palette.accent)
        }
        .frame(height: 60)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens the conversation")
    }
}

/// Phone card: the Android home's green card, seen from the iPhone.
struct SettingsView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var onPill: () -> Void
    var onPair: () -> Void
    @State private var confirmUnlink = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                TabHeader(title: "Settings", onPill: onPill) { EmptyView() }
                if store.peer != nil {
                    PhoneCard().padding(.horizontal, 16).padding(.top, 4)
                        // Debug: hold the card to measure the link (`bench`, PROTOCOL.md 6); the result shows in an alert.
                        .onLongPressGesture(minimumDuration: 2) { central?.startBench() }
                    if !store.callsReady { group { callsRow } }
                    group {
                        row("Pair again", color: Palette.ink, chevron: true, action: onPair)
                        Rectangle().fill(Palette.separator).frame(height: 1)
                        row("Unlink \(Fmt.isolate(store.peerName))", color: Palette.missed, chevron: false) { confirmUnlink = true }
                    }
                } else {
                    group { row("Pair with your Android phone", color: Palette.accent, chevron: true, action: onPair) }
                }
            }
        }
        .background(Palette.groupedBg)
        .confirmationDialog("Unlink \(store.peerName)?", isPresented: $confirmUnlink, titleVisibility: .visible) {
            Button("Unlink", role: .destructive) { central?.unpair() }
        } message: {
            Text("This iPhone stops getting texts and calls until you pair again.")
        }
    }

    /// Shown only while calls fall back to the phase-1 alert; the word says what to fix on Android.
    private var callsRow: some View {
        let reason: LocalizedStringKey = switch store.audioState {
        case "off": "Not running"
        case "unsupported": "Not supported"
        default: "Set up there"
        }
        return HStack(spacing: 12) {
            Text("Calls ring on the \(Fmt.isolate(store.peerName))").font(.app(17)).foregroundStyle(Palette.ink)
            Spacer(minLength: 0)
            HStack(spacing: 6) {
                Circle().fill(Palette.away).frame(width: 8, height: 8)
                Text(reason).font(.app(15)).foregroundStyle(Palette.away)
            }
            .fixedSize()
        }
        .padding(.horizontal, 16).padding(.vertical, 12).frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }

    private func group<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(spacing: 0, content: content)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
            .padding(.horizontal, 16).padding(.top, 24)
    }

    private func row(_ title: LocalizedStringKey, color: Color, chevron: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).font(.app(17)).foregroundStyle(color)
                Spacer()
                if chevron { Image(systemName: "chevron.forward").font(.system(size: 14, weight: .semibold)).foregroundStyle(Color(hex: 0xC4C8CF)) }
            }
            .padding(.horizontal, 16).frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct PhoneCard: View {
    @Environment(Store.self) private var store

    var body: some View {
        let linked = store.link == .linked
        let ink = linked ? Color(hex: 0x0F4D31) : Palette.ink
        VStack(alignment: .leading, spacing: 12) {
            LinkGlyph(linked: linked, color: linked ? Palette.linked : Palette.secondary).scaleEffect(1.6, anchor: .leading).frame(height: 16)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.app(22, .semibold))
                Text(detail).font(.app(15)).foregroundStyle(linked ? ink : Palette.caption)
            }
            HStack(spacing: 8) {
                stat(store.status.flatMap { $0.battery >= 0 ? (Double($0.battery) / 100).formatted(.percent) : nil } ?? "–", "battery")
                ForEach(simSlots, id: \.0) { slot, carrier in
                    stat(carrier, "SIM \(Fmt.digits(String(slot)))")
                }
            }
        }
        .foregroundStyle(ink)
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(linked ? Palette.linkedBg : Palette.field, in: RoundedRectangle(cornerRadius: 22))
        .accessibilityElement(children: .combine)
    }

    private var simSlots: [(Int, String)] {
        let sims = store.status?.sims ?? []
        return sims.isEmpty ? [(1, "–")] : sims.map { ($0.sim, $0.carrier.isEmpty ? "–" : $0.carrier) }
    }

    private var title: String {
        let name = Fmt.isolate(store.peerName)
        switch store.link {
        case .linked: return String(localized: "Linked to \(name)")
        case .connecting: return String(localized: "Connecting to \(name)")
        default: return String(localized: "\(name) is out of reach")
        }
    }

    private var detail: String {
        if case .outOfReach(let since) = store.link { return String(localized: "Since \(Fmt.time(since)). Texts arrive when it’s back.") }
        guard let last = store.messages.values.map(\.ts).max() else { return String(localized: "Encrypted.") }
        let when = Date().timeIntervalSince(last) < 60 ? String(localized: "just now") : last.formatted(.relative(presentation: .named))
        return String(localized: "Encrypted. Last text \(when).")
    }

    private func stat(_ value: String, _ label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.app(17, .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.app(13))
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 14))
    }
}
