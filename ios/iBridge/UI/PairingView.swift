import AVFoundation
import SwiftUI

/// Scan the Android pairing QR, or type its fallback code. No approved mockup yet: stock layout in the A Companion palette.
struct PairingView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    @Environment(\.dismiss) private var dismiss
    @State private var typing = Demo.scenario()?.name == "pairing-code"
    @State private var code = ""
    @State private var waiting = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if typing {
                    TextField("K7Q 29M", text: $code)
                        .font(.app(28, .bold)).tracking(2).multilineTextAlignment(.center)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                        .padding(.vertical, 14)
                        .background(Palette.field, in: RoundedRectangle(cornerRadius: 14))
                    Button {
                        if let c = PairingStart.normalizeCode(code) { waiting = true; central?.pair(.code(c)) }
                    } label: {
                        Text("Pair").font(.app(17, .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity).frame(height: 52).background(Palette.accent, in: Capsule())
                    }
                    .disabled(PairingStart.normalizeCode(code) == nil || waiting)
                } else {
                    QRScanner { text in
                        guard !waiting, let start = PairingStart.parse(text) else { return }
                        waiting = true
                        central?.pair(start)
                    }
                    .frame(maxWidth: .infinity).aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                }
                if let err = store.pairingError {
                    Text(err).font(.app(15)).foregroundStyle(Palette.awayInk)
                } else if waiting {
                    HStack(spacing: 8) { ProgressView(); Text("Linking").font(.app(15)).foregroundStyle(Palette.caption) }
                }
                Button(typing ? "Scan instead" : "Type code instead") { typing.toggle(); store.pairingError = nil; waiting = false }
                    .font(.app(16, .medium)).foregroundStyle(Palette.accent)
                Spacer()
            }
            .padding(24)
            .navigationTitle("Pair with Android")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { central?.cancelPairing(); dismiss() } } }
            .onChange(of: store.pairingError) { if store.pairingError != nil { waiting = false } }
            .onChange(of: store.link) { if store.link == .linked { dismiss() } }
        }
    }
}

/// AVFoundation QR reader. Shows a plain panel where there is no camera (the Simulator).
struct QRScanner: UIViewRepresentable {
    var onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = UIColor(Palette.ink)
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device) else {
            let label = UILabel()
            label.text = String(localized: "No camera")
            label.textColor = .white
            label.translatesAutoresizingMaskIntoConstraints = false
            v.addSubview(label)
            NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: v.centerXAnchor), label.centerYAnchor.constraint(equalTo: v.centerYAnchor)])
            return v
        }
        let session = AVCaptureSession()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        session.addOutput(output)
        output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
        output.metadataObjectTypes = [.qr]
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.session = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) { coordinator.session?.stopRunning() }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let onCode: (String) -> Void
        var session: AVCaptureSession?
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            if let s = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue { onCode(s) }
        }
    }
}
