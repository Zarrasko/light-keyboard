#!/usr/bin/env python3
"""
Build the bundled word/frequency list the swipe-typing gesture decoder scores candidates against
(GestureDecoder.kt / WordList.kt).

Unlike the voice-dictation model, this list ships directly in the APK — it's tiny (a few hundred KB
for tens of thousands of words) compared to anything worth downloading on demand.

Source word list: the same corpus already used for the typing-accuracy char trigram model (see
gen_charmodel.py) — Peter Norvig's count_1w.txt (Google Web Trillion Word Corpus unigrams). Reusing
it means no new licensing/provenance question for this project.

Output format (little-endian, read by WordList.kt):
    int32   count
    repeated `count` times:
        uint8   word length (2..15)
        bytes   word, ASCII lowercase a-z, that many bytes
        float32 ln(frequency / total) — natural log word-frequency, for scoring against
                GestureDecoder's shape/location distance terms

Words are kept only if they're pure a-z (matches the keyboard's letter layout; no digits, hyphens,
or apostrophes — a glide gesture can't hit punctuation) and 2-15 letters long (below 2, "a"/"I" are
handled as taps trivially; above 15 a glide's shape signal is too noisy to be worth the candidate).
Kept words are ranked by frequency and capped at MAX_WORDS so the prefilter step in GestureDecoder
(bucket by first letter, then scan) stays fast without needing a separate index structure.
"""
import struct
import re
import sys
import math

WORD = re.compile(r"^[a-z]{2,15}$")
MAX_WORDS = 60_000

src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
out = sys.argv[2] if len(sys.argv) > 2 else "wordlist.bin"

counts = []
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if not WORD.match(w):
            continue
        try:
            weight = float(c)
        except ValueError:
            continue
        counts.append((w, weight))

# Highest frequency first, then cap — the tail of a Zipfian word list contributes almost nothing to
# real typing but costs prefilter time, so it's not worth keeping.
counts.sort(key=lambda wc: -wc[1])
counts = counts[:MAX_WORDS]
total = sum(c for _, c in counts)

with open(out, "wb") as fo:
    fo.write(struct.pack("<i", len(counts)))
    for w, c in counts:
        wb = w.encode("ascii")
        logp = math.log(c / total)
        fo.write(struct.pack("<B", len(wb)))
        fo.write(wb)
        fo.write(struct.pack("<f", logp))

print(f"words kept: {len(counts):,} of a possible larger corpus")
print(f"output bytes: (approx) {sum(1 + len(w.encode()) + 4 for w, _ in counts) + 4:,}")
