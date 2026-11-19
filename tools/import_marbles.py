"""Glass-marble sounds (v0.1.1, replaces the stone-on-wood stand-in of v0.1.0, deviation DV-1).
Same cleaning chain as tools/import_stones.py (spectral subtraction -> 120 Hz-6 kHz -> onset cut -> -1 dBFS peak), on the
freesound CC0 recordings in ~/Downloads/cc0-sounds/freesound-marbles (KaleidacousticsAudio):

  ~/.factory-venv/bin/python tools/import_marbles.py --scan   # rank every hit
  ~/.factory-venv/bin/python tools/import_marbles.py          # write place / place_2 / place_3 / home / home_2
"""
import sys
import import_stones as st

st.SOURCES.clear()
st.SOURCES.update({
    "W1": ("freesound-marbles/marbles_628994.mp3", "https://freesound.org/people/KaleidacousticsAudio/sounds/628994/ (KaleidacousticsAudio, Marbles_Bouncing on wooden floor 1)"),
    "W2": ("freesound-marbles/marbles_628995.mp3", "https://freesound.org/people/KaleidacousticsAudio/sounds/628995/ (KaleidacousticsAudio, Marbles_Bouncing on wooden floor 2)"),
    "H": ("freesound-marbles/marbles_628997.mp3", "https://freesound.org/people/KaleidacousticsAudio/sounds/628997/ (KaleidacousticsAudio, Marbles_Hitting each other)"),
})

# chosen after --scan (key, onset seconds): single clean hits, lowest second-hit and quietest tail
CHOSEN = {"place": ("W1", 2.190), "place_2": ("W2", 7.024), "place_3": ("W1", 1.437),   # glass marble lands on wood
          "home": ("H", 1.447), "home_2": ("H", 3.332)}                                   # two glass marbles touch

if __name__ == "__main__":
    cs = list(st.candidates())
    if "--scan" in sys.argv:
        for c in sorted(cs, key=lambda c: (c["key"], c["second"], c["tail"])):
            print("%-3s %7.3fs  2nd-hit %.2f  tail %6.1f dB  centroid %5.0f Hz  rms %6.1f dBFS  dur %3.0f ms" % (
                c["key"], c["at"], c["second"], c["tail"], c["centroid"], c["rms"], 1000 * len(c["seg"]) / st.SR))
    else:
        for name, (key, t) in CHOSEN.items():
            c = min((c for c in cs if c["key"] == key), key=lambda c: abs(c["at"] - t))
            st.write(name, c["seg"])
            print("%s <- %s @ %.3f s  tail %.1f dB  centroid %.0f Hz  rms %.1f dBFS  %s" % (name, c["key"], c["at"], c["tail"], c["centroid"], c["rms"], c["url"]))
