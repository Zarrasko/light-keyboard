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

Dictionary cross-check (short words only): count_1w.txt is raw web text, not English-only — short
non-English fragments and abbreviations ("et", as in "et al") can carry inflated frequency purely
from web-scale volume, occasionally outranking a legitimate, much rarer-looking English word of the
same length ("wet") that a real swipe would target. A wrong short word is far more visible/damaging
than a wrong long one (fewer real candidates to begin with, so ties happen more), so words of
MIN_DICT_CHECK_LEN letters or fewer must additionally appear in a real dictionary, or in the small
manual ALLOWLIST below for common modern words an old formal dictionary wouldn't carry (texting/net
terms). Longer words are left to frequency alone, same as before — this is a targeted fix for a
demonstrated failure mode, not a wholesale dictionary-only word list.
"""
import struct
import re
import sys
import math
import os

WORD = re.compile(r"^[a-z]{2,15}$")
MAX_WORDS = 60_000
MIN_DICT_CHECK_LEN = 4

# Common modern short words a 1934-vintage formal dictionary (e.g. macOS/BSD's web2) wouldn't carry.
ALLOWLIST = {
    "ok", "okay", "hey", "yep", "nope", "lol", "omg", "app", "wifi", "info", "yeah",
}

# Candidate system dictionaries to cross-check short words against, tried in order — first one found
# wins. macOS/BSD ship web2 (Webster's Second International word list) at /usr/share/dict/words; many
# Linux distros ship a "words" package at the same path (content varies by distro but serves the same
# purpose). Passing a path explicitly (4th arg) always overrides this list.
DEFAULT_DICT_PATHS = ["/usr/share/dict/words", "/usr/dict/words"]

src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
out = sys.argv[2] if len(sys.argv) > 2 else "wordlist.bin"
dict_path = sys.argv[3] if len(sys.argv) > 3 else next(
    (p for p in DEFAULT_DICT_PATHS if os.path.exists(p)), None,
)

dictionary = None
if dict_path:
    with open(dict_path, encoding="utf-8", errors="ignore") as f:
        dictionary = {line.strip().lower() for line in f if line.strip().isalpha()}
    print(f"cross-checking words of {MIN_DICT_CHECK_LEN} letters or fewer against {dict_path} "
          f"({len(dictionary):,} entries)")
else:
    print("no system dictionary found — skipping the short-word cross-check "
          "(pass one explicitly as a 3rd argument, e.g. a words_alpha.txt)")

counts = []
dropped_contamination = 0
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if not WORD.match(w):
            continue
        if dictionary is not None and len(w) <= MIN_DICT_CHECK_LEN:
            if w not in dictionary and w not in ALLOWLIST:
                dropped_contamination += 1
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
if dictionary is not None:
    print(f"short non-dictionary words dropped as likely contamination: {dropped_contamination:,}")
print(f"output bytes: (approx) {sum(1 + len(w.encode()) + 4 for w, _ in counts) + 4:,}")
