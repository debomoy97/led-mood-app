package com.debomoy97.ledmood

/**
 * Transition/animation modes for custom color-sequence patterns.
 * Ported 1:1 from user154lt/LEDDMX-00's CustomPatternMode.kt.
 *
 * AC represents "off" for custom pattern mode, not a real animation style.
 * Ordinal position matters: it's sent directly as the mode byte, so this
 * order must not be changed.
 */
enum class CustomPatternMode {
    GD, // Gradual
    FD, // Fade
    FW, // Flow / forward-wipe (exact meaning unconfirmed, kept as documented abbreviation)
    FS, // Flash/Strobe
    AC, // "Off" - not a real animation
    PU, // Pulse/Jump
    FL, // Flicker
    HO, // Hold/static
}