#!/usr/bin/env swift
//
//  The StreamHub mark, and every file that needs it.
//
//  Run from the repository root:
//      swift shared/brand/IconTool.swift
//
//  Why a generator rather than committed art: the same mark has to exist as an
//  SVG for the browser tab, three vector drawables for Android, and PNGs from
//  16px to 1024px for iOS and the web. Drawing it once and emitting all of them
//  is the only way those stay the same shape — hand-maintained copies drift,
//  and the drift shows up as a favicon that does not match the app icon.
//
//  macOS only. It uses CoreGraphics to rasterise and CoreText to set the
//  television banner's wordmark; the outputs are committed, so nobody has to
//  run it to build any of the clients.

import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

// MARK: - The design

/// Everything about the mark, in one place, on a 1024 canvas.
enum Design {
    static let canvas: CGFloat = 1024

    /// A play triangle, optically centred.
    ///
    /// Geometrically centring a triangle looks wrong — its mass sits toward the
    /// flat edge — so it is placed by its centroid instead, which is a third of
    /// the way in from the left edge rather than half.
    static let markWidth: CGFloat = 430
    static let markHeight: CGFloat = 486

    /// Rounded vertices. Sharp points on a play mark read as cheap at large
    /// sizes and turn to needles at small ones.
    static let markCornerRadius: CGFloat = 48

    /// The tile's corner, for the contexts that are not masked by the system.
    /// Smaller than Apple's squircle on purpose: at 16 pixels a large radius
    /// eats the shape and the icon reads as a circle.
    static let tileCornerRadius: CGFloat = 200

    /// The tile is near-black, not red.
    ///
    /// A red tile with a white play triangle is what YouTube is, and the
    /// launcher masks the tile to a circle — which strips away every other
    /// distinguishing detail and leaves exactly that. It also contradicted the
    /// product: the web client, both Android apps and the iOS app are all
    /// near-black surfaces with red as the accent, so a red-dominant icon was
    /// the one place StreamHub did not look like itself.
    static let topLeft = (r: 0.129, g: 0.129, b: 0.137)     // #212123
    static let bottomRight = (r: 0.027, g: 0.027, b: 0.031) // #070708

    /// The mark carries the brand colour instead. `--accent` in the web
    /// client is #E50914; these bracket it.
    static let markTop = (r: 1.00, g: 0.153, b: 0.200)      // #FF2733
    static let markBottom = (r: 0.796, g: 0.020, b: 0.055)  // #CB050E
}

// MARK: - Geometry

struct Point {
    var x: CGFloat
    var y: CGFloat
}

/// The play mark as a rounded triangle, in y-down coordinates.
///
/// Returned as both a `CGPath` and path data, so the raster and the two vector
/// formats are literally the same numbers rather than three transcriptions.
func markPath(canvas: CGFloat, inset: CGFloat = 1.0) -> (path: CGPath, data: String) {
    let scale = canvas / Design.canvas
    let width = Design.markWidth * scale * inset
    let height = Design.markHeight * scale * inset
    let radius = Design.markCornerRadius * scale * inset

    let centre = canvas / 2
    // Placed by centroid, which for this triangle sits a third of the width in
    // from the flat edge.
    let left = centre - width / 3
    let right = left + width
    let top = centre - height / 2
    let bottom = centre + height / 2

    // Clockwise in y-down space, which is what makes every arc a sweep of 1.
    let corners = [
        Point(x: left, y: top),
        Point(x: right, y: centre),
        Point(x: left, y: bottom),
    ]

    var data = ""
    let path = CGMutablePath()

    for index in corners.indices {
        let vertex = corners[index]
        let previous = corners[(index + corners.count - 1) % corners.count]
        let next = corners[(index + 1) % corners.count]

        let toPrevious = normalise(Point(x: previous.x - vertex.x, y: previous.y - vertex.y))
        let toNext = normalise(Point(x: next.x - vertex.x, y: next.y - vertex.y))

        // How far back from the corner the arc has to start for a circle of
        // this radius to sit tangent to both edges.
        let halfAngle = acos(max(-1, min(1, toPrevious.x * toNext.x + toPrevious.y * toNext.y))) / 2
        let tangent = radius / tan(halfAngle)

        let start = Point(x: vertex.x + toPrevious.x * tangent, y: vertex.y + toPrevious.y * tangent)
        let end = Point(x: vertex.x + toNext.x * tangent, y: vertex.y + toNext.y * tangent)

        if index == 0 {
            data += "M\(f(start.x)),\(f(start.y))"
            path.move(to: CGPoint(x: start.x, y: start.y))
        } else {
            data += " L\(f(start.x)),\(f(start.y))"
            path.addLine(to: CGPoint(x: start.x, y: start.y))
        }
        data += " A\(f(radius)),\(f(radius)) 0 0 1 \(f(end.x)),\(f(end.y))"
        path.addArc(
            tangent1End: CGPoint(x: vertex.x, y: vertex.y),
            tangent2End: CGPoint(x: next.x, y: next.y),
            radius: radius
        )
    }
    data += " Z"
    path.closeSubpath()
    return (path, data)
}

