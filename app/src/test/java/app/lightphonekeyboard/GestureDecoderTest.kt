package app.lightphonekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.ln

/**
 * Tests for [GestureDecoder] against a synthetic QWERTY grid — no Android dependency needed, since
 * both [GestureDecoder] and [WordList] are pure Kotlin.
 */
class GestureDecoderTest {

    // ---- synthetic keyboard, same shape as TouchModelStressTest's, just keyed by letter ----
    private val rowsLetters = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val padSide = 18f
    private val padTop = 24f
    private val rowPitch = 150f
    private val keyWidth = 96f   // (1080 - 2*18) / 10, i.e. the top-row column width

    private fun buildKeyCenters(): Map<Char, GestureDecoder.Pt> {
        val centers = HashMap<Char, GestureDecoder.Pt>()
        for ((r, row) in rowsLetters.withIndex()) {
            val colW = (1080f - 2 * padSide) / row.length
            val cy = padTop + 75f + r * rowPitch
            for ((i, ch) in row.withIndex()) {
                centers[ch] = GestureDecoder.Pt(padSide + (i + 0.5f) * colW, cy)
            }
        }
        return centers
    }

    /** Straight-line path through a word's own key centers — this IS the decoder's own "ideal path"
     *  for that word, so a gesture built this way should always decode back to it when it's the only
     *  reasonable candidate, or the highest-frequency one among near-identical shapes. */
    private fun idealPathFor(word: String, centers: Map<Char, GestureDecoder.Pt>): List<GestureDecoder.Pt> =
        word.map { centers.getValue(it) }

    private fun wordList(vararg wordsAndCounts: Pair<String, Double>): WordList {
        val total = wordsAndCounts.sumOf { it.second }
        return WordList(wordsAndCounts.map { (w, c) -> WordList.Entry(w, ln(c / total).toFloat()) })
    }

    @Test fun straightGlide_decodesToTheSwipedWord() {
        val centers = buildKeyCenters()
        val words = wordList("the" to 100.0, "ten" to 40.0, "her" to 30.0, "then" to 20.0, "he" to 90.0)
        val decoder = GestureDecoder(words)
        val path = idealPathFor("the", centers)
        assertEquals("the", decoder.decode(path, centers, keyWidth))
    }

    @Test fun distinctShapes_pickTheMatchingWordEvenWhenLessFrequent() {
        val centers = buildKeyCenters()
        // "then" is far more common than "hen" in real English, but a gesture shaped exactly like
        // "hen" (a short 3-letter glide) shouldn't be swallowed by "then"'s extra leading stroke.
        val words = wordList("hen" to 1.0, "then" to 500.0)
        val decoder = GestureDecoder(words)
        val path = idealPathFor("hen", centers)
        assertEquals("hen", decoder.decode(path, centers, keyWidth))
    }

    @Test fun ambiguousShape_frequencyBreaksTheTie() {
        val centers = buildKeyCenters()
        // "cat" and "car" differ only in their last letter; 't' and 'r' sit close together on the top
        // row, so a slightly-imprecise glide ending between them is genuinely ambiguous on shape alone.
        // The much more frequent word should win.
        val words = wordList("cat" to 5.0, "car" to 5000.0)
        val decoder = GestureDecoder(words)
        val path = idealPathFor("cat", centers).toMutableList()
        val tPt = centers.getValue('t')
        val rPt = centers.getValue('r')
        path[path.lastIndex] = GestureDecoder.Pt((tPt.x + rPt.x) / 2f, (tPt.y + rPt.y) / 2f)
        assertEquals("car", decoder.decode(path, centers, keyWidth))
    }

