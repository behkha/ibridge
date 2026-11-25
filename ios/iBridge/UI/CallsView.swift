import SwiftUI

struct CallsView: View {
    @Environment(Store.self) private var store
    var onPill: () -> Void
    @State private var missedOnly = false

    /// Consecutive calls from one number with the same outcome fold into one row: "Mom (2)".
    private var rows: [(call: Call, count: Int)] {
        var out: [(call: Call, count: Int)] = []
        for c in store.callList where !missedOnly || c.missed {
            if let last = out.last, last.call.addr == c.addr, last.call.missed == c.missed, last.call.kind == c.kind {
                out[out.count - 1].count += 1
            } else {
                out.append((c, 1))
            }
        }
        return out
    }

    var body: some View {
        VStack(spacing: 0) {
            TabHeader(title: "Calls", onPill: onPill) {
                Picker("Filter", selection: $missedOnly) {
                    Text("All").tag(false)
                    Text("Missed").tag(true)
                }
                .pickerStyle(.segmented)
            }
            if case .outOfReach(let since) = store.link {
                OutOfReachStrip(name: store.peerName, since: since,
                                message: "\(store.peerName) is out of reach since \(Fmt.time(since)). Calling needs it nearby.")
            }
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows, id: \.call.id) { row in CallCell(call: row.call, count: row.count) }
                }
            }
        }
        .background(Color.white)
    }
}

private struct CallCell: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    var call: Call
    var count: Int

    private var title: String {
        let base = Fmt.digits(call.name ?? store.contactName(for: call.addr) ?? call.addr)
        return count > 1 ? "\(base) (\(Fmt.digits(String(count))))" : base
    }

    private var kind: LocalizedStringKey {
        let sim = Fmt.digits(String(call.sim))
        switch call.kind {
        case "out": return "Outgoing, SIM \(sim)"
        case "in": return "Incoming, SIM \(sim)"
        case "rejected": return "Declined, SIM \(sim)"
        default: return "Missed, SIM \(sim)"
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.app(17, .semibold)).foregroundStyle(call.missed ? Palette.missed : Palette.ink).lineLimit(1)
                Text(kind).font(.app(14)).foregroundStyle(Palette.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(Fmt.when(call.ts)).font(.app(15)).foregroundStyle(Palette.secondary)
            // Blue while Android relays calls and is in reach (P2-Calls); grey otherwise, as in phase 1.
            Button(action: callBack) {
                Image(systemName: "phone")
                    .font(.system(size: 19)).foregroundStyle(store.canCall ? Palette.accent : Palette.disabled)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!store.canCall)
        }
        .padding(.vertical, 9)
        .overlay(alignment: .bottom) { Rectangle().fill(Palette.separator).frame(height: 1) }
        .padding(.horizontal, 16)
        .frame(minHeight: 60)
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: store.canCall ? Text("Call back") : Text("Call back, not available yet"), callBack)
    }

    private func callBack() {
        guard store.canCall, !call.addr.isEmpty else { return }
        central?.calls.dial(call.addr, sim: call.sim > 0 ? call.sim : nil)
    }
}