func normalise(_ point: Point) -> Point {
    let length = sqrt(point.x * point.x + point.y * point.y)
    guard length > 0 else { return point }
    return Point(x: point.x / length, y: point.y / length)
}

/// Trimmed so the emitted path data does not carry meaningless precision.
func f(_ value: CGFloat) -> String {
    let rounded = (value * 100).rounded() / 100
    return rounded == rounded.rounded()
        ? String(Int(rounded))
        : String(format: "%.2f", rounded)
}

// MARK: - Drawing

enum Tile {
    case brand
    case dark
    case grayscale
    case transparent
}

func drawIcon(size: CGFloat, tile: Tile, cornerRadius: CGFloat) -> CGImage? {
    let pixels = Int(size)
    guard let context = CGContext(
        data: nil,
        width: pixels,
        height: pixels,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }

    // CoreGraphics is y-up; every number in this file is y-down.
    context.translateBy(x: 0, y: size)
    context.scaleBy(x: 1, y: -1)
    context.setAllowsAntialiasing(true)
    context.interpolationQuality = .high

    let bounds = CGRect(x: 0, y: 0, width: size, height: size)
    let clip = CGPath(roundedRect: bounds, cornerWidth: cornerRadius, cornerHeight: cornerRadius, transform: nil)

    context.saveGState()
    context.addPath(clip)
    context.clip()

    switch tile {
    case .brand, .dark:
        fillDiagonal(context, bounds, from: Design.topLeft, to: Design.bottomRight)
        // A soft light from the upper left. Flat colour looks like a swatch;
        // this reads as a surface without tipping into a dated bevel.
        addHighlight(context, bounds, alpha: 0.10)
        addStreams(context, bounds, alpha: 0.07)
    case .grayscale:
        setFill(context, (r: 0.10, g: 0.10, b: 0.10), alpha: 1)
        context.fill(bounds)
    case .transparent:
        break
    }
    context.restoreGState()

    let mark = markPath(canvas: size).path
    switch tile {
    case .brand, .dark:
        // A glow rather than a drop shadow: the mark is the bright thing on a
        // dark tile, so light coming off it is what reads as depth. Faint
        // enough to vanish at favicon sizes instead of smearing.
        context.saveGState()
        context.setShadow(
            offset: .zero,
            blur: size * 0.055,
            color: CGColor(srgbRed: 1, green: 0.11, blue: 0.16, alpha: 0.45)
        )
        context.addPath(mark)
        setFill(context, Design.markBottom, alpha: 1)
        context.fillPath()
        context.restoreGState()

        context.saveGState()
        context.addPath(mark)
        context.clip()
        let box = mark.boundingBox
        fillDiagonal(context, box, from: Design.markTop, to: Design.markBottom)
        context.restoreGState()
    case .grayscale, .transparent:
        context.addPath(mark)
        setFill(context, (r: 1, g: 1, b: 1), alpha: 1)
        context.fillPath()
    }

    return context.makeImage()
}

func fillDiagonal(
    _ context: CGContext,
    _ bounds: CGRect,
    from: (r: Double, g: Double, b: Double),
    to: (r: Double, g: Double, b: Double)
) {
    let colours = [
        CGColor(srgbRed: from.r, green: from.g, blue: from.b, alpha: 1),
        CGColor(srgbRed: to.r, green: to.g, blue: to.b, alpha: 1),
    ] as CFArray
    guard let gradient = CGGradient(
        colorsSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
        colors: colours,
        locations: [0, 1]
    ) else { return }
    context.drawLinearGradient(
        gradient,
        start: CGPoint(x: bounds.minX, y: bounds.minY),
        end: CGPoint(x: bounds.maxX, y: bounds.maxY),
        options: []
    )
}

