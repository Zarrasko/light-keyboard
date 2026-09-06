package app.lightphonekeyboard

import android.content.res.Resources
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundled word/frequency list swipe typing scores gesture candidates against (see
 * [GestureDecoder]). Built by `tools/gen_wordlist.py` from the same corpus as the typing-accuracy
 * char trigram model ([LightKeyboardView]'s `charmodel.bin`). Bucketed by first letter, since every
 * candidate lookup starts from where the gesture's path begins.
 */
class WordList(entries: List<Entry>) {

    class Entry(val word: String, val logFreq: Float)

    private val byFirstLetter: Array<MutableList<Entry>> = Array(26) { mutableListOf() }

    init {
        for (e in entries) {
            val c = e.word.firstOrNull() ?: continue
            val i = c - 'a'
            if (i in 0..25) byFirstLetter[i].add(e)
        }
    }

    /** Candidate words starting with [first] (lowercase a-z), or empty if out of range. */
    fun startingWith(first: Char): List<Entry> {
        val i = first - 'a'
        return if (i in 0..25) byFirstLetter[i] else emptyList()
    }

    companion object {
        /** Parse the bundled res/raw/wordlist.bin asset (little-endian: int32 count, then per word a
         *  length byte + ASCII bytes + float32 log-frequency — see gen_wordlist.py). Null if the
         *  resource is missing/corrupt; callers treat that as "swipe typing unavailable" and fall
         *  back to plain taps. */
        fun load(resources: Resources): WordList? = try {
            val bytes = resources.openRawResource(R.raw.wordlist).use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buf.int
            val entries = ArrayList<Entry>(count)
            repeat(count) {
                val len = buf.get().toInt() and 0xFF
                val wordBytes = ByteArray(len)
                buf.get(wordBytes)
                val word = String(wordBytes, Charsets.US_ASCII)
                entries.add(Entry(word, buf.float))
            }
            WordList(entries)
        } catch (e: Exception) {
            null
        }
    }
}
