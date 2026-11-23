import SwiftUI
import UIKit

struct RootView: View {
    @Environment(Store.self) private var store
    @State var tab: Int
    @State private var pairing = false

    var body: some View {
        TabView(selection: $tab) {
            MessagesView(onPill: pill)
                .tabItem { Label("Messages", systemImage: "message") }.tag(0)
            CallsView(onPill: pill)
                .tabItem { Label("Calls", systemImage: "phone") }.tag(1)
            KeypadView(onPill: pill)
                .tabItem { Label("Keypad", systemImage: "circle.grid.3x3") }.tag(2)
            ContactsView(onPill: pill)
                .tabItem { Label("Contacts", systemImage: "person.crop.circle") }.tag(3)
            SettingsView(onPill: pill, onPair: { pairing = true })
                .tabItem { Label("Settings", systemImage: "gearshape") }.tag(4)
        }
        .environment(\.symbolVariants, .none)
        .tint(Palette.accent)
        .fullScreenCover(isPresented: $pairing) { PairingView() }
        .onAppear { if store.peer == nil && !store.isDemo { pairing = true } }
        .onChange(of: store.peer == nil) { _, unpaired in if unpaired && !store.isDemo { pairing = true } }
        .alert(store.alert ?? "", isPresented: Binding(get: { store.alert != nil }, set: { if !$0 { store.alert = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func pill() {
        if store.peer == nil { pairing = true } else { tab = 4 }
    }

    static func styleTabBar() {
        let a = UITabBarAppearance()
        a.configureWithOpaqueBackground()
        a.backgroundColor = UIColor(Palette.tabBg)
        a.shadowColor = UIColor(Palette.separator)
        for item in [a.stackedLayoutAppearance, a.inlineLayoutAppearance, a.compactInlineLayoutAppearance] {
            item.normal.iconColor = UIColor(Palette.tabOff)
            item.normal.titleTextAttributes = [.foregroundColor: UIColor(Palette.tabOff), .font: UIFont.systemFont(ofSize: 10, weight: .medium)]
            item.selected.iconColor = UIColor(Palette.accent)
            item.selected.titleTextAttributes = [.foregroundColor: UIColor(Palette.accent), .font: UIFont.systemFont(ofSize: 10, weight: .medium)]
        }
        UITabBar.appearance().standardAppearance = a
        UITabBar.appearance().scrollEdgeAppearance = a
    }
}