    @Test fun noisyRealisticPath_stillDecodesCorrectly() {
        // Resample a word's ideal path more densely (as a real touch stream would produce many more
        // samples than letters) and add small jitter, rather than feeding the decoder the noiseless
        // per-letter points directly.
        val centers = buildKeyCenters()
        val words = wordList("keyboard" to 50.0, "leopard" to 10.0, "keyword" to 30.0)
        val decoder = GestureDecoder(words)
        val ideal = idealPathFor("keyboard", centers)
        val rng = Random(42)
        val noisy = ArrayList<GestureDecoder.Pt>()
        for (i in 0 until ideal.size - 1) {
            val a = ideal[i]; val b = ideal[i + 1]
            for (s in 0 until 6) {
                val t = s / 6f
                val jx = (rng.nextGaussian() * 4.0).toFloat()
                val jy = (rng.nextGaussian() * 4.0).toFloat()
                noisy.add(GestureDecoder.Pt(a.x + (b.x - a.x) * t + jx, a.y + (b.y - a.y) * t + jy))
            }
        }
        noisy.add(ideal.last())
        assertEquals("keyboard", decoder.decode(noisy, centers, keyWidth))
    }

    @Test fun emptyOrDegeneratePath_returnsNullWithoutCrashing() {
        val centers = buildKeyCenters()
        val decoder = GestureDecoder(wordList("the" to 1.0))
        assertNull(decoder.decode(emptyList(), centers, keyWidth))
        assertNull(decoder.decode(listOf(GestureDecoder.Pt(0f, 0f)), centers, keyWidth))
        assertNull(decoder.decode(idealPathFor("the", centers), emptyMap(), keyWidth))
        assertNull(decoder.decode(idealPathFor("the", centers), centers, 0f))
    }

    @Test fun pathFarFromAnyKey_returnsNull() {
        val centers = buildKeyCenters()
        val decoder = GestureDecoder(wordList("the" to 1.0))
        val farAway = listOf(GestureDecoder.Pt(-9000f, -9000f), GestureDecoder.Pt(-8000f, -9000f))
        assertNull(decoder.decode(farAway, centers, keyWidth))
    }

    @Test fun largeBucketForOnePlausibleLetter_doesNotStarveAnother() {
        // Regression test: the candidate cap used to be a single global budget shared across every
        // plausible starting letter. A gesture whose start point sits near two candidate letters, one
        // of which has a large word bucket, could exhaust that whole budget scanning the big bucket
        // before the OTHER plausible letter's bucket — containing the actually-correct word — was ever
        // reached at all. (This is exactly how a real "wet" swipe decoded to "er" instead: the down
        // touch was plausibly close to both 'w' and 'e', 'e' has a huge bucket, and "wet" — bucketed
        // under 'w' — was never scored.) The fix scores each plausible letter's bucket independently,
        // so a big bucket for one letter can never crowd out another letter's bucket.
        val centers = buildKeyCenters()
        val entries = ArrayList<WordList.Entry>()
        // Flood the 'r' bucket with far more than the per-letter cap, all real enough to pass the
        // start/end-letter prefilter (start 'r', end 'n', like the target) so every one of them
        // actually gets scored, not skipped for free.
        repeat(1000) { i -> entries.add(WordList.Entry("r" + "a".repeat(i % 8 + 1) + "n", -5f)) }
        // The true target: "then" (t-h-e-n), bucketed under 't' — a different plausible starting
        // letter — with a much better (less negative) shape/location match than any filler above.
        entries.add(WordList.Entry("then", -9f))
        val words = WordList(entries)
        val decoder = GestureDecoder(words)

        // Start the gesture between 'r' and 't' (adjacent keys) so both are plausible first letters,
        // then trace the rest of "then"'s own shape.
        val rt = centers.getValue('r')
        val tt = centers.getValue('t')
        val path = listOf(GestureDecoder.Pt((rt.x + tt.x) / 2f, rt.y)) +
            idealPathFor("then", centers).drop(0)
        assertEquals("then", decoder.decode(path, centers, keyWidth))
    }

    @Test fun wordList_bucketsByFirstLetter() {
        val words = wordList("cat" to 1.0, "car" to 1.0, "dog" to 1.0)
        assertEquals(setOf("cat", "car"), words.startingWith('c').map { it.word }.toSet())
        assertEquals(setOf("dog"), words.startingWith('d').map { it.word }.toSet())
        assertTrue(words.startingWith('z').isEmpty())
    }
}
