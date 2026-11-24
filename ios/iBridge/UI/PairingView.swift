import AVFoundation
import SwiftUI

/// Approved P1 "Viewfinder" (canvas page Screens round 2): the camera fills the screen, typing the code is one tap away.
struct PairingView: View {
    @Environment(Store.self) private var store
    @Environment(\.central) private var central
    @Environment(\.dismiss) private var dismiss
    @State private var typing = Demo.scenario()?.name == "pairing-code"
    @State private var code = ""
    /// Who we are linking to while the handshake runs (the QR carries the Android phone's name).
    @State private var linking: String?
    /// The QR that just failed: ignored until it changes or the user taps the error, so the camera doesn't retry it every frame.
    @State private var failedQR: String?
    @State private var lastQR: String?
    private let hasCamera = AVCaptureDevice.default(for: .video) != nil && AVCaptureDevice.authorizationStatus(for: .video) != .denied

    var body: some View {
        Group {
            if typing { codeEntry } else { viewfinder }
        }
        .preferredColorScheme(typing ? .light : .dark) // light status bar over the camera
        .onChange(of: store.pairingError) { if store.pairingError != nil { failedQR = lastQR; linking = nil } }
        .onChange(of: store.link) { if store.link == .linked { dismiss() } }
    }

    private func cancel() { central?.cancelPairing(); store.pairingError = nil; dismiss() }

    private func switchMode() { typing.toggle(); store.pairingError = nil; linking = nil; failedQR = nil; code = "" }

    // MARK: camera

    private var viewfinder: some View {
        ZStack {
            Palette.camera.ignoresSafeArea()
            QRScanner { text in
                guard linking == nil, text != failedQR, let start = PairingStart.parse(text), case .qr(_, _, let name) = start else { return }
                store.pairingError = nil
                failedQR = nil
                lastQR = text
                linking = name
                central?.pair(start)
            }
            .ignoresSafeArea()
            if hasCamera {
                Color.black.opacity(0.45).ignoresSafeArea().allowsHitTesting(false)
                // Extra shade at the top so Cancel and the title stay readable over a bright feed.
                LinearGradient(colors: [.black.opacity(0.5), .clear], startPoint: .top, endPoint: .center)
                    .ignoresSafeArea().allowsHitTesting(false)
            }
            VStack(spacing: 0) {
                HStack {
                    Button("Cancel", action: cancel)
                        .font(.app(17)).foregroundStyle(.white).frame(height: 44)
                    Spacer()
                }
                Text("Scan the code on your Android phone")
                    .font(.app(28, .bold)).foregroundStyle(.white).multilineTextAlignment(.center)
                    .padding(.top, 24).padding(.horizontal, 8)
                Spacer(minLength: 24)
                Reticle(color: linking == nil ? .white : Palette.scanned).frame(width: 250, height: 250)
                    .overlay {
                        if !hasCamera { Text("Camera not available").font(.app(15)).foregroundStyle(.white.opacity(0.8)) }
                    }
                Spacer(minLength: 24)
                VStack(spacing: 12) {
                    if let card = statusCard { card }
                    if linking == nil {
                        Button(action: switchMode) {
                            Text("Type code instead").font(.app(17, .semibold)).foregroundStyle(.white)
                                .padding(.horizontal, 24).frame(height: 50)
                                .background(.white.opacity(0.18), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 24)
        }
        .environment(\.colorScheme, .dark)
    }

    // MARK: typed code

    private var codeEntry: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Cancel", action: cancel)
                Spacer()
                Button("Scan instead", action: switchMode)
            }
            .font(.app(17)).foregroundStyle(Palette.accent).frame(height: 44)
            VStack(spacing: 28) {
                Text("Type the code under the QR")
                    .font(.app(28, .bold)).foregroundStyle(Palette.ink).multilineTextAlignment(.center)
                CodeBoxes(code: $code)
                Button {
                    guard let c = PairingStart.normalizeCode(code) else { return }
                    store.pairingError = nil
                    linking = ""
                    central?.pair(.code(c))
                } label: {
                    Text("Pair").font(.app(17, .semibold)).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).frame(height: 52).background(Palette.accent, in: Capsule())
                }
                .buttonStyle(FilledButtonStyle())
                .disabled(PairingStart.normalizeCode(code) == nil || linking != nil)
                if let card = statusCard { card }
            }
            .padding(.horizontal, 8).padding(.top, 24)
            Spacer()
        }
        .padding(.horizontal, 16).padding(.top, 6)
        .background(Color.white)
    }

