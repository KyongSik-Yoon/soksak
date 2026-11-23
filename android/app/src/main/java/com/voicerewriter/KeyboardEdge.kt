package com.voicerewriter

/**
 * Spots the moment the keyboard comes up, from the accessibility service's repeated reports of
 * whether an input-method window is open. The bubble's tucked tab comes out on that moment only:
 * a keyboard that stays up does not keep bringing it back after it tucks away again.
 */
class KeyboardEdge {

    private var open = false

    /**
     * True when this report is the keyboard opening. A null report (the service could not see
     * the windows) keeps the last known state, so the next real report of a keyboard that was
     * up all along is not taken for it opening.
     */
    fun opened(imeOpen: Boolean?): Boolean {
        if (imeOpen == null) return false
        val rose = imeOpen && !open
        open = imeOpen
        return rose
    }
}
