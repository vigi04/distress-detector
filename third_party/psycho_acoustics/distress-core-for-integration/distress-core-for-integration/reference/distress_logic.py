"""
Distress-call detection logic — reference implementation in Python.

This mirrors, step for step, the logic running in distress_monitor.html
(the phone prototype). It is deliberately plain NumPy, no audio libraries,
so that it can be read, checked and ported to C without surprises.

Pipeline
  audio (mono, any rate) -> frames (NFFT window, HOP step)
    -> per-frame features (level, f0, alpha ratio, Hammarberg, flatness, roughness proxy)
    -> voiced-event segmentation
    -> per-event tone features + "help-shaped" and "bachao-shaped" tests
    -> distress score (z-score fusion against a calm baseline)
    -> decision: word path (3 word-shaped events in 6 s, mean distress >= 0.5)
                 or scream path (1 event >= 0.3 s, distress >= 0.85, roughness >= +2 sigma)

Word shapes
  help   = [flat unvoiced burst /h/ in the pre-context] -> voiced core 0.1-3 s -> [abrupt stop /p/]
  bachao = short voiced onset "ba-" (0.04-0.4 s) -> flat unvoiced burst "-ch-" (0.03-0.2 s,
           with high-frequency energy) -> voiced core "-ao" (0.12-3 s). The "-ch-" gap is usually
           shorter than INNER_GAP and is absorbed into one event; it can also split the word into
           two events, in which case the second absorbs the first.
  Both shapes count on the word path; mixed repetitions ("help! bachao! bachao!") are fine.
  The Android app (DistressDetector, Kotlin) mirrors this file line for line.

Usage
  python distress_logic.py recording.wav            # prints events and alerts
  python distress_logic.py recording.wav --csv out.csv

Only dependency: numpy. WAV reading uses the standard library.
"""

from __future__ import annotations
import sys, wave, struct, csv, math
from dataclasses import dataclass, field
import numpy as np

# ----------------------------------------------------------------------------
# Parameters (all hand-set; these are what the recordings will later tune)
# ----------------------------------------------------------------------------
HOP_48K = 1024      # samples per step at 48 kHz (21.3 ms); scaled to other rates so the hop stays 21.3 ms
NFFT_48K = 2048     # analysis window at 48 kHz (42.7 ms); scaled and rounded up to a power of two
ENV_SUB_48K = 128   # envelope decimation at 48 kHz (2.7 ms per point); scaled likewise

def framing_for(fs: int) -> tuple[int, int, int, int]:
    """(HOP, NFFT, pitch decimation, ENV_SUB) for a sample rate — identical to Config.kt in the app."""
    hop = int(round(HOP_48K * fs / 48000))
    nfft = 1
    while nfft < NFFT_48K * fs / 48000:
        nfft <<= 1
    dec = max(1, int(round(fs / 16000)))
    env_sub = max(8, int(round(ENV_SUB_48K * fs / 48000)))
    return hop, nfft, dec, env_sub
PRE_FRAMES = 8      # frames of context kept before a voiced event (for the /h/ burst)
END_GAP = 6         # unvoiced frames that end an event
INNER_GAP = 5       # unvoiced frames that are still "inside" an event (short dips)
WINDOW_S = 6.0      # repetition window in seconds
N_REPEAT = 3        # help-shaped events needed on the word path
WORD_DISTRESS = 0.50
SCREAM_DISTRESS = 0.85
SCREAM_MIN_DUR = 0.30
SCREAM_ROUGH_Z = 2.0

BACHAO_ONSET = (0.04, 0.40)   # s, voiced "ba-"
BACHAO_GAP = (0.03, 0.20)     # s, unvoiced "-ch-"
BACHAO_CORE = (0.12, 3.0)     # s, voiced "-ao"
BACHAO_GAP_FLAT = 0.25        # mean spectral flatness of the audible gap frames
BACHAO_GAP_ALPHA = -5.0       # dB, max alpha ratio of the gap: fricative energy sits above 1 kHz