    // MARK: linking / error

    private var statusCard: StatusCard? {
        if let err = store.pairingError {
            // The error strings are "What happened. What to do." The first sentence is the title.
            let parts = err.split(separator: ".", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            return StatusCard(kind: .problem, title: parts.first ?? err, detail: parts.count > 1 ? parts[1] : nil) {
                store.pairingError = nil; failedQR = nil // tap to try the same QR again
            }
        }
        guard let name = linking else { return nil }
        let title = name.isEmpty ? String(localized: "Linking to your Android phone") : String(localized: "Linking to \(name)")
        return StatusCard(kind: .working, title: title, detail: String(localized: "Keep both phones close."))
    }
}

/// Six boxes, 3 + 3, over one hidden text field. The code reads left to right in every language.
private struct CodeBoxes: View {
    @Binding var code: String
    @FocusState private var focused: Bool

    var body: some View {
        let chars = Array(code)
        HStack(spacing: 6) {
            ForEach(0..<6, id: \.self) { i in
                Text(i < chars.count ? String(chars[i]) : "")
                    .font(.app(28, .bold)).foregroundStyle(Palette.ink)
                    .frame(width: 44, height: 56)
                    .background(Palette.field, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(i == min(chars.count, 5) && focused ? Palette.accent : .clear, lineWidth: 2))
                if i == 2 { Spacer().frame(width: 12) }
            }
        }
        .environment(\.layoutDirection, .leftToRight)
        .background {
            TextField("", text: $code)
                .textInputAutocapitalization(.characters).autocorrectionDisabled().keyboardType(.asciiCapable)
                .focused($focused).opacity(0.02).allowsHitTesting(false)
                .onChange(of: code) {
                    let clean = String(code.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(6))
                    if clean != code { code = clean }
                }
        }
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
        .onAppear { focused = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Pairing code, 6 characters")
        .accessibilityValue(code)
    }
}

/// Linking (green, spinner) or a problem (orange), on the camera or under the code boxes.
struct StatusCard: View {
    enum Kind { case working, problem }
    var kind: Kind
    var title: String
    var detail: String?
    var onTap: (() -> Void)? = nil

    var body: some View {
        if let onTap {
            Button(action: onTap) { card }.buttonStyle(.plain)
        } else {
            card
        }
    }

    @ViewBuilder private var card: some View {
        let ink = kind == .working ? Palette.linkedInk : Palette.awayInk
        HStack(spacing: 12) {
            if kind == .working {
                ProgressView().tint(ink)
            } else {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.away)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.app(17, .semibold))
                if let detail { Text(detail).font(.app(15)) }
            }
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(kind == .working ? Palette.linkedBg : Palette.awayBg, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }
}

/// Four corner brackets.
private struct Reticle: View {
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            let l: CGFloat = 34, w = size.width, h = size.height, i: CGFloat = 2
            var p = Path()
            p.move(to: .init(x: i, y: l)); p.addLine(to: .init(x: i, y: i)); p.addLine(to: .init(x: l, y: i))
            p.move(to: .init(x: w - l, y: i)); p.addLine(to: .init(x: w - i, y: i)); p.addLine(to: .init(x: w - i, y: l))
            p.move(to: .init(x: w - i, y: h - l)); p.addLine(to: .init(x: w - i, y: h - i)); p.addLine(to: .init(x: w - l, y: h - i))
            p.move(to: .init(x: l, y: h - i)); p.addLine(to: .init(x: i, y: h - i)); p.addLine(to: .init(x: i, y: h - l))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}

/// AVFoundation QR reader. Shows a plain panel where there is no camera (the Simulator).
struct QRScanner: UIViewRepresentable {
    var onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .clear
        // No camera (the Simulator) or no permission: the dark surface behind stays, "Type code instead" still works.
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device) else { return v }
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
