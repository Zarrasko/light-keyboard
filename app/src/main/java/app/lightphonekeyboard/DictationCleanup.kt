package app.lightphonekeyboard

/**
 * Zero-cost cleanup applied to a finished voice-dictation segment before it's committed: capitalizes
 * sentence starts and standalone "I" (including contractions — "i'm", "i'll", "i've", "i'd"), mirroring
 * the sentence-case logic already applied to typed text ([LightImeService.updateShift]). No model, no
 * download. This is the baseline dictation punctuation/casing behavior — it ships regardless of
 * whether the heavier recasepunc integration pans out.
 */
object DictationCleanup {

    // \b is a transition between a word char and a non-word char (or a string edge); an apostrophe is
    // a non-word char, so this matches lone "i" AND the "i" in "i'm"/"i'll"/"i've"/"i'd", but not the
    // "i" inside "ibm" or "hi".
    private val STANDALONE_I = Regex("\\bi\\b")

    /**
     * @param text the raw segment from Vosk — lowercase, no punctuation.
     * @param sentenceStart whether this segment begins a new sentence (nothing dictated yet this field,
     *   or the cursor sits at a position the field itself reports as sentence-start — see the caller).
     */
    fun applyCasing(text: String, sentenceStart: Boolean): String {
        if (text.isEmpty()) return text
        val withI = STANDALONE_I.replace(text, "I")
        return if (sentenceStart) withI.replaceFirstChar { it.uppercaseChar() } else withI
    }
}