func addHighlight(_ context: CGContext, _ bounds: CGRect, alpha: Double) {
    let colours = [
        CGColor(srgbRed: 1, green: 1, blue: 1, alpha: alpha),
        CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 0),
    ] as CFArray
    guard let gradient = CGGradient(
        colorsSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
        colors: colours,
        locations: [0, 1]
    ) else { return }
    let centre = CGPoint(x: bounds.width * 0.30, y: bounds.height * 0.22)
    context.drawRadialGradient(
        gradient,
        startCenter: centre,
        startRadius: 0,
        endCenter: centre,
        endRadius: bounds.width * 0.78,
        options: []
    )
}

/// Two soft bands sweeping across the tile.
///
/// This is where the icon stops being a stock play button. The mark itself has
/// to stay a plain solid — anything cut into it turns to fringing at sixteen
/// pixels — so the character goes into the surface behind it instead, where it
/// simply fades out as the icon shrinks and costs nothing.
func addStreams(_ context: CGContext, _ bounds: CGRect, alpha: Double) {
    let colours = [
        CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 0),
        CGColor(srgbRed: 1, green: 1, blue: 1, alpha: alpha),
        CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 0),
    ] as CFArray
    guard let gradient = CGGradient(
        colorsSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
        colors: colours,
        locations: [0, 0.5, 1]
    ) else { return }

    let size = bounds.width
    context.saveGState()
    context.translateBy(x: size / 2, y: size / 2)
    context.rotate(by: -.pi / 5.2)

    // Offset from the centre so the bands read as passing behind the mark
    // rather than framing it.
    for (offset, thickness) in [(-0.30, 0.115), (0.20, 0.055)] as [(CGFloat, CGFloat)] {
        let band = CGRect(
            x: -size,
            y: size * offset,
            width: size * 2,
            height: size * thickness
        )
        context.saveGState()
        context.addPath(CGPath(
            roundedRect: band,
            cornerWidth: band.height / 2,
            cornerHeight: band.height / 2,
            transform: nil
        ))
        context.clip()
        context.drawLinearGradient(
            gradient,
            start: CGPoint(x: band.minX, y: 0),
            end: CGPoint(x: band.maxX, y: 0),
            options: []
        )
        context.restoreGState()
    }
    context.restoreGState()
}

func setFill(_ context: CGContext, _ colour: (r: Double, g: Double, b: Double), alpha: Double) {
    context.setFillColor(CGColor(srgbRed: colour.r, green: colour.g, blue: colour.b, alpha: alpha))
}

// MARK: - The television banner

/// Android TV shows a banner in the launcher, not an icon, and the guidance is
/// that it carries the app's name. Drawn here rather than as a vector drawable
/// because vector drawables have no text element, and outlining a wordmark by
/// hand produces worse typography than simply setting it.
func drawBanner(width: CGFloat, height: CGFloat) -> CGImage? {
    guard let context = CGContext(
        data: nil,
        width: Int(width),
        height: Int(height),
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }

    let bounds = CGRect(x: 0, y: 0, width: width, height: height)
    fillDiagonal(context, bounds, from: Design.topLeft, to: Design.bottomRight)
    addHighlight(context, bounds, alpha: 0.14)

    // The mark, sized to the banner's height and set on the left.
    let markBox = height * 0.46
    context.saveGState()
    context.translateBy(x: width * 0.085, y: (height - markBox) / 2)
    context.translateBy(x: 0, y: markBox)
    context.scaleBy(x: 1, y: -1)
    let bannerMark = markPath(canvas: markBox).path
    context.saveGState()
    context.addPath(bannerMark)
    context.clip()
    fillDiagonal(context, bannerMark.boundingBox, from: Design.markTop, to: Design.markBottom)
    context.restoreGState()
    context.restoreGState()

    let font = CTFontCreateUIFontForLanguage(.emphasizedSystem, height * 0.20, nil)
        ?? CTFontCreateWithName("Helvetica-Bold" as CFString, height * 0.20, nil)
    // CoreText's own attribute names rather than AppKit's: this is a command
    // line tool and has no business loading a UI framework to set nine letters.
    let attributes: [CFString: Any] = [
        kCTFontAttributeName: font,
        kCTForegroundColorAttributeName: CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1),
        kCTKernAttributeName: -height * 0.006,
    ]
    let attributed = CFAttributedStringCreate(
        nil,
        "StreamHub" as CFString,
        attributes as CFDictionary
    )!
    let line = CTLineCreateWithAttributedString(attributed)
    let textBounds = CTLineGetBoundsWithOptions(line, .useOpticalBounds)
    context.textPosition = CGPoint(
        x: width * 0.085 + markBox + width * 0.055,
        y: (height - textBounds.height) / 2 - textBounds.minY
    )
    CTLineDraw(line, context)

    return context.makeImage()
}

