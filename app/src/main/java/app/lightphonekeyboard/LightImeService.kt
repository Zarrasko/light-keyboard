package app.lightphonekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale

/**
 * The system keyboard. Once enabled + selected as default, it appears in every text field on the
 * phone. Keystrokes from [LightKeyboardView] are applied to the focused field via InputConnection.
 *
 * Optional word-level autocorrect (toggle in [SetupActivity]) runs on top: as you type a word we ask
 * the device's own spell checker ([SpellCheckerSession] → the phone's built-in dictionary) about it,
 * and when the word is finished (space / punctuation / enter) we swap in the suggested fix. Case is
 * preserved, and the first backspace after a correction reverts it.
 */
class LightImeService : InputMethodService(), LightKeyboardView.Listener, SpellCheckerSessionListener {

    private var keyboard: LightKeyboardView? = null

    private val dictation by lazy { VoiceDictation(this) }

    private var spell: SpellCheckerSession? = null
    private val corrections = HashMap<String, String?>()   // word -> fix (null = checked, no fix)
    private val pending = HashMap<Int, String>()           // request sequence -> word
    private var seq = 0

    // Words the user has explicitly kept despite the spell checker flagging them — autocorrect
    // leaves these alone from now on. Loaded once; persisted via Prefs as each rejection happens.
    private val rejectedWords: MutableSet<String> by lazy { HashSet(Prefs.rejectedCorrections(this)) }

