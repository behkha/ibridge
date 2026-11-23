import SwiftUI

// Keypad, Contacts and Settings have no approved mockups yet. They use the A Companion header,
// palette and type with stock iOS controls, and are listed for design review in STATE.md.

struct KeypadView: View {
    @Environment(Store.self) private var store
    var onPill: () -> Void
    @State private var number = ""
    @State private var composing = false
    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "⌫"]

    var body: some View {
        VStack(spacing: 0) {
            TabHeader(title: "Keypad", onPill: onPill) { EmptyView() }
            Spacer()
            Text(Fmt.digits(number.isEmpty ? " " : number))
                .font(.app(34, .medium)).foregroundStyle(Palette.ink)
                .environment(\.layoutDirection, .leftToRight)
                .padding(.bottom, 24)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(84), spacing: 24), count: 3), spacing: 16) {
                ForEach(keys, id: \.self) { k in
                    Button {
                        if k == "⌫" { if !number.isEmpty { number.removeLast() } } else { number.append(k) }
                    } label: {
                        Text(Fmt.digits(k)).font(.app(32)).foregroundStyle(Palette.ink)
                            .frame(width: 78, height: 78).background(Palette.field, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(k == "⌫" ? "Delete" : k)
                }
            }
            .environment(\.layoutDirection, .leftToRight)
            Button { composing = true } label: {
                Label("Text", systemImage: "message.fill")
                    .font(.app(17, .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 28).frame(height: 52).background(Palette.accent, in: Capsule())
            }
            .disabled(number.count < 3)
            .padding(.top, 24)
            Spacer()
        }
        .background(Color.white)
        .sheet(isPresented: $composing) { ComposeView(to: number) }
    }
}

struct ContactsView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var onPill: () -> Void
    @State private var query = ""
    @State private var composeTo: String?

    private var rows: [Contact] {
        let all = store.contacts.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? all : all.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.phones.contains { $0.n.contains(q) } }
    }

    var body: some View {
        VStack(spacing: 0) {
            TabHeader(title: "Contacts", onPill: onPill) { SearchField(text: $query) }
            List(rows) { c in
                Button { composeTo = c.phones.first?.n } label: {
                    HStack(spacing: 12) {
                        Avatar(title: c.name, tint: store.tintColor(for: c.phones.first?.n ?? c.id, title: c.name), size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.name).font(.app(17, .semibold)).foregroundStyle(Palette.ink)
                            if let p = c.phones.first {
                                Text(Fmt.digits(p.n)).font(.app(14)).foregroundStyle(Palette.secondary)
                                    .environment(\.layoutDirection, .leftToRight)
                            }
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
            .refreshable { central?.refreshContacts() }
        }
        .background(Color.white)
        .sheet(item: Binding(get: { composeTo.map(Recipient.init) }, set: { composeTo = $0?.id })) { ComposeView(to: $0.id) }
    }

    private struct Recipient: Identifiable { var id: String }
}

struct ComposeView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    @Environment(\.dismiss) private var dismiss
    @State var to: String
    @State private var body_ = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("To", text: $to).keyboardType(.phonePad).font(.app(17))
                TextField("Text", text: $body_, axis: .vertical).lineLimit(3...8).font(.app(17))
            }
            .navigationTitle("New text")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") {
                        _ = central?.sendText(to: to, body: body_)
                        dismiss()
                    }
                    .disabled(to.isEmpty || body_.isEmpty || store.link != .linked)
                }
            }
        }
    }
}

struct SettingsView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var onPill: () -> Void
    var onPair: () -> Void
    @State private var confirmUnlink = false

    var body: some View {
        VStack(spacing: 0) {
            TabHeader(title: "Settings", onPill: onPill) { EmptyView() }
            Form {
                if store.peer != nil {
                    Section {
                        LabeledContent("Phone", value: store.peerName)
                        if let s = store.status {
                            if s.battery >= 0 { LabeledContent("Battery", value: Fmt.digits("\(s.battery)%")) }
                            ForEach(s.sims, id: \.sim) { sim in
                                LabeledContent("SIM \(Fmt.digits(String(sim.sim)))", value: sim.carrier)
                            }
                        }
                    }
                    Section {
                        Button("Pair again", action: onPair)
                        Button("Unlink \(store.peerName)", role: .destructive) { confirmUnlink = true }
                    }
                } else {
                    Section { Button("Pair with your Android phone", action: onPair) }
                }
            }
            .font(.app(17))
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .confirmationDialog("Unlink \(store.peerName)?", isPresented: $confirmUnlink, titleVisibility: .visible) {
            Button("Unlink", role: .destructive) { central?.unpair() }
        } message: {
            Text("This iPhone stops getting texts and calls until you pair again.")
        }
    }
}
