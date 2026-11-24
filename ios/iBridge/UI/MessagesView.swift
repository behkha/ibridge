import SwiftUI

struct MessagesView: View {
    @Environment(Store.self) private var store
    var onPill: () -> Void
    @State private var query = ""
    @State private var path: [Chat] = []

    private var rows: [ThreadRow] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return store.threads }
        return store.threads.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.last.body.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                TabHeader(title: "Messages", onPill: onPill) { SearchField(text: $query) }
                if case .outOfReach(let since) = store.link {
                    OutOfReachStrip(name: store.peerName, since: since,
                                    message: "\(store.peerName) is out of reach since \(Fmt.time(since)). Texts arrive when it’s back.")
                }
                if let code = store.freshCode, query.isEmpty { CodeCard(code: code) }
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(rows) { row in
                            NavigationLink(value: Chat(thread: row.id, addr: row.addr)) { ThreadCell(row: row) }.buttonStyle(.plain)
                        }
                    }
                }
                .scrollDismissesKeyboard(.immediately)
            }
            .background(Color.white)
            .navigationTitle("Messages") // the back button's label in a conversation
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Chat.self) { ConversationView(chat: $0) }
        }
        .onAppear {
            if let t = store.demoThread, path.isEmpty, let row = store.threads.first(where: { $0.id == t }) { path = [Chat(thread: t, addr: row.addr)] }
        }
    }
}

