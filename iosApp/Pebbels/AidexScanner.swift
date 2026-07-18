/*
 * iOS-Kamera-Scanner für die AiDEX-Seriennummer (QR-Code auf dem Karton).
 * AVFoundation — Gegenstück zum Android-ML-Kit-Scanner.
 *
 * WICHTIG: In der Info.plist muss NSCameraUsageDescription gesetzt sein, sonst
 * beendet iOS die App beim Kamerazugriff. Text z. B.:
 *   "Zum Einscannen der AiDEX-Seriennummer wird die Kamera benötigt."
 */
import UIKit
import AVFoundation

final class AidexScanner: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    private let captureSession = AVCaptureSession()
    private var onResult: ((String) -> Void)?
    private var done = false

    static func present(from root: UIViewController, onResult: @escaping (String) -> Void) {
        let vc = AidexScanner()
        vc.onResult = onResult
        vc.modalPresentationStyle = .fullScreen
        root.present(vc, animated: true)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              captureSession.canAddInput(input) else {
            addHint("Kamera nicht verfügbar"); addCloseButton(); return
        }
        captureSession.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard captureSession.canAddOutput(output) else { addCloseButton(); return }
        captureSession.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr, .code128, .dataMatrix, .ean13, .code39]

        let preview = AVCaptureVideoPreviewLayer(session: captureSession)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        addHint("QR-Code auf dem Karton anvisieren")
        addCloseButton()
        DispatchQueue.global(qos: .userInitiated).async { self.captureSession.startRunning() }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if captureSession.isRunning { captureSession.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !done,
              let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let raw = obj.stringValue,
              let sn = extractSn(raw) else { return }
        done = true
        captureSession.stopRunning()
        onResult?(sn)
        dismiss(animated: true)
    }

    /// Aus dem QR-Inhalt die SN ziehen: bevorzugt eine 10-stellige alphanumerische Kennung.
    private func extractSn(_ raw: String) -> String? {
        let up = raw.uppercased()
        if let m = up.range(of: "[0-9A-Z]{10}", options: .regularExpression) { return String(up[m]) }
        let clean = up.filter { $0.isLetter || $0.isNumber }
        return (6...14).contains(clean.count) ? clean : nil
    }

    private func addHint(_ text: String) {
        let l = UILabel()
        l.text = text; l.textColor = .white; l.textAlignment = .center; l.numberOfLines = 2
        l.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(l)
        NSLayoutConstraint.activate([
            l.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            l.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            l.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -40),
        ])
    }

    private func addCloseButton() {
        let b = UIButton(type: .system)
        b.setTitle("Abbrechen", for: .normal)
        b.setTitleColor(.white, for: .normal)
        b.translatesAutoresizingMaskIntoConstraints = false
        b.addTarget(self, action: #selector(closeTap), for: .touchUpInside)
        view.addSubview(b)
        NSLayoutConstraint.activate([
            b.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            b.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
        ])
    }

    @objc private func closeTap() {
        if captureSession.isRunning { captureSession.stopRunning() }
        dismiss(animated: true)
    }
}