    // Bounded history of fixes actually applied this session (original -> fix), oldest evicted first.
    // Lets a later, unmodified retype of [original] register as a rejection (see checkDelayedRejection),
    // covering the case where the user notices and fixes a correction well after the fact — not just
    // the single immediate backspace that [undoFrom]/[undoTo] already handle.
    private val recentCorrections = object : LinkedHashMap<String, String>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_RECENT_CORRECTIONS
    }

    // Revert-on-backspace: after a correction the text before the cursor ends with [undoFrom];
    // the next backspace restores [undoTo] instead of deleting a character.
    private var undoFrom: String? = null
    private var undoTo: String? = null

    // A word terminated before its spell-check came back. If the fix arrives while "word + terminator"
    // is still sitting at the cursor, we apply it retroactively — covers typing faster than the checker.
    private var lateWord: String? = null
    private var lateTerminator: String? = null

    // A swiped word commits without its trailing space (see onWord) — the space is deferred until we
    // see what actually follows, so punctuation hugs the word instead of "word ." A single backspace
    // right after a swipe deletes the whole word, not one character (see onBackspace).
    private var pendingSpaceAfterSwipe = false
    private var swipedWordPending: String? = null

    private var micActive = false

    override fun onCreate() {
        super.onCreate()
        initSpell()
        if (Prefs.voiceEnabled(this)) dictation.prepare()   // warm the model if voice is on (and downloaded)
    }

    override fun onCreateInputView(): View {
        val kb = LightKeyboardView(this)
        kb.listener = this
        keyboard = kb
        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Only re-initialise the keyboard surface for a genuinely new field. restarting == true is the
        // SAME field reconnecting — many apps call restartInput() after each committed character — so
        // resetting here would snap a user who switched to the numbers/symbols layer back to letters
        // mid-typing (the reported "type one number and it jumps back to ABC" bug).
        if (!restarting) {
            // Number / phone / date fields open on the numbers layer; text fields on letters.
            val cls = info?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
            val numeric = cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
            keyboard?.reset(numeric)
        }
        micActive = false
        dictation.destroy()
        corrections.clear()
        pending.clear()
        clearUndo()
        if (spell == null) initSpell()
        updateShift()
    }

    override fun onDestroy() {
        dictation.destroy()
        spell?.close()
        spell = null
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        updateShift() // after each keystroke/cursor move, recompute uppercase-vs-lowercase
    }

    /** Sentence-case: uppercase at a sentence start, lowercase after — from the field's caps mode.
     *  When Auto-Capitalize is off we report no caps, so there's no auto-uppercase but a manual SHIFT
     *  still one-shots (the next selection update drops it back to lowercase). */
    private fun updateShift() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        val caps = Prefs.autoCapitalize(this) && ic.getCursorCapsMode(type) != 0
        keyboard?.setShifted(caps)
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    // ------------------------------------------------------------------ key events

    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        consumePendingSwipeSpace(insertSpace = !(s.length == 1 && attachesWithoutSpace(s[0])))
        lateWord = null                    // any new input invalidates a pending late-correction
        lateTerminator = null
        if (s.length == 1 && isWordChar(s[0])) {
            clearUndo()
            ic.commitText(s, 1)
            requestCheck(trailingWord())   // keep the spell checker warm on the growing word
            return
        }
        // Only whitespace / sentence punctuation finish a word for autocorrect. Digits and other
        // symbols just commit, so alphanumeric tokens (ab2, mp3, covid19) are left alone.
        if (!(s.length == 1 && isCorrectTrigger(s[0]))) {
            clearUndo()
            ic.commitText(s, 1)
            return
        }
        // A word terminator: capitalizing "i" always wins (a case-only fix, so it must be checked
        // before the ignoreCase equality guard below, which would otherwise treat "I" == "i" as a
        // no-op fix); then try the spell-checker fix, then commit [s].
        val original = trailingWord()
        val iFix = if (Prefs.autoCapitalize(this)) capitalizeI(original) else null
        if (iFix != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(iFix, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            clearUndo()
            return
        }
        if (original.length >= 2 && checkDelayedRejection(original)) {
            clearUndo()
            ic.commitText(s, 1)
            return
        }
        val fix = if (autocorrectOn() && original.length >= 2 && !isRejected(original)) {
            corrections[original]
        } else null
        if (fix != null && !fix.equals(original, ignoreCase = true)) {
            val cased = applyCase(original, fix)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s     // arm revert: text now ends with the fix + terminator
            undoTo = original + s
            recentCorrections[original] = fix
        } else {
            clearUndo()
            ic.commitText(s, 1)
            // The check may not have returned yet; remember the word so a late result can still fix it.
            if (autocorrectOn() && original.length >= 2 && !corrections.containsKey(original)) {
                lateWord = original
                lateTerminator = s
            }
        }
    }

    /** A swipe-typing gesture resolved to a whole word. It's dictionary-valid by construction, so
     *  unlike a typed word it never goes through the spell-checker autocorrect path — only casing is
     *  applied, mirroring what typing it letter-by-letter under the current shift state would produce.
     *
     *  The trailing space is deferred rather than committed here (see [consumePendingSwipeSpace]), so
     *  punctuation typed right after hugs the word instead of leaving "word ." — and a backspace right
     *  after deletes the whole word instead of one character (see [onBackspace]). */
    override fun onWord(word: String) {
        val ic = currentInputConnection ?: return
        consumePendingSwipeSpace(insertSpace = true)   // separate from whatever (if anything) preceded
        clearUndo()
        val cased = when (keyboard?.currentCasing()) {
            LightKeyboardView.WordCasing.ALL_CAPS -> word.uppercase()
            LightKeyboardView.WordCasing.CAPITALIZE_FIRST -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
        ic.commitText(cased, 1)
        swipedWordPending = cased
        pendingSpaceAfterSwipe = true
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val pendingWord = swipedWordPending
        swipedWordPending = null
        pendingSpaceAfterSwipe = false
        if (pendingWord != null && ic.getTextBeforeCursor(pendingWord.length, 0)?.toString() == pendingWord) {
            ic.deleteSurroundingText(pendingWord.length, 0)
            return
        }
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null) {
            val before = ic.getTextBeforeCursor(from.length, 0)?.toString()
            clearUndo()
            if (before == from) {   // only revert if the corrected text is still sitting there
                ic.beginBatchEdit()
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1)
                ic.endBatchEdit()
                rejectWord(to.dropLast(1))   // undoing a fix means "never do that to this word again"
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        // Delete a whole grapheme cluster, not one UTF-16 unit — otherwise an emoji (surrogate pair /
        // variation selector) gets half-deleted and the leftover renders as a white box.
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)
        ic.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        clearUndo()
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        if (before.isEmpty()) { ic.deleteSurroundingText(1, 0); return }
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--    // trailing whitespace
        while (i > 0 && !before[i - 1].isWhitespace()) i--   // then the word
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    /** Length (in chars) of the last grapheme cluster of [before]; 1 if empty/unknown. */
    private fun lastGraphemeLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 1
        val s = before.toString()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous()
        return if (start == java.text.BreakIterator.DONE) s.length else (end - start)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        consumePendingSwipeSpace(insertSpace = false)   // no space wanted before a newline
        // Fix the last word before firing the action / newline.
        val original = trailingWord()
        val iFix = if (Prefs.autoCapitalize(this)) capitalizeI(original) else null
        if (iFix != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(iFix, 1)
            ic.endBatchEdit()
        } else if (autocorrectOn() && original.length >= 2 && !checkDelayedRejection(original)) {
            val fix = if (!isRejected(original)) corrections[original] else null
            if (fix != null && !fix.equals(original, ignoreCase = true)) {
                val cased = applyCase(original, fix)
                ic.beginBatchEdit()
                ic.deleteSurroundingText(original.length, 0)
                ic.commitText(cased, 1)
                ic.endBatchEdit()
                recentCorrections[original] = fix
            }
        }
        clearUndo()
        // Always insert a newline on the return key.
        ic.commitText("\n", 1)
    }

    /** Auto-Period: a quick second space turns the trailing " " into ". " — but only after a letter
     *  or digit, so a double space at line start or after punctuation just stays two spaces. The IME
     *  owns the text, so the rewrite happens here; the view only detects the double tap. */
    override fun onDoubleSpace() {
        val ic = currentInputConnection ?: return
        clearUndo()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)   // drop the lone trailing space…
            ic.commitText(". ", 1)           // …and replace it with period + space
            ic.endBatchEdit()
        } else {
            ic.commitText(" ", 1)            // not eligible — behave like a normal space
        }
    }

    override fun onDismiss() {
        requestHideSelf(0) // swipe-down closes the keyboard, the proper Android way
    }

    // Never take over the whole screen with the big white "extract" editor (it appears in landscape by
    // default). Our keyboard is built for the compact LightOS layout, so keep it docked at the bottom.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onMic() {
        if (!Prefs.voiceEnabled(this)) return   // mic key is hidden when voice is off, but guard anyway
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An IME can't pop the permission dialog itself; the shim activity does it.
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val kb = keyboard ?: return
        micActive = true
        kb.startListeningUi()
        startDictationWhenReady(kb, attempts = 0)
    }

    /** Wait (briefly) for the model to finish unpacking on first use, then start listening. */
    private fun startDictationWhenReady(kb: LightKeyboardView, attempts: Int) {
        if (!micActive) return
        if (dictation.ready) {
            dictation.listen(
                onPartial = { kb.setListeningStatus(it) },
                // Each finished segment commits to the field; dictation keeps going across pauses.
                onSegment = { text ->
                    clearUndo()
                    val ic = currentInputConnection
                    // Same signal updateShift() already uses for typed text's sentence-case auto-shift —
                    // reused here instead of tracking dictation's own notion of "start of sentence".
                    val sentenceStart = ic?.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
                    val cleaned = DictationCleanup.applyCasing(text, sentenceStart)
                    ic?.commitText(spacedDictation(cleaned), 1)
                },
                onError = { msg ->
                    micActive = false
                    kb.setListeningStatus(msg)
                    kb.postDelayed({ kb.stopListeningUi() }, 1200)
                },
            )
            return
        }
        dictation.prepare()
        if (attempts > 40) {   // ~12s; first-run model unpack should be done well before this
            micActive = false
            kb.setListeningStatus("Voice unavailable")
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        kb.setListeningStatus("Preparing voice…")
        kb.postDelayed({ startDictationWhenReady(kb, attempts + 1) }, 300)
    }

    /** Tap on the listening surface = "I'm done": flush the trailing words, then close it. */
    override fun onMicCancel() {
        if (!micActive) return
        micActive = false
        dictation.stop()
        keyboard?.stopListeningUi()
    }

    /** Insert a leading space if the cursor isn't already at a boundary, so dictated text doesn't fuse. */
    private fun spacedDictation(text: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
    }

    // Tell any of our own overlays (e.g. light-assistant's edge seam) to get out of the way while the
    // keyboard is on screen, so they don't sit over the top-left keys.
    override fun onWindowShown() { super.onWindowShown(); broadcastImeVisible(true) }
    override fun onWindowHidden() { super.onWindowHidden(); broadcastImeVisible(false) }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (micActive) { micActive = false; dictation.destroy(); keyboard?.stopListeningUi() }
        broadcastImeVisible(false)
    }

    private fun broadcastImeVisible(visible: Boolean) {
        runCatching { sendBroadcast(Intent(ACTION_IME_VISIBILITY).putExtra(EXTRA_VISIBLE, visible)) }
    }

    // ------------------------------------------------------------------ spell checking

    private fun initSpell() {
        val tsm = getSystemService(TextServicesManager::class.java) ?: return
        // referToSpellCheckerLanguageSettings = false: use the locale directly so we don't depend on
        // the global spell-check toggle being explicitly enabled.
        spell = tsm.newSpellCheckerSession(null, Locale.getDefault(), this, false)
    }

    private fun autocorrectOn(): Boolean = Prefs.autocorrect(this)

    /** Ask the device spell checker about [word] (once); the answer lands in [corrections]. */
    private fun requestCheck(word: String) {
        if (!autocorrectOn()) return
        val s = spell ?: return
        if (word.length < 2 || word.length > 32) return
        if (isRejected(word)) return   // the user has already told us to leave this word alone
        if (corrections.containsKey(word)) return
        if (word.any { it.isDigit() } || word.drop(1).any { it.isUpperCase() }) return // acronyms/odd
        val id = seq++
        pending[id] = word
        @Suppress("DEPRECATION")
        s.getSuggestions(arrayOf(TextInfo(word, 0, id)), 3, true)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        results ?: return
        for (si in results) {
            val word = pending.remove(si.sequence) ?: continue
            val fix = pickFix(si)
            corrections[word] = fix
            if (word == lateWord && fix != null && !fix.equals(word, ignoreCase = true)) {
                applyLateFix(word, fix)
            }
        }
    }

    /** A spell-check result came back after the word was already terminated. If "word + terminator" is
     *  still sitting right before the cursor (the user hasn't typed on), swap in the fix now. */
    private fun applyLateFix(original: String, fix: String) {
        if (isRejected(original) || checkDelayedRejection(original)) return
        val ic = currentInputConnection ?: return
        val term = lateTerminator ?: return
        lateWord = null
        lateTerminator = null
        val tail = original + term
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return
        val cased = applyCase(original, fix)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText(cased + term, 1)
        ic.endBatchEdit()
        undoFrom = cased + term
        undoTo = original + term
        recentCorrections[original] = fix
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        // Unused — we drive everything through the per-word getSuggestions path above.
    }

    /** The top suggestion whenever the checker flags the word as a typo (i.e. what gets the red
     *  underline) and offers one. We don't require the stricter "recommended" flag — if a word is
     *  flagged wrong we fix it, and an over-eager fix is a single backspace to undo. Real, in-dictionary
     *  words are always left alone. */
    private fun pickFix(si: SuggestionsInfo): String? {
        val attr = si.suggestionsAttributes
        if (attr and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0) return null
        val typo = attr and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
        if (!typo || si.suggestionsCount <= 0) return null
        return si.getSuggestionAt(0)
    }

    // ------------------------------------------------------------------ helpers

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

    /** Characters that "finish" a word and may trigger autocorrect — whitespace + sentence punctuation. */
    private fun isCorrectTrigger(c: Char): Boolean = c.isWhitespace() || c in ".,!?;:)"

    /** The run of word characters immediately before the cursor. */
    private fun trailingWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0) ?: return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i).toString()
    }

    /** Match the suggestion's case to what the user typed (ALL CAPS / Capitalized / lower). */
    private fun applyCase(original: String, fix: String): String = when {
        original.length > 1 && original.all { it.isUpperCase() } -> fix.uppercase()
        original.firstOrNull()?.isUpperCase() == true -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }

    private fun clearUndo() {
        undoFrom = null
        undoTo = null
        swipedWordPending = null
        pendingSpaceAfterSwipe = false
    }

    // ------------------------------------------------------------------ swipe follow-up

    /** "i" (and its apostrophe contractions — i'm, i've, i'll, i'd) is always capitalized regardless of
     *  sentence position, the one case-only fix every other keyboard applies that this one didn't. Null
     *  if [word] isn't one of those. A pure case change, so callers must apply it before any ignoreCase
     *  equality check against the original (which would otherwise treat "I" == "i" as nothing to do). */
    private fun capitalizeI(word: String): String? = when {
        word == "i" -> "I"
        word.length > 1 && word[0] == 'i' && word[1] == '\'' -> "I" + word.substring(1)
        else -> null
    }

    /** Punctuation that should hug the preceding word with no space before it, plus whitespace (which
     *  supplies its own separation either way). Anything else — letters, opening brackets, digits —
     *  needs [consumePendingSwipeSpace] to insert the deferred space first. */
    private fun attachesWithoutSpace(c: Char): Boolean = c.isWhitespace() || c in ".,!?;:)"

    /** Resolve the space deferred by a just-swiped word (see [onWord]): insert it now if whatever's
     *  happening next needs one before it, or drop it silently if not. Also ends whole-word-backspace
     *  eligibility for that word (see [onBackspace]) — both only apply to the very next action. */
    private fun consumePendingSwipeSpace(insertSpace: Boolean) {
        swipedWordPending = null
        if (!pendingSpaceAfterSwipe) return
        pendingSpaceAfterSwipe = false
        if (insertSpace) currentInputConnection?.commitText(" ", 1)
    }

    // ------------------------------------------------------------------ correction memory

    private fun isRejected(word: String): Boolean = word.lowercase() in rejectedWords

    /** Permanently stop autocorrecting [original]: the user has just told us, one way or another,
     *  that this spelling is intentional. */
    private fun rejectWord(original: String) {
        val key = original.lowercase()
        if (rejectedWords.add(key)) Prefs.addRejectedCorrection(this, key)
        corrections.remove(original)
        recentCorrections.remove(original)
    }

    /** True (and records the rejection) if [original] was corrected away earlier in this session and
     *  the user has now retyped that exact original spelling as a fresh, complete word — i.e. they
     *  noticed the fix later and put their own spelling back, rather than undoing it immediately with
     *  the single-backspace path in [onBackspace]. */
    private fun checkDelayedRejection(original: String): Boolean {
        if (!recentCorrections.containsKey(original)) return false
        rejectWord(original)
        return true
    }

    companion object {
        /** Broadcast so our overlays can dodge the keyboard. Implicit; caught by a runtime receiver. */
        const val ACTION_IME_VISIBILITY = "app.lightphonekeyboard.IME_VISIBILITY"
        const val EXTRA_VISIBLE = "visible"
        /** Window of text to inspect when deleting the last grapheme cluster (covers long emoji). */
        private const val GRAPHEME_LOOKBACK = 16
        /** Cap on [recentCorrections] — a rolling window is enough to catch a delayed retype without
         *  growing unbounded over a long typing session. */
        private const val MAX_RECENT_CORRECTIONS = 25
    }
}
