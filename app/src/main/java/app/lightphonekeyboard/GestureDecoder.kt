package app.lightphonekeyboard

import kotlin.math.hypot

/**
 * Decodes a swipe-typing gesture into the word it most likely spells, by comparing the user's touch
 * path against each dictionary candidate's "ideal path" — straight lines through the *current*
 * keyboard's actual letter key centers. A simplified version of the shape/location matching from
 * SHARK2 (Kristensson & Zhai, UIST 2004), the algorithm behind early glide-typing keyboards.
 *
 * Pure Kotlin, no Android dependency — directly unit-testable, matching the existing pattern for the
 * tap-accuracy logic in [LightKeyboardView] (per the JUnit comment in app/build.gradle).
 *
 * Two signals per candidate, combined with word frequency:
 *   - shape: scale + position normalised point-to-point distance — "does this gesture have the same
 *     silhouette as swiping this word?", independent of exactly where or how big the swipe was.
 *   - location: centered but NOT rescaled distance — "does this gesture actually sit over this
 *     word's keys?", catching same-shape-different-place mismatches (e.g. "in" vs "on").
 */
class GestureDecoder(private val wordList: WordList) {

    data class Pt(val x: Float, val y: Float)

    // Tunables. If a particular gesture reads wrong, nudge these rather than the algorithm shape.
    private val resamplePoints = 32        // points each path is resampled to before comparing
    private val shapeWeight = 1f
    private val locationWeight = 1f
    private val freqWeight = 0.15f         // how much word frequency can tip a close shape/location call
    private val startEndSlack = 1.3f       // key-units of tolerance for the first/last-letter prefilter
    private val maxCandidates = 400        // hard cap so one huge gesture can't blow up scoring cost

    /**
     * @param path the raw touch path for one continuous single-finger gesture (view pixel coords).
     * @param keyCenters current key center for every letter key, in the same pixel coordinate space.
     * @param keyWidth current letter key width (px) — normalises distances so scoring is the same
     *   regardless of keyboard height preset.
     * @return the best-scoring dictionary word, or null if nothing plausible was found.
     */
    fun decode(path: List<Pt>, keyCenters: Map<Char, Pt>, keyWidth: Float): String? {
        if (path.size < 2 || keyCenters.isEmpty() || keyWidth <= 0f) return null
        val first = path.first()
        val last = path.last()
        val resampledPath = resample(path, resamplePoints)
        val (pathCx, pathCy) = centroid(resampledPath)

        // Only words whose first letter's key is near the gesture's actual start point are worth
        // scoring at all — this is what makes bucketing by first letter in WordList pay off.
        val plausibleFirstLetters = keyCenters.filterValues { dist(first, it) <= startEndSlack * keyWidth }.keys
        if (plausibleFirstLetters.isEmpty()) return null

        var best: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var scored = 0

        outer@ for (letter in plausibleFirstLetters) {
            for (entry in wordList.startingWith(letter)) {
                if (scored >= maxCandidates) break@outer
                val word = entry.word
                val lastCenter = keyCenters[word.last()] ?: continue
                if (dist(last, lastCenter) > startEndSlack * keyWidth) continue
                val ideal = idealPath(word, keyCenters) ?: continue
                scored++

                val resampledIdeal = resample(ideal, resamplePoints)
                val (idealCx, idealCy) = centroid(resampledIdeal)
                val shapeDist = shapeDistance(
                    resampledPath, pathCx, pathCy, resampledIdeal, idealCx, idealCy, keyWidth,
                )
                val locationDist = locationDistance(resampledPath, resampledIdeal, keyWidth)
                val score = -shapeWeight * shapeDist - locationWeight * locationDist +
                    freqWeight * entry.logFreq
                if (score > bestScore) { bestScore = score; best = word }
            }
        }
        return best
    }

    /** Straight-line path through [word]'s letters' key centers. Consecutive duplicate letters
     *  collapse to one point — a repeated letter has no distinct shape signature of its own; the
     *  double letter is already implicit in which word matched. Null if any letter isn't a key
     *  (shouldn't happen for a-z words on the letters layer, but layouts could theoretically differ). */
    private fun idealPath(word: String, keyCenters: Map<Char, Pt>): List<Pt>? {
        val pts = ArrayList<Pt>(word.length)
        for (c in word) {
            val p = keyCenters[c] ?: return null
            if (pts.isEmpty() || pts.last() != p) pts.add(p)
        }
        return if (pts.size >= 2) pts else null
    }

    /** Resample a polyline to exactly [n] points, evenly spaced by arc length. */
    private fun resample(points: List<Pt>, n: Int): List<Pt> {
        if (points.size == 1) return List(n) { points[0] }
        val segLens = FloatArray(points.size - 1)
        var total = 0f
        for (i in segLens.indices) {
            segLens[i] = dist(points[i], points[i + 1])
            total += segLens[i]
        }
        if (total <= 0f) return List(n) { points[0] }
        val step = total / (n - 1)
        val out = ArrayList<Pt>(n)
        out.add(points.first())
        var segIdx = 0
        var segStart = 0f
        var target = step
        while (out.size < n - 1 && segIdx < segLens.size) {
            if (segStart + segLens[segIdx] < target) {
                segStart += segLens[segIdx]
                segIdx++
                continue
            }
            val t = ((target - segStart) / segLens[segIdx]).coerceIn(0f, 1f)
            val a = points[segIdx]
            val b = points[segIdx + 1]
            out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            target += step
        }
        while (out.size < n - 1) out.add(points.last())
        out.add(points.last())
        return out
    }

    private fun centroid(points: List<Pt>): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for (p in points) { sx += p.x; sy += p.y }
        return (sx / points.size) to (sy / points.size)
    }

    /** Scale + position normalised point-to-point distance ("shape" in SHARK2 terms). */
    private fun shapeDistance(
        a: List<Pt>, acx: Float, acy: Float,
        b: List<Pt>, bcx: Float, bcy: Float,
        keyWidth: Float,
    ): Float {
        val aScale = boundingRadius(a, acx, acy).coerceAtLeast(keyWidth * 0.5f)
        val bScale = boundingRadius(b, bcx, bcy).coerceAtLeast(keyWidth * 0.5f)
        var sum = 0f
        for (i in a.indices) {
            val ax = (a[i].x - acx) / aScale
            val ay = (a[i].y - acy) / aScale
            val bx = (b[i].x - bcx) / bScale
            val by = (b[i].y - bcy) / bScale
            sum += hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()
        }
        return sum / a.size
    }

    /** Centered but NOT rescaled distance ("location" in SHARK2 terms) — catches a gesture that has
     *  the right silhouette but sits over the wrong keys. */
    private fun locationDistance(a: List<Pt>, b: List<Pt>, keyWidth: Float): Float {
        var sum = 0f
        for (i in a.indices) sum += dist(a[i], b[i])
        return sum / a.size / keyWidth
    }

    private fun boundingRadius(points: List<Pt>, cx: Float, cy: Float): Float {
        var maxD = 0f
        for (p in points) {
            maxD = maxOf(maxD, hypot((p.x - cx).toDouble(), (p.y - cy).toDouble()).toFloat())
        }
        return maxD
    }

    private fun dist(a: Pt, b: Pt): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}
