import Foundation

/// Launch arguments `-demo messages|calls-offline` fill the store with the mockups' content, for CI screenshots.
/// No Bluetooth, no persistence.
enum Demo {
    struct Scenario { var name: String; var tab: Int }

    static func scenario() -> Scenario? {
        guard let name = UserDefaults.standard.string(forKey: "demo") else { return nil }
        return Scenario(name: name, tab: name.hasPrefix("calls") ? 1 : 0)
    }

    @MainActor
    static func apply(_ s: Scenario, to store: Store) {
        store.isDemo = true
        let fa = Locale.current.language.languageCode?.identifier == "fa"
        store.peer = PairedPeer(publicKey: Data(), name: "Pixel 8a")
        let cal = Calendar.current
        func today(_ h: Int, _ m: Int) -> Date { cal.date(bySettingHour: h, minute: m, second: 0, of: Date())! }
        func daysAgo(_ d: Int, _ h: Int, _ m: Int) -> Date { cal.date(byAdding: .day, value: -d, to: today(h, m))! }

        let texts: [(String, String, Date, String, Int)] = fa ? [
            ("سارا احمدی", "+989121110001", today(14, 8), "دم ساختمونم، درِ آبی کنار داروخانه", 2),
            ("", "30004505", today(14, 7), "کد ورود شما ۴۸۲۹۱۳ است. آن را به کسی ندهید.", 0),
            ("مامان", "+989121110002", today(13, 41), "ناهار خوردی؟ وقت کردی زنگ بزن", 1),
            ("رضا کریمی", "+989121110003", today(11, 20), "فاکتور رو فرستادم، واریز فردا صبح می‌رسه", 0),
            ("پیک", "+989121110004", daysAgo(1, 16, 2), "بستهٔ شما بین ساعت ۱۶ تا ۱۹ تحویل داده می‌شود", 0),
            ("ندا جعفری", "+989121110005", daysAgo(1, 9, 30), "آره حتماً، پنجشنبه می‌بینمت", 0),
        ] : [
            ("Sara Ahmadi", "+989121110001", today(14, 8), "I’m outside the building, the blue door next to the pharmacy", 2),
            ("", "30004505", today(14, 7), "Your login code is 482913. Do not share it with anyone.", 0),
            ("Mom", "+989121110002", today(13, 41), "Did you eat? Call me when you’re free", 1),
            ("Reza Karimi", "+989121110003", today(11, 20), "Sent the invoice, the transfer should land tomorrow morning", 0),
            ("Courier", "+989121110004", daysAgo(1, 16, 2), "Your parcel is out for delivery between 16:00 and 19:00", 0),
            ("Neda Jafari", "+989121110005", daysAgo(1, 9, 30), "Haha yes, see you Thursday", 0),
        ]
        var n = 0
        for (name, addr, ts, body, unread) in texts {
            if !name.isEmpty { _ = store.tint(for: addr) }
            for i in 0..<max(unread, 1) {
                n += 1
                store.messages["\(n)"] = SMS(id: "\(n)", thread: addr, addr: addr, name: name.isEmpty ? nil : name, body: body,
                                             ts: ts.addingTimeInterval(Double(-i)), dir: "in", read: unread == 0, sim: 2, state: "ok")
            }
        }
        store.latestCode = OneTimeCode(sms: "2", code: "482913", from: "30004505", ts: Date())

        let calls: [(String, String, Date, Int)] = [
            ("Mom", "missed", today(13, 55), 2), ("Sara Ahmadi", "in", today(12, 30), 2),
            ("+98 21 8820 4410", "missed", today(11, 2), 1), ("Reza Karimi", "out", today(10, 47), 2),
            ("Courier", "in", daysAgo(1, 18, 0), 1), ("Neda Jafari", "out", daysAgo(1, 15, 0), 2),
            ("Dr. Moradi Clinic", "in", daysAgo(4, 10, 0), 1),
        ]
        var c = 0
        for (name, kind, ts, sim) in calls {
            let repeats = name == "Mom" ? 2 : 1
            for i in 0..<repeats {
                c += 1
                let isNumber = name.hasPrefix("+")
                store.calls["\(c)"] = Call(id: "\(c)", addr: isNumber ? name : name, name: isNumber ? nil : name, kind: kind,
                                           ts: ts.addingTimeInterval(Double(-i * 60)), dur: 0, sim: sim)
            }
        }
        store.link = s.name.hasSuffix("offline") ? .outOfReach(since: today(14, 2)) : .linked
    }
}
