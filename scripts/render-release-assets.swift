import AppKit
import Foundation

// Release artwork uses the launcher icon and supplied in-app screenshot.
let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let output = root.appendingPathComponent("docs/assets/releases")
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
func color(_ hex: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 255) / 255,
            green: CGFloat((hex >> 8) & 255) / 255,
            blue: CGFloat(hex & 255) / 255, alpha: 1)
}
func text(_ value: String, at point: NSPoint, size: CGFloat, weight: NSFont.Weight, tint: NSColor) {
    NSAttributedString(string: value, attributes: [.font: NSFont.systemFont(ofSize: size, weight: weight),
        .foregroundColor: tint]).draw(at: point)
}
func canvas(_ name: String, width: Int, height: Int, draw: () -> Void) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    NSGraphicsContext.current?.imageInterpolation = .high
    draw()
    NSGraphicsContext.restoreGraphicsState()
    try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(name))
}
try canvas("luno-mobile-banner.png", width: 1600, height: 400) {
    let bounds = NSRect(x: 0, y: 0, width: 1600, height: 400)
    let frame = NSBezierPath(roundedRect: bounds, xRadius: 28, yRadius: 28)
    NSGradient(starting: color(0x171322), ending: color(0x0C0C13))!.draw(in: frame, angle: 0)
    let screenshot = NSImage(contentsOf: output.appendingPathComponent("luno-mobile-screen.jpg"))!
    let deviceBounds = NSRect(x: 1328, y: 5, width: 204, height: 390)
    let device = NSBezierPath(roundedRect: deviceBounds, xRadius: 30, yRadius: 30)
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = NSColor.black.withAlphaComponent(0.7)
    shadow.shadowBlurRadius = 22
    shadow.shadowOffset = NSSize(width: 0, height: -8)
    shadow.set()
    color(0x17141D).setFill()
    device.fill()
    NSGraphicsContext.restoreGraphicsState()
    color(0x5D4A7B).setStroke()
    device.lineWidth = 2
    device.stroke()

    let screenHeight: CGFloat = 360
    let screenWidth = screenHeight * screenshot.size.width / screenshot.size.height
    let screenBounds = NSRect(x: deviceBounds.midX - screenWidth / 2, y: 20,
        width: screenWidth, height: screenHeight)
    let screen = NSBezierPath(roundedRect: screenBounds, xRadius: 12, yRadius: 12)
    NSGraphicsContext.saveGraphicsState()
    screen.addClip()
    screenshot.draw(in: screenBounds, from: NSRect(origin: .zero, size: screenshot.size),
        operation: .sourceOver, fraction: 1)
    NSGraphicsContext.restoreGraphicsState()
    let icon = NSImage(contentsOf: root.appendingPathComponent("app/src/main/res/drawable-nodpi/luno_launcher_art.png"))!
    NSGraphicsContext.saveGraphicsState()
    let iconBounds = NSRect(x: 62, y: 100, width: 200, height: 200)
    NSBezierPath(roundedRect: iconBounds, xRadius: 42, yRadius: 42).addClip()
    icon.draw(in: iconBounds, from: NSRect(x: icon.size.width * 0.1, y: icon.size.height * 0.1,
        width: icon.size.width * 0.8, height: icon.size.height * 0.8), operation: .sourceOver, fraction: 1)
    NSGraphicsContext.restoreGraphicsState()
    text("LUNO FOR ANDROID", at: NSPoint(x: 310, y: 298), size: 20, weight: .semibold, tint: color(0xB89AFF))
    text("Luno Mobile", at: NSPoint(x: 305, y: 175), size: 88, weight: .bold, tint: color(0xFAF8FF))
    text("Your music. Your library.", at: NSPoint(x: 310, y: 112), size: 32, weight: .regular, tint: color(0xC5BFCE))
    text("Local listening  /  Playlists  /  Music discovery", at: NSPoint(x: 310, y: 58), size: 21,
        weight: .regular, tint: color(0x92899F))
}
try canvas("download-android.png", width: 640, height: 128) {
    color(0x8044E8).setFill()
    NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: 640, height: 128), xRadius: 24, yRadius: 24).fill()
    NSColor.white.setStroke()
    let arrow = NSBezierPath()
    arrow.lineWidth = 5
    arrow.lineCapStyle = .round
    arrow.lineJoinStyle = .round
    arrow.move(to: NSPoint(x: 58, y: 85)); arrow.line(to: NSPoint(x: 58, y: 48))
    arrow.move(to: NSPoint(x: 43, y: 62)); arrow.line(to: NSPoint(x: 58, y: 47)); arrow.line(to: NSPoint(x: 73, y: 62))
    arrow.move(to: NSPoint(x: 38, y: 41)); arrow.line(to: NSPoint(x: 38, y: 32))
    arrow.line(to: NSPoint(x: 78, y: 32)); arrow.line(to: NSPoint(x: 78, y: 41)); arrow.stroke()
    text("Download for Android", at: NSPoint(x: 108, y: 40), size: 43, weight: .semibold, tint: .white)
}
print("Rendered release banner and download button.")
