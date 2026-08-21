import AVFoundation
import SwiftUI

/// Reading the pairing code off a television with the camera.
///
/// AVFoundation recognises QR codes itself, so this ships no decoder and no
/// dependency — the capture session is asked for `.qr` metadata and hands back
/// strings. That is also why this is a `UIViewRepresentable` rather than
/// anything fancier: the work is entirely in the session, and SwiftUI only
/// needs somewhere to put the preview layer.
///
/// The session is stopped on the way out, without exception. A view that leaves
/// capture running leaves the camera indicator lit, which on a phone reads as
/// the app watching you.
struct QrScannerView: View {
    let onCode: (String) -> Void

    @State private var authorised = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    @State private var refused = AVCaptureDevice.authorizationStatus(for: .video) == .denied
        || AVCaptureDevice.authorizationStatus(for: .video) == .restricted

    var body: some View {
        Group {
            if authorised {
                CameraLayer(onCode: onCode)
                    // Square, because a QR is square; a full-frame preview
                    // spends most of its height on the wall around the set.
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(.white.opacity(0.6), lineWidth: 2)
                    )
            } else {
                Text(refused
                     ? "Camera access was refused. Allow it in Settings, or type the code."
                     : "Waiting for camera access…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .task {
            guard !authorised, !refused else { return }
            // Asked here rather than at launch: it is wanted for one button on
            // one screen, and the code can always be typed instead.
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            authorised = granted
            refused = !granted
        }
    }
}

private struct CameraLayer: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        context.coordinator.start(in: view)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        // The callback is replaced rather than the session rebuilt: a new
        // closure every render would otherwise restart the camera every render.
        context.coordinator.onCode = onCode
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }

    /// A view whose backing layer *is* the preview layer, so it resizes with
    /// the view instead of needing its frame kept in step by hand.
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: (String) -> Void
        private let session = AVCaptureSession()
        // Configuration and teardown are serialised off the main thread;
        // startRunning() blocks, and on the main thread that is a visible hitch.
        private let queue = DispatchQueue(label: "streamhub.scanner")
        private var delivered = false

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func start(in view: PreviewView) {
            view.previewLayer.session = session
            view.previewLayer.videoGravity = .resizeAspectFill

            queue.async { [weak self] in
                guard let self else { return }
                self.session.beginConfiguration()

                guard
                    let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                    let input = try? AVCaptureDeviceInput(device: device),
                    self.session.canAddInput(input)
                else {
                    self.session.commitConfiguration()
                    return
                }
                self.session.addInput(input)

                let output = AVCaptureMetadataOutput()
                guard self.session.canAddOutput(output) else {
                    self.session.commitConfiguration()
                    return
                }
                self.session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
                // Set after the output is attached — before, .qr is not yet an
                // available type and assigning it throws.
                output.metadataObjectTypes = [.qr]

                self.session.commitConfiguration()
                self.session.startRunning()
            }
        }

        func stop() {
            queue.async { [session] in
                if session.isRunning { session.stopRunning() }
            }
        }

        func metadataOutput(
            _ output: AVCaptureMetadataOutput,
            didOutput metadataObjects: [AVMetadataObject],
            from connection: AVCaptureConnection
        ) {
            guard !delivered else { return }
            for object in metadataObjects {
                guard
                    let readable = object as? AVMetadataMachineReadableCodeObject,
                    // Anything that is not one of our codes is ignored rather
                    // than reported: a camera pointed at a room finds wifi codes
                    // and packaging, and stopping on each would be unusable.
                    let code = UserCode.fromScan(readable.stringValue)
                else { continue }
                delivered = true
                stop()
                onCode(code)
                return
            }
        }
    }
}
