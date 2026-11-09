// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "ESPHIDKit", platforms: [.iOS(.v17), .macOS(.v14)],
  products: [.library(name: "ESPHIDKit", targets: ["ESPHIDKit"])],
  targets: [
    .target(name: "ESPHIDKit", swiftSettings: [.unsafeFlags(["-strict-concurrency=complete"])]),
    .testTarget(name: "ESPHIDKitTests", dependencies: ["ESPHIDKit"]),
  ], swiftLanguageModes: [.v5])
