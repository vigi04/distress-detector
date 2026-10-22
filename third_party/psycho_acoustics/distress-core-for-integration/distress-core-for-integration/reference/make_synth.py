"""Generate synthetic test WAVs (48 kHz mono 16-bit) for the Kotlin-vs-Python comparison."""
import numpy as np, wave, sys, os
RATE = 48000
rng = np.random.default_rng(7)
out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)

def silence(sec): return (rng.random(int(sec*RATE)) - 0.5) * 0.002
def noise(sec, amp): return (rng.random(int(sec*RATE)) - 0.5) * 2 * amp
def vowel(sec, f0a, f0b, amp, am_depth=0.0, am_hz=0.0, jitter=0.0, fade_out=0.0):
    n = int(sec*RATE); t = np.arange(n)/RATE
    f0 = f0a + (f0b-f0a)*np.arange(n)/n
    if jitter:
        steps = np.repeat(1 + jitter*(rng.random(n//480+1)-0.5)*2, 480)[:n]
        f0 = f0*steps
    phase = 2*np.pi*np.cumsum(f0)/RATE
    s = sum(np.sin(h*phase)/h**1.2 for h in range(1, 13)) * 0.5
    if am_depth: s *= 1 - am_depth*(0.5+0.5*np.sin(2*np.pi*am_hz*t))
    g = np.full(n, amp)
    if fade_out:
        fn = int(fade_out*RATE); g[n-fn:] *= np.linspace(1, 0, fn)
    return s*g + (rng.random(n)-0.5)*0.002
def help_(calm, stretch=0.25):
    parts = [noise(0.04, 0.03 if calm else 0.12)]
    parts.append(vowel(stretch, 150, 140, 0.03) if calm else vowel(stretch, 320, 420, 0.35, 0.6, 70, 0.03))
    parts.append(silence(0.02))
    return np.concatenate(parts)
def bachao(calm):
    return np.concatenate([
        vowel(0.10, 150 if calm else 300, 150 if calm else 320, 0.03 if calm else 0.3),
        noise(0.07, 0.03 if calm else 0.1),
        vowel(0.45, 150, 140, 0.03, fade_out=0.15) if calm else vowel(0.9, 330, 450, 0.35, 0.6, 75, 0.03, fade_out=0.2)])
def scream(): return np.concatenate([noise(0.02, 0.05), vowel(0.7, 500, 650, 0.5, 0.8, 90, 0.05), silence(0.02)])

cases = {
 "01_calm_help_x3": [silence(1)] + sum([[help_(True), silence(0.8)] for _ in range(3)], []) + [silence(1)],
 "02_frightened_help_x3": [silence(1)] + sum([[help_(False), silence(0.6)] for _ in range(3)], []) + [silence(1)],
 "03_frightened_heeelp_x3": [silence(1)] + sum([[help_(False, 1.5), silence(0.4)] for _ in range(3)], []) + [silence(1)],
 "04_help_me_x3_frightened": [silence(1)] + sum([[help_(False), silence(0.05), vowel(0.15, 300, 280, 0.25, fade_out=0.1), silence(0.5)] for _ in range(3)], []) + [silence(1)],
 "05_single_scream": [silence(1), scream(), silence(1.5)],
 "06_calm_bachao_x3": [silence(1)] + sum([[bachao(True), silence(0.8)] for _ in range(3)], []) + [silence(1)],
 "07_frightened_bachao_x3": [silence(1)] + sum([[bachao(False), silence(0.6)] for _ in range(3)], []) + [silence(1)],
 "08_mixed_help_bachao_help": [silence(1), help_(False), silence(0.6), bachao(False), silence(0.6), help_(False), silence(1)],
 "09_plain_tone_x3": [silence(1)] + sum([[vowel(0.4, 200, 200, 0.1, fade_out=0.3), silence(0.8)] for _ in range(3)], []) + [silence(1)],
 "10_loud_calm_help_x3": [silence(1)] + sum([[noise(0.04, 0.1), vowel(0.3, 170, 160, 0.3), silence(0.8)] for _ in range(3)], []) + [silence(1)],
}
for name, parts in cases.items():
    x = np.clip(np.concatenate(parts), -1, 1)
    with wave.open(os.path.join(out_dir, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((x*32767).astype("<i2").tobytes())
    print(name, f"{len(x)/RATE:.1f}s")