// MARK: - Emitting

let root = FileManager.default.currentDirectoryPath

func write(_ image: CGImage?, to path: String) {
    guard let image else { fatalError("could not render \(path)") }
    let url = URL(fileURLWithPath: root).appendingPathComponent(path)
    try? FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )
    guard let destination = CGImageDestinationCreateWithURL(
        url as CFURL, UTType.png.identifier as CFString, 1, nil
    ) else { fatalError("could not open \(path)") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("could not write \(path)") }
    print("  \(path)")
}

func write(_ text: String, to path: String) {
    let url = URL(fileURLWithPath: root).appendingPathComponent(path)
    try? FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )
    try! text.write(to: url, atomically: true, encoding: .utf8)
    print("  \(path)")
}

func hex(_ colour: (r: Double, g: Double, b: Double)) -> String {
    String(
        format: "#%02X%02X%02X",
        Int((colour.r * 255).rounded()),
        Int((colour.g * 255).rounded()),
        Int((colour.b * 255).rounded())
    )
}

/// The master, and what the browser tab actually loads.
///
/// Every number here is the same one the rasteriser uses, so the tab icon and
/// the app icon are the same drawing rather than two that merely resemble each
/// other.
func svg(cornerRadius: CGFloat) -> String {
    let size = Design.canvas
    let mark = markPath(canvas: size).data

    // The bands, matching addStreams. Rotation is negated because SVG measures
    // a positive angle clockwise in the same y-down space the rasteriser flips
    // into, where the equivalent call takes a negative one.
    let bands = [(offset: -0.30, thickness: 0.115), (offset: 0.20, thickness: 0.055)]
        .map { band -> String in
            let height = size * CGFloat(band.thickness)
            let y = size * CGFloat(band.offset)
            return """
                <rect x="\(f(-size))" y="\(f(y))" width="\(f(size * 2))" height="\(f(height))" \
            rx="\(f(height / 2))" fill="url(#stream)"/>
            """
        }
        .joined(separator: "\n    ")

    return """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" role="img" aria-label="StreamHub">
      <defs>
        <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="\(hex(Design.topLeft))"/>
          <stop offset="1" stop-color="\(hex(Design.bottomRight))"/>
        </linearGradient>
        <linearGradient id="mark" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="\(hex(Design.markTop))"/>
          <stop offset="1" stop-color="\(hex(Design.markBottom))"/>
        </linearGradient>
        <radialGradient id="light" cx="0.30" cy="0.22" r="0.78">
          <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.10"/>
          <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
        </radialGradient>
        <linearGradient id="stream" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stop-color="#FFFFFF" stop-opacity="0"/>
          <stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.07"/>
          <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
        </linearGradient>
        <clipPath id="tileShape">
          <rect width="1024" height="1024" rx="\(f(cornerRadius))"/>
        </clipPath>
        <filter id="glow" x="-25%" y="-25%" width="150%" height="150%">
          <feDropShadow dx="0" dy="0" stdDeviation="\(f(size * 0.0275))"
                        flood-color="\(hex(Design.markTop))" flood-opacity="0.45"/>
        </filter>
      </defs>
      <g clip-path="url(#tileShape)">
        <rect width="1024" height="1024" fill="url(#tile)"/>
        <rect width="1024" height="1024" fill="url(#light)"/>
        <g transform="translate(512 512) rotate(-34.6)">
          \(bands)
        </g>
      </g>
      <path d="\(mark)" fill="url(#mark)" filter="url(#glow)"/>
    </svg>

    """
}

/// Android's adaptive icon: two layers the launcher masks and parallaxes
/// separately. The mark is drawn smaller than on a full-bleed tile because the
/// mask crops to roughly two thirds of the canvas.
func androidForeground() -> String {
    let mark = markPath(canvas: 108, inset: 0.62).data
    return """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by shared/brand/IconTool.swift. Do not edit by hand. -->
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:aapt="http://schemas.android.com/aapt"
        android:width="108dp"
        android:height="108dp"
        android:viewportWidth="108"
        android:viewportHeight="108">
        <path android:pathData="\(mark)">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="36"
                    android:startY="28"
                    android:endX="72"
                    android:endY="80">
                    <item android:offset="0" android:color="\(hex(Design.markTop))" />
                    <item android:offset="1" android:color="\(hex(Design.markBottom))" />
                </gradient>
            </aapt:attr>
        </path>
    </vector>

    """
}

