# Keyboard tooling

## `gen_charmodel.py` — typing-accuracy language model

Generates `app/src/main/res/raw/charmodel.bin`, the character trigram model the keyboard uses for
per-tap accuracy (spatial × language key selection in `LightKeyboardView`).

It learns `P(next_letter | prev2, prev1)` over a 27-symbol alphabet (a-z + a word-boundary symbol),
frequency-weighted from a real word list, with trigram/bigram/unigram interpolation for smoothing.
Output is a little-endian float32 table, flattened `index = (c1*27 + c2)*27 + c3`, value `ln P`.

### Regenerate

```sh
curl -s -o /tmp/count_1w.txt http://norvig.com/ngrams/count_1w.txt   # ~4.7 MB, 333k words+counts
python3 gen_charmodel.py /tmp/count_1w.txt \
    ../app/src/main/res/raw/charmodel.bin
```

Source word list: Peter Norvig's `count_1w.txt` (Google Web Trillion Word Corpus unigrams).
Tunables for accuracy (Gaussian width, context weight `lambda`, touch offset `biasX/biasY`) live in
`LightKeyboardView.kt`, not here.

## `gen_wordlist.py` — swipe-typing word list

Generates `app/src/main/res/raw/wordlist.bin`, the word/frequency list `GestureDecoder` scores swipe
candidates against. Ships directly in the APK (a few hundred KB), unlike the voice model.

### Regenerate

```sh
curl -sL -o /tmp/count_1w.txt https://norvig.com/ngrams/count_1w.txt
python3 gen_wordlist.py /tmp/count_1w.txt ../app/src/main/res/raw/wordlist.bin
```

Same source corpus as `gen_charmodel.py`. Keeps the top 60k pure a-z words (2-15 letters) by
frequency; see the script's docstring for the exact binary format.

Words of 4 letters or fewer are additionally cross-checked against a system dictionary (auto-detected
at `/usr/share/dict/words` on macOS/most Linux, or pass one explicitly as a 3rd argument) — raw web
text isn't English-only, and a short non-English fragment ("et") can outrank a legitimate rarer-looking
English word ("wet") on frequency alone. No dictionary found just skips the check (prints a warning)
rather than failing the build.
