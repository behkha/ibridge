import SwiftUI

struct MessagesView: View {
    @Environment(Store.self) private var store
    var onPill: () -> Void
    @State private var query = ""
    @State private var path: [String] = []

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
                            NavigationLink(value: row.id) { ThreadCell(row: row) }.buttonStyle(.plain)
                        }
                    }
                }
                .scrollDismissesKeyboard(.immediately)
            }
            .background(Color.white)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { ConversationView(thread: $0) }
        }
        .onAppear { if let t = store.demoThread, path.isEmpty { path = [t] } }
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

/// One conversation. Not in the approved mockups yet: plain bubbles in the A Companion palette.
struct ConversationView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var thread: String
    @State private var draft = ""

    var body: some View {
        let items = store.conversation(thread)
        let addr = items.last?.addr ?? ""
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(items) { m in
                            HStack {
                                if !m.incoming { Spacer(minLength: 48) }
                                Text(m.body)
                                    .font(.app(17))
                                    .foregroundStyle(m.incoming ? Palette.ink : .white)
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(m.incoming ? Palette.field : (m.state == "failed" ? Palette.missed : Palette.accent),
                                                in: RoundedRectangle(cornerRadius: 18))
                                if m.incoming { Spacer(minLength: 48) }
                            }
                            .id(m.id)
                        }
                    }
                    .padding(16)
                }
                .onAppear { proxy.scrollTo(items.last?.id, anchor: .bottom) }
            }
            HStack(spacing: 8) {
                TextField("Text", text: $draft, axis: .vertical)
                    .font(.app(17)).lineLimit(1...5)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Palette.field, in: RoundedRectangle(cornerRadius: 18))
                Button {
                    _ = central?.sendText(to: addr, body: draft, sim: items.last?.sim) // reply on the SIM the conversation uses
                    draft = ""
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 32)).foregroundStyle(Palette.accent)
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.link != .linked)
                .accessibilityLabel("Send")
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
        .navigationTitle(Fmt.digits(items.last.map { $0.name ?? store.contactName(for: $0.addr) ?? $0.addr } ?? ""))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }
}