ENV_N = 128         # envelope points in the modulation analysis

VOICING_CORR = 0.55 # autocorrelation peak needed to call a frame voiced
VOICING_SNR = 10.0  # dB above the tracked noise floor needed to call a frame voiced
F0_MIN, F0_MAX = 60.0, 1000.0

# Distress-score weights (sign: + means "more of this = more distress")
WEIGHTS = {"rough": 1.0, "f0st": 0.8, "f0range": 0.6, "alpha": 0.6,
           "ham": -0.5, "jitter": 0.5, "level": 0.4, "dur": 0.3}

def default_baseline() -> dict:
    """Mean/sd of event features for a generic calm adult voice."""
    return {"f0st": (33, 4), "f0range": (4, 2.5), "jitter": (0.35, 0.25),
            "level": (-30, 6), "alpha": (-14, 5), "ham": (26, 6),
            "rough": (0.18, 0.10), "dur": (0.35, 0.25)}

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
def semitone(f_hz: float) -> float:
    """Pitch in semitones above 27.5 Hz (the eGeMAPS convention)."""
    return 12.0 * math.log2(f_hz / 27.5)

def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))

def read_wav(path: str) -> tuple[np.ndarray, int]:
    """Read a WAV as mono float32 in [-1, 1]. Standard library only."""
    with wave.open(path, "rb") as w:
        n_ch, sampwidth, fs, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if sampwidth == 2:
        x = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif sampwidth == 4:
        x = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    elif sampwidth == 1:
        x = (np.frombuffer(raw, dtype="u1").astype(np.float32) - 128.0) / 128.0
    else:
        raise ValueError(f"unsupported sample width {sampwidth}")
    if n_ch > 1:
        x = x.reshape(-1, n_ch).mean(axis=1)
    return x, fs

# ----------------------------------------------------------------------------
# Per-frame analysis
# ----------------------------------------------------------------------------
@dataclass
class Frame:
    idx: int
    t: float
    level: float      # dBFS, RMS of the window
    f0: float         # Hz, 0 if unvoiced
    corr: float       # autocorrelation peak (0..1)
    voiced: bool
    alpha: float      # dB, energy 1-5 kHz over 50-1000 Hz
    ham: float        # dB, Hammarberg index: peak 0-2 kHz minus peak 2-5 kHz
    flat: float       # spectral flatness 0.5-8 kHz (0..1)
    rough: float = 0  # roughness proxy (0..1), filled in by the stream

