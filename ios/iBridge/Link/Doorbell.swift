import CoreBluetooth
import Foundation
import os

/// The doorbell (PROTOCOL.md section 1): a write-only characteristic this iPhone hosts so Android can wake a
/// suspended iBridge after Android's app restarts on a radio link that never dropped.
@MainActor
final class Doorbell: NSObject {
    private var manager: CBPeripheralManager!
    private let log = Logger(subsystem: "app.ibridge", category: "link")
    private let isPaired: () -> Bool
    private let onRing: () -> Void
    private var service: CBMutableService?

    init(isPaired: @escaping () -> Bool, onRing: @escaping () -> Void) {
        self.isPaired = isPaired
        self.onRing = onRing
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: .main, options: [CBPeripheralManagerOptionRestoreIdentifierKey: "app.ibridge.doorbell"])
    }

    /// Publishes the doorbell while paired and removes it otherwise. Call after pairing changes.
    func update() {
        guard manager.state == .poweredOn else { return }
        if isPaired(), service == nil {
            let wake = CBMutableCharacteristic(type: Proto.wake, properties: [.write], value: nil, permissions: [.writeEncryptionRequired])
            let s = CBMutableService(type: Proto.doorbell, primary: true)
            s.characteristics = [wake]
            manager.add(s)
            service = s
        } else if !isPaired(), let s = service {
            manager.remove(s)
            service = nil
        }
    }
}

extension Doorbell: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        MainActor.assumeIsolated {
            // Powering off drops published services; publish again on power-on.
            if peripheral.state != .poweredOn { service = nil }
            update()
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        MainActor.assumeIsolated {
            service = (dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService])?.first { $0.uuid == Proto.doorbell }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            if let error {
                log.error("doorbell not published: \(String(describing: error), privacy: .public)")
                self.service = nil
            } else {
                log.notice("doorbell published")
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        MainActor.assumeIsolated {
            guard let first = requests.first else { return }
            peripheral.respond(to: first, withResult: requests.allSatisfy { $0.characteristic.uuid == Proto.wake } ? .success : .requestNotSupported)
            guard requests.contains(where: { $0.characteristic.uuid == Proto.wake }) else { return }
            log.notice("doorbell rang; app state \(Central.appState, privacy: .public)")
            onRing()
        }
    }
}
