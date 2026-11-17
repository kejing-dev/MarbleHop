"""Offline loudness evidence (B20 / ui_gates loudness): renders 30 s of a quick game from the app's own WAVs at the
default volume (大 = 100 %) and the app's relative gains (place 1.0, hop 0.8, pick 0.55, back 0.6, win 0.9), with its
timing (step 220 ms, hop 260 ms + 60 ms gaps, bot >= 400 ms) and the ±5 % rate variation, then prints the sounding-
segment RMS (20 ms windows above peak - 30 dB, as the gate measures) and the peak. Rendered, not recorded: the
emulator on this machine has no audio loopback.

  python tools/loudness.py OUT.wav
"""
import math, os, random, struct, sys, wave

SR = 44100
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1
        return [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]


def rate(x, r):
    n = int(len(x) / r)
    return [x[min(len(x) - 1, int(i * r))] for i in range(n)]


def main(out):
    rnd = random.Random(5)
    places = [load("place"), load("place_2"), load("place_3")]
    retract = [load("retract"), load("retract_2")]
    win = load("win")
    buf = [0.0] * (30 * SR)

    def put(x, t, gain):
        o = int(t * SR)
        for i, v in enumerate(x):
            if o + i < len(buf):
                buf[o + i] += v * gain

    t = 0.3
    turns = 0
    while t < 26.0:
        put(rate(rnd.choice(retract), 1.12), t, 0.55)                      # you pick a marble
        t += 0.8
        hops = rnd.choice([1, 2, 3])
        for k in range(hops):                                              # a hop chain: lighter hops, the last lands
            t += 0.26
            if k < hops - 1:
                put(rate(rnd.choice(places), 1.06 + rnd.random() * 0.06), t, 0.8); t += 0.06
            else:
                put(rate(rnd.choice(places), 0.95 + rnd.random() * 0.10), t, 1.0)
        t += 0.4 + 0.22                                                    # the bot thinks, then steps
        put(rate(rnd.choice(places), 0.95 + rnd.random() * 0.10), t, 1.0)
        t += 0.6
        turns += 1
    put(win, 28.6, 0.9)
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in buf))
    pk = max(abs(v) for v in buf)
    win_n = int(0.02 * SR)
    rms_w = [math.sqrt(sum(v * v for v in buf[i:i + win_n]) / win_n) for i in range(0, len(buf) - win_n, win_n)]
    thr = pk * 10 ** (-30 / 20)
    sounding = [r for r in rms_w if r > thr]
    rms = math.sqrt(sum(r * r for r in sounding) / len(sounding))
    print("sounding-segment RMS %.1f dBFS  peak %.1f dBFS  windows %d/%d  turns %d" % (20 * math.log10(rms), 20 * math.log10(pk), len(sounding), len(rms_w), turns))


main(sys.argv[1])