func androidBackground() -> String {
    """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by shared/brand/IconTool.swift. Do not edit by hand. -->
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        xmlns:aapt="http://schemas.android.com/aapt"
        android:width="108dp"
        android:height="108dp"
        android:viewportWidth="108"
        android:viewportHeight="108">
        <path android:pathData="M0,0h108v108h-108z">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="0"
                    android:startY="0"
                    android:endX="108"
                    android:endY="108">
                    <item android:offset="0" android:color="\(hex(Design.topLeft))" />
                    <item android:offset="1" android:color="\(hex(Design.bottomRight))" />
                </gradient>
            </aapt:attr>
        </path>
    </vector>

    """
}

func androidAdaptive(round: Bool) -> String {
    """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by shared/brand/IconTool.swift. Do not edit by hand. -->
    <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@drawable/ic_launcher_background" />
        <foreground android:drawable="@drawable/ic_launcher_foreground" />
        <monochrome android:drawable="@drawable/ic_launcher_foreground" />
    </adaptive-icon>

    """
}

// MARK: - Run

print("brand")
write(svg(cornerRadius: Design.tileCornerRadius), to: "shared/brand/icon.svg")
write(svg(cornerRadius: 0), to: "shared/brand/icon-square.svg")

print("web")
// The SVG is what modern browsers use; the PNGs are for the ones that do not,
// and for the home-screen bookmark, which is masked by iOS and so full bleed.
write(drawIcon(size: 32, tile: .brand, cornerRadius: 200 * 32 / 1024), to: "frontend/public/favicon-32.png")
write(drawIcon(size: 16, tile: .brand, cornerRadius: 200 * 16 / 1024), to: "frontend/public/favicon-16.png")
write(drawIcon(size: 180, tile: .brand, cornerRadius: 0), to: "frontend/public/apple-touch-icon.png")
write(drawIcon(size: 192, tile: .brand, cornerRadius: 200 * 192 / 1024), to: "frontend/public/icon-192.png")
write(drawIcon(size: 512, tile: .brand, cornerRadius: 200 * 512 / 1024), to: "frontend/public/icon-512.png")
write(svg(cornerRadius: Design.tileCornerRadius), to: "frontend/public/favicon.svg")

print("ios")
// Full bleed and square: iOS applies its own mask, and a pre-rounded icon shows
// a second corner inside the system's.
write(drawIcon(size: 1024, tile: .brand, cornerRadius: 0), to: "ios/StreamHub/Assets.xcassets/AppIcon.appiconset/icon-1024.png")
write(drawIcon(size: 1024, tile: .dark, cornerRadius: 0), to: "ios/StreamHub/Assets.xcassets/AppIcon.appiconset/icon-1024-dark.png")
write(drawIcon(size: 1024, tile: .grayscale, cornerRadius: 0), to: "ios/StreamHub/Assets.xcassets/AppIcon.appiconset/icon-1024-tinted.png")

print("android")
for module in ["mobile", "tv"] {
    let res = "android/\(module)/src/main/res"
    write(androidForeground(), to: "\(res)/drawable/ic_launcher_foreground.xml")
    write(androidBackground(), to: "\(res)/drawable/ic_launcher_background.xml")
    write(androidAdaptive(round: false), to: "\(res)/mipmap-anydpi-v26/ic_launcher.xml")
    write(androidAdaptive(round: true), to: "\(res)/mipmap-anydpi-v26/ic_launcher_round.xml")
    // A launcher that ignores the adaptive form still needs something to show.
    for (density, size) in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)] {
        let radius = CGFloat(size) * 200 / 1024
        write(drawIcon(size: CGFloat(size), tile: .brand, cornerRadius: radius), to: "\(res)/mipmap-\(density)/ic_launcher.png")
        write(drawIcon(size: CGFloat(size), tile: .brand, cornerRadius: CGFloat(size) / 2), to: "\(res)/mipmap-\(density)/ic_launcher_round.png")
    }
}
// The television launcher wants a 320x180 banner, at xhdpi.
write(drawBanner(width: 320, height: 180), to: "android/tv/src/main/res/drawable-xhdpi/banner.png")
write(drawBanner(width: 640, height: 360), to: "android/tv/src/main/res/drawable-xxhdpi/banner.png")

print("done")