private struct ThreadCell: View {
    @Environment(Store.self) private var store
    var row: ThreadRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Avatar(title: Fmt.digits(row.title), tint: store.tintColor(for: row.addr, title: row.title))
            VStack(alignment: .leading, spacing: Font.isPersian ? 0 : 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(Fmt.digits(row.title)).font(.app(Font.isPersian ? 16 : 17, .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                    Spacer(minLength: 0)
                    Text(Fmt.when(row.last.ts)).font(.app(Font.isPersian ? 14 : 15)).foregroundStyle(Palette.secondary)
                }
                HStack(alignment: .top, spacing: 8) {
                    Text(row.last.body)
                        .font(.app(Font.isPersian ? 14 : 15)).foregroundStyle(Palette.preview)
                        .lineLimit(2).lineSpacing(Font.isPersian ? 4 : 2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if row.unread > 0 {
                        Text(Fmt.digits("\(row.unread)"))
                            .font(.app(13, .semibold)).foregroundStyle(.white)
                            .padding(.horizontal, 6).frame(minWidth: 22, minHeight: 22)
                            .background(Palette.accent, in: Capsule())
                    }
                }
            }
            .padding(.bottom, Font.isPersian ? 8 : 10)
            .overlay(alignment: .bottom) { Rectangle().fill(Palette.separator).frame(height: 1) }
        }
        .padding(.horizontal, 16).padding(.top, Font.isPersian ? 8 : 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Where a conversation opens: a known thread, or just a number (from Keypad or Contacts).
struct Chat: Hashable {
    var thread: String?
    var addr: String
}

/// Approved C1 "Native" (canvas page Screens round 2): iOS bubbles, the SIM in the composer, inline retry.
struct ConversationView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var chat: Chat
    @State private var draft = ""

    var body: some View {
        let thread = chat.thread ?? store.threadID(for: chat.addr)
        let items = thread.map(store.conversation) ?? []
        let addr = items.last?.addr ?? chat.addr
        let title = items.last?.name ?? store.contactName(for: addr) ?? Fmt.phone(addr)
        let sims = store.status?.sims.count ?? 0
        let sim = items.last?.sim ?? store.status?.sims.first?.sim
        VStack(spacing: 0) {
            if case .outOfReach(let since) = store.link {
                OutOfReachStrip(name: store.peerName, since: since,
                                message: "\(Fmt.isolate(store.peerName)) is out of reach since \(Fmt.time(since)). Texts arrive when it’s back.")
                    .padding(.top, 4)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 3) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                            let prev = i > 0 ? items[i - 1] : nil
                            let next = i + 1 < items.count ? items[i + 1] : nil
                            if prev.map({ !Calendar.current.isDate($0.ts, inSameDayAs: m.ts) }) ?? true {
                                Text(Fmt.day(m.ts)).font(.app(12, .semibold)).foregroundStyle(Palette.secondary)
                                    .padding(.top, 8).padding(.bottom, 4)
                            }
                            Bubble(message: m, lastInGroup: next.map { !Self.sameGroup(m, $0) } ?? true, canRetry: store.link == .linked) {
                                // Resend; the new copy replaces the failed one here (Android keeps its own record).
                                _ = central?.sendText(to: m.addr, body: m.body, sim: m.sim)
                                store.messages[m.id] = nil
                            }
                            .id(m.id)
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                }
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: items.last?.id) { proxy.scrollTo(items.last?.id, anchor: .bottom) }
            }
            composer(addr: addr, sim: sim, showSim: sims > 1)
        }
        .background(Color.white)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 2) {
                    Text(title == Fmt.phone(addr) ? Fmt.ltr(title) : Fmt.digits(title)).font(.app(16, .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                    if title != Fmt.phone(addr) {
                        Text(Fmt.phone(addr, localDigits: false)).font(.app(12)).foregroundStyle(Palette.secondary)
                            .environment(\.layoutDirection, .leftToRight)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .toolbar(.visible, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
    }

    /// Same direction, same day, under 10 minutes apart: one group, one time label.
    static func sameGroup(_ a: SMS, _ b: SMS) -> Bool {
        a.dir == b.dir && Calendar.current.isDate(a.ts, inSameDayAs: b.ts) && b.ts.timeIntervalSince(a.ts) < 600
    }

    private func composer(addr: String, sim: Int?, showSim: Bool) -> some View {
        let live = store.link == .linked
        let away: Bool = { if case .outOfReach = store.link { return true }; return false }()
        let empty = draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let placeholder: String = {
            if away { return String(localized: "\(Fmt.isolate(store.peerName)) is out of reach") }
            if showSim, let sim { return String(localized: "Text from SIM \(Fmt.digits(String(sim)))") }
            return String(localized: "Text")
        }()
        return HStack(spacing: 8) {
            TextField(placeholder, text: $draft, axis: .vertical)
                .font(.app(Font.isPersian ? 16 : 17)).lineLimit(1...5)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .frame(minHeight: 36)
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Palette.separator))
                .disabled(away)
            Button {
                _ = central?.sendText(to: addr, body: draft, sim: sim) // reply on the SIM the conversation uses
                draft = ""
            } label: {
                Image(systemName: "arrow.up").font(.system(size: 17, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(live && !empty ? Palette.accent : Palette.disabled, in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(empty || !live)
            .accessibilityLabel("Send")
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.white)
        .overlay(alignment: .top) { Rectangle().fill(Palette.separator).frame(height: 1) }
    }
}

private struct Bubble: View {
    var message: SMS
    var lastInGroup: Bool
    var canRetry: Bool
    var retry: () -> Void

    var body: some View {
        let m = message
        let tail: CGFloat = lastInGroup ? 6 : 18
        let shape = UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: m.incoming ? tail : 18,
                                           bottomTrailingRadius: m.incoming ? 18 : tail, topTrailingRadius: 18)
        VStack(alignment: m.incoming ? .leading : .trailing, spacing: 2) {
            Text(m.body)
                .font(.app(Font.isPersian ? 16 : 17)).lineSpacing(Font.isPersian ? 6 : 2)
                .foregroundStyle(m.incoming ? Palette.ink : .white)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(m.incoming ? Palette.field : Palette.accent, in: shape)
                .compositingGroup()
                .opacity(m.incoming || m.state == "ok" ? 1 : 0.55)
                .frame(maxWidth: 292, alignment: m.incoming ? .leading : .trailing)
            if m.state == "failed" && !m.incoming {
                Button(action: retry) {
                    Label("Not sent. Tap to retry", systemImage: "exclamationmark.circle")
                        .font(.app(13, .semibold)).foregroundStyle(Palette.missed)
                }
                .buttonStyle(.plain)
                .disabled(!canRetry)
                .padding(.horizontal, 6).padding(.bottom, 6)
            } else if lastInGroup {
                Text(Fmt.time(m.ts)).font(.app(12)).foregroundStyle(Palette.secondary)
                    .padding(.horizontal, 6).padding(.bottom, 6)
            }
        }
        .frame(maxWidth: .infinity, alignment: m.incoming ? .leading : .trailing)
    }
}