class FrameAnalyser:
    def __init__(self, fs: int):
        self.fs = fs
        _, NFFT, dec, _ = framing_for(fs)
        self.nfft = NFFT
        self.hann = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(NFFT) / (NFFT - 1))
        self.bin_hz = fs / NFFT
        f = np.arange(NFFT // 2) * self.bin_hz
        self.m_lo = (f >= 50) & (f < 1000)
        self.m_hi = (f >= 1000) & (f < 5000)
        self.m_p1 = f < 2000
        self.m_p2 = (f >= 2000) & (f < 5000)
        self.m_fl = (f >= 500) & (f < 8000)
        # pitch on a copy decimated to ~16 kHz (3:1 at 48 kHz, 1:1 at 16 kHz)
        self.dec = dec
        self.fsd = fs / self.dec
        nd = NFFT // self.dec
        self.lag_min = int(self.fsd / F0_MAX)
        self.lag_max = min(int(self.fsd / F0_MIN), nd // 2)

    def analyse(self, x: np.ndarray, floor_db: float) -> tuple:
        """x: NFFT samples. Returns a tuple of frame features (without idx/t/rough)."""
        level = 20 * math.log10(math.sqrt(float(np.mean(x * x))) + 1e-9)

        spec = np.abs(np.fft.rfft(x * self.hann))[: self.nfft // 2] ** 2
        spec[0] = 0.0
        e_lo, e_hi = spec[self.m_lo].sum(), spec[self.m_hi].sum()
        alpha = 10 * math.log10((e_hi + 1e-20) / (e_lo + 1e-20))
        pk1, pk2 = spec[self.m_p1].max(), spec[self.m_p2].max()
        ham = 10 * math.log10((pk1 + 1e-20) / (pk2 + 1e-20))
        s = spec[self.m_fl]
        flat = float(np.exp(np.mean(np.log(s + 1e-20))) / (np.mean(s) + 1e-20))

        # normalised autocorrelation pitch tracker with an octave guard
        nd = len(x) // self.dec
        y = x[: nd * self.dec].reshape(nd, self.dec).mean(axis=1)
        m = nd - self.lag_max
        e0 = float(np.dot(y[:m], y[:m]))
        best, best_lag = 0.0, 0
        rr = np.zeros(self.lag_max + 1)
        for lag in range(self.lag_min, self.lag_max + 1):
            seg = y[lag: lag + m]
            r = float(np.dot(y[:m], seg)) / (math.sqrt(e0 * float(np.dot(seg, seg))) + 1e-12)
            rr[lag] = r
            if r > best:
                best, best_lag = r, lag
        for lag in range(self.lag_min + 1, self.lag_max):
            if rr[lag] >= 0.92 * best and rr[lag] >= rr[lag - 1] and rr[lag] >= rr[lag + 1]:
                best_lag, best = lag, rr[lag]
                break
        f0 = self.fsd / best_lag if best_lag else 0.0
        voiced = best > VOICING_CORR and level > floor_db + VOICING_SNR and F0_MIN <= f0 <= F0_MAX
        return level, (f0 if voiced else 0.0), best, voiced, alpha, ham, flat

def roughness_proxy(env: np.ndarray, fs: int, f0: float = 0.0, env_sub: int = ENV_SUB_48K) -> float:
    """Share of amplitude-modulation energy in 30-150 Hz (the scream band),
    excluding the modulation the pitch itself produces (f0 and 2*f0), so that a
    steady low-pitched voice does not read as rough. Sub-harmonic modulation
    (f0/2, f0/3), the signature of a rough or screamed voice, is kept."""
    mean = env.mean()
    if mean < 1e-5:
        return 0.0
    depth = float(env.std() / mean)          # modulation depth; a steady vowel has almost none
    if depth < 0.05:
        return 0.0
    hann = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(ENV_N) / (ENV_N - 1))
    spec = np.abs(np.fft.rfft((env - mean) / mean * hann)) ** 2
    efs = fs / env_sub
    f = np.arange(len(spec)) * efs / ENV_N
    keep = np.ones_like(f, dtype=bool)
    if f0 > 0:
        bw = 2.5 * efs / ENV_N                # about two bins either side
        for h in (f0, 2 * f0):
            keep &= np.abs(f - h) > bw
    tot = spec[2:].sum()
    band = spec[(f >= 30) & (f <= 150) & keep].sum()
    return float(band / tot) if tot > 0 else 0.0

# ----------------------------------------------------------------------------
# Event segmentation, tone features, distress score, decision
# ----------------------------------------------------------------------------
@dataclass
class Event:
    id: int
    t_start: float
    t_end: float
    feat: dict
    helplike: bool
    distress: float
    z: dict
    shape: str = "voiced"          # "help", "bachao" or "voiced"
    absorbs: int | None = None     # id of the "ba-" event a split bachao absorbs
    trigger: str | None = None
    audio: np.ndarray | None = None

    @property
    def wordlike(self) -> bool:
        return self.shape in ("help", "bachao")


def _runs(frames: list[Frame]) -> list[list[Frame]]:
    """Split a frame list into maximal runs of equal voicing."""
    runs: list[list[Frame]] = []
    for f in frames:
        if not runs or runs[-1][0].voiced != f.voiced:
            runs.append([])
        runs[-1].append(f)
    return runs


def bachao_fits(onset: list[Frame], gap: list[Frame], core: list[Frame], floor_db: float, hop_s: float) -> bool:
    """ba- (voiced, short) -> -ch- (unvoiced, flat, audible, high-frequency) -> -ao (voiced, 0.12-3 s)."""
    if not (BACHAO_ONSET[0] <= len(onset) * hop_s <= BACHAO_ONSET[1]):
        return False
    if not (BACHAO_GAP[0] <= len(gap) * hop_s <= BACHAO_GAP[1]):
        return False
    if not (BACHAO_CORE[0] <= len(core) * hop_s <= BACHAO_CORE[1]):
        return False
    if any(f.voiced for f in gap):
        return False
    audible = [f for f in gap if f.level > floor_db + 6]
    if not audible:
        return False
    if np.mean([f.flat for f in audible]) < BACHAO_GAP_FLAT:
        return False
    if max(f.alpha for f in audible) < BACHAO_GAP_ALPHA:
        return False
    return True

def event_features(frames: list[Frame], pre: list[Frame], post: list[Frame],
                   floor_db: float, hop_s: float) -> tuple[dict, bool]:
    vf = [f for f in frames if f.voiced]
    dur = len(frames) * hop_s
    last_v = vf[-1]
    # /h/ burst: an unvoiced, spectrally flat frame above the floor just before the voice
    burst = any((not f.voiced) and f.flat > 0.25 and f.level > floor_db + 6 for f in pre)
    # stop: abrupt level drop right after the voiced core
    after = post[:3]
    drop = last_v.level - min(f.level for f in after) if after else 0.0
    stop = drop >= 12
    dur_ok = 0.1 <= dur <= 3.0
    help_score = 0.45 * burst + 0.30 * dur_ok + 0.25 * stop
    helplike = help_score >= 0.55

    f0s = np.array([semitone(f.f0) for f in vf])
    ts = np.array([f.t for f in vf])
    jitter = float(np.mean(np.abs(np.diff(f0s)))) if len(f0s) > 1 else 0.0
    slope = float(np.polyfit(ts, f0s, 1)[0]) if len(f0s) > 1 and np.ptp(ts) > 0 else 0.0
    feat = {
        "dur": dur,
        "f0hz": 27.5 * 2 ** (f0s.mean() / 12),
        "f0st": float(f0s.mean()),
        "f0range": float(f0s.max() - f0s.min()),
        "f0slope": slope,
        "jitter": jitter,
        "level": float(np.mean([f.level for f in vf])),
        "levelMax": float(max(f.level for f in vf)),
        "alpha": float(np.mean([f.alpha for f in vf])),
        "ham": float(np.mean([f.ham for f in vf])),
        "rough": float(np.mean([f.rough for f in vf])),
        "burst": int(burst), "stop": int(stop),
        "helpScore": help_score, "helpLike": int(helplike),
    }
    return feat, helplike

def distress_score(feat: dict, baseline: dict) -> tuple[float, dict]:
    """z-score each feature against the calm baseline, weight, average, squash."""
    z, acc, wsum = {}, 0.0, 0.0
    for k, w in WEIGHTS.items():
        if k not in baseline:
            continue
        m, s = baseline[k]
        zz = max(-3.0, min(3.0, (feat[k] - m) / max(s, 1e-3)))
        z[k] = zz
        acc += w * zz
        wsum += abs(w)
    mz = acc / wsum
    return sigmoid(1.6 * (mz - 0.9)), z

class Detector:
    """Streams frames, segments events, scores them and applies the two-path rule."""

    def __init__(self, fs: int, baseline: dict | None = None, keep_audio: bool = True):
        self.fs = fs
        self.hop, self.nfft, _, self.env_sub = framing_for(fs)
        self.hop_s = self.hop / fs
        self.fa = FrameAnalyser(fs)
        self.baseline = baseline or default_baseline()
        self.keep_audio = keep_audio
        self.floor = -60.0
        self.frames: list[Frame] = []
        self.events: list[Event] = []
        self.alerts: list[tuple[float, str, str]] = []
        self._ev = None
        self._prev: Event | None = None
        self._prev_last_voiced_t = 0.0
        self._win = np.zeros(self.nfft, dtype=np.float32)
        self._env = np.zeros(ENV_N)
        self._env_rem = np.zeros(0, dtype=np.float32)   # samples left over from the previous hop
        self._audio = None

    # -- streaming interface --------------------------------------------------
    def process(self, x: np.ndarray):
        self._audio = x if self.keep_audio else None
        HOP, NFFT, ENV_SUB = self.hop, self.nfft, self.env_sub
        n_hops = (len(x) - NFFT) // HOP + 1
        for i in range(n_hops):
            self._win = x[i * HOP: i * HOP + NFFT]
            # envelope ring: ENV_SUB-sample RMS points, carrying leftover samples across hops
            seg = np.concatenate([self._env_rem, x[i * HOP: i * HOP + HOP]])
            n_blk = len(seg) // ENV_SUB
            blk = seg[: n_blk * ENV_SUB].reshape(-1, ENV_SUB)
            self._env_rem = seg[n_blk * ENV_SUB:]
            pts = np.sqrt(np.mean(blk * blk, axis=1))
            self._env = np.concatenate([self._env[len(pts):], pts])
            level, f0, corr, voiced, alpha, ham, flat = self.fa.analyse(self._win, self.floor)
            fr = Frame(i, i * self.hop_s, level, f0, corr, voiced, alpha, ham, flat,
                       roughness_proxy(self._env, self.fs, f0, ENV_SUB))
            self._on_frame(fr)
        if self._ev:
            self._finish_event()

    def _on_frame(self, fr: Frame):
        self.frames.append(fr)
        if not fr.voiced:
            self.floor = 0.995 * self.floor + 0.005 * fr.level
        if self._ev is None:
            if fr.voiced:
                self._ev = {"start": fr.idx, "frames": [fr], "gap": 0,
                            "pre": self.frames[-1 - PRE_FRAMES:-1], "post": []}
        else:
            ev = self._ev
            if fr.voiced:
                if 0 < ev["gap"] <= INNER_GAP:
                    ev["frames"].extend(ev["post"])
                ev["frames"].append(fr)
                ev["gap"], ev["post"] = 0, []
            else:
                ev["gap"] += 1
                ev["post"].append(fr)
                if ev["gap"] >= END_GAP:
                    self._finish_event()

    def _finish_event(self):
        ev, self._ev = self._ev, None
        vf = [f for f in ev["frames"] if f.voiced]
        if len(vf) < 4:                       # < ~85 ms: ignore
            return
        feat, helplike = event_features(ev["frames"], ev["pre"], ev["post"], self.floor, self.hop_s)
        d, z = distress_score(feat, self.baseline)
        feat["distress"] = d
        t_end = (ev["start"] + len(ev["frames"]) + len(ev["post"])) * self.hop_s
        # ---- bachao shape ------------------------------------------------------------
        bachao, absorbs = False, None
        runs = _runs(ev["frames"])                         # (a) inside this one event
        if len(runs) >= 3 and runs[-1][0].voiced and not runs[-2][0].voiced and runs[-3][0].voiced:
            bachao = bachao_fits(runs[-3], runs[-2], runs[-1], self.floor, self.hop_s)
        prev = self._prev                                   # (b) across two events
        if not bachao and prev is not None:
            start_t = ev["start"] * self.hop_s
            gap_s = start_t - self._prev_last_voiced_t - self.hop_s
            if BACHAO_GAP[0] <= gap_s <= BACHAO_GAP[1]:
                gap = [f for f in self.frames if self._prev_last_voiced_t < f.t and f.idx < ev["start"]]
                onset = [f for f in self.frames if f.t >= prev.t_start and f.t <= self._prev_last_voiced_t and f.voiced]
                if gap and onset and bachao_fits(onset, gap, vf, self.floor, self.hop_s):
                    bachao, absorbs = True, prev.id
        shape = "bachao" if bachao else ("help" if helplike else "voiced")
        feat["shape"] = shape
        e = Event(len(self.events) + 1, ev["start"] * self.hop_s, t_end, feat, helplike, d, z, shape, absorbs)
        self._prev, self._prev_last_voiced_t = e, vf[-1].t
        if self._audio is not None:
            s0 = max(0, (ev["start"] - PRE_FRAMES) * self.hop - int(0.05 * self.fs))
            s1 = min(len(self._audio), int(t_end * self.fs) + int(0.1 * self.fs))
            e.audio = self._audio[s0:s1]
        self.events.append(e)
        self._decide(e)

    def _decide(self, e: Event):
        f, z = e.feat, e.z
        # scream path: one severe, rough event
        if f["dur"] >= SCREAM_MIN_DUR and e.distress >= SCREAM_DISTRESS and z.get("rough", 0) >= SCREAM_ROUGH_Z:
            e.trigger = "scream"
            self.alerts.append((e.t_end, "scream",
                f"{f['dur']:.2f} s voiced, distress {e.distress:.2f}, roughness +{z['rough']:.1f}σ"))
            return
        # word path: N word-shaped events ("help" or "bachao") within the window, mean distress above threshold
        recent = [r for r in self.events
                  if r.wordlike and e.t_end - r.t_end <= WINDOW_S and r.id != e.absorbs]
        if len(recent) >= N_REPEAT:
            md = float(np.mean([r.distress for r in recent]))
            if md >= WORD_DISTRESS:
                e.trigger = "word"
                self.alerts.append((e.t_end, "word",
                    f"{len(recent)}× word-shaped in {WINDOW_S:.0f} s, mean distress {md:.2f}"))
            else:
                self.alerts.append((e.t_end, "word-only",
                    f"{len(recent)}× word-shaped in {WINDOW_S:.0f} s but tone calm (mean distress {md:.2f})"))

# ----------------------------------------------------------------------------
# Command line
# ----------------------------------------------------------------------------
def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return
    x, fs = read_wav(argv[1])
    det = Detector(fs)
    det.process(x)
    print(f"{argv[1]}: {len(x)/fs:.1f} s at {fs} Hz, noise floor {det.floor:.0f} dBFS, {len(det.events)} events")
    for e in det.events:
        f = e.feat
        tags = e.shape + (f" · {e.trigger} ALERT" if e.trigger else "")
        print(f"  #{e.id:3d} {e.t_start:7.2f}-{e.t_end:7.2f}s  dur {f['dur']:.2f}  distress {e.distress:.2f}  "
              f"f0 {f['f0hz']:4.0f} Hz  range {f['f0range']:4.1f} st  rough {f['rough']:.2f}  "
              f"alpha {f['alpha']:6.1f}  ham {f['ham']:5.1f}  {tags}")
    for t, kind, txt in det.alerts:
        print(f"  ALERT [{kind}] at {t:.2f} s: {txt}")
    if "--csv" in argv:
        out = argv[argv.index("--csv") + 1]
        cols = ["id", "t_start", "t_end", "trigger"] + list(det.events[0].feat.keys()) if det.events else []
        with open(out, "w", newline="") as fh:
            w = csv.writer(fh); w.writerow(cols)
            for e in det.events:
                w.writerow([e.id, f"{e.t_start:.3f}", f"{e.t_end:.3f}", e.trigger or ""] +
                           [f"{v:.4f}" if isinstance(v, float) else v for v in e.feat.values()])
        print(f"wrote {out}")

if __name__ == "__main__":
    main(sys.argv)
