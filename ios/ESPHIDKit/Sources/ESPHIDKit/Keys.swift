import Foundation

public struct MouseButtons: OptionSet, Sendable, Hashable {
  public let rawValue: UInt8
  public init(rawValue: UInt8) { self.rawValue = rawValue }
  public static let left = Self(rawValue: 1)
  public static let right = Self(rawValue: 2)
  public static let middle = Self(rawValue: 4)
  public static let back = Self(rawValue: 8)
  public static let forward = Self(rawValue: 16)
}
public struct Modifiers: OptionSet, Sendable, Hashable {
  public let rawValue: UInt8
  public init(rawValue: UInt8) { self.rawValue = rawValue }
  public static let lctrl = Self(rawValue: 1)
  public static let lshift = Self(rawValue: 2)
  public static let lalt = Self(rawValue: 4)
  public static let lgui = Self(rawValue: 8)
  public static let rctrl = Self(rawValue: 16)
  public static let rshift = Self(rawValue: 32)
  public static let ralt = Self(rawValue: 64)
  public static let rgui = Self(rawValue: 128)
  public static let ctrl = lctrl
  public static let shift = lshift
  public static let alt = lalt
  public static let gui = lgui
}
public struct KeyboardLEDs: OptionSet, Sendable, Hashable {
  public let rawValue: UInt8
  public init(rawValue: UInt8) { self.rawValue = rawValue }
  public static let numLock = Self(rawValue: 1)
  public static let capsLock = Self(rawValue: 2)
  public static let scrollLock = Self(rawValue: 4)
  public static let compose = Self(rawValue: 8)
  public static let kana = Self(rawValue: 16)
}
public enum HIDKey: UInt8, CaseIterable, Sendable {
  case none = 0
  case a = 4
  case b = 5
  case c = 6
  case d = 7
  case e = 8
  case f = 9
  case g = 10
  case h = 11
  case i = 12
  case j = 13
  case k = 14
  case l = 15
  case m = 16
  case n = 17
  case o = 18
  case p = 19
  case q = 20
  case r = 21
  case s = 22
  case t = 23
  case u = 24
  case v = 25
  case w = 26
  case x = 27
  case y = 28
  case z = 29
  case digit0 = 39
  case digit1 = 30
  case digit2 = 31
  case digit3 = 32
  case digit4 = 33
  case digit5 = 34
  case digit6 = 35
  case digit7 = 36
  case digit8 = 37
  case digit9 = 38
  case enter = 40
  case esc = 41
  case backspace = 42
  case tab = 43
  case space = 44
  case minus = 45
  case equal = 46
  case lbracket = 47
  case rbracket = 48
  case backslash = 49
  case nonUSHash = 50
  case semicolon = 51
  case quote = 52
  case grave = 53
  case comma = 54
  case period = 55
  case slash = 56
  case capslock = 57
  case f1 = 58
  case f2 = 59
  case f3 = 60
  case f4 = 61
  case f5 = 62
  case f6 = 63
  case f7 = 64
  case f8 = 65
  case f9 = 66
  case f10 = 67
  case f11 = 68
  case f12 = 69
  case printscreen = 70
  case scrolllock = 71
  case pause = 72
  case insert = 73
  case home = 74
  case pageup = 75
  case delete = 76
  case end = 77
  case pagedown = 78
  case right = 79
  case left = 80
  case down = 81
  case up = 82
  case numlock = 83
  case menu = 101
  case f13 = 104
  case f14 = 105
  case f15 = 106
  case f16 = 107
  case f17 = 108
  case f18 = 109
  case f19 = 110
  case f20 = 111
  case f21 = 112
  case f22 = 113
  case f23 = 114
  case f24 = 115
  case lctrl = 224
  case lshift = 225
  case lalt = 226
  case lgui = 227
  case rctrl = 228
  case rshift = 229
  case ralt = 230
  case rgui = 231
  public var displayName: String {
    String(describing: self).replacingOccurrences(of: "digit", with: "").capitalized
  }
}
public enum ConsumerKey: UInt16, CaseIterable, Sendable {
  case playPause = 205
  case next = 181
  case prev = 182
  case stop = 183
  case mute = 226
  case volumeUp = 233
  case volumeDown = 234
  case brightnessUp = 111
  case brightnessDown = 112
}

extension HIDKey {
  public static let ctrl = lctrl
  public static let shift = lshift
  public static let alt = lalt
  public static let gui = lgui
  public static let cmd = lgui
  public static let win = lgui
  public static let meta = lgui
}
