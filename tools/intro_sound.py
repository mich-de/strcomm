#!/usr/bin/env python3
"""Synthesise the launch-splash sting of the touch app (client/src/main/res/raw/strcomm_intro.ogg).

Our own sound, made from scratch here (no sample, no recording): a rising whoosh, one deep impact
when "StrComm" lands, then three bell tones, one per stripe of the tricolore as it draws under
"ITALIA". Timings match StrCommSplash (ui/components/Splash.kt): impact at 380 ms, bells at 900 /
1000 / 1100 ms.

    python3 tools/intro_sound.py out.wav && ffmpeg -i out.wav -c:a libvorbis -q:a 5 strcomm_intro.ogg
"""

import sys
import wave

import numpy as np

SR = 44100
LENGTH = 2.6
IMPACT = 0.38
BELLS = [(0.90, 783.99), (1.00, 987.77), (1.10, 1174.66)]  # G5, B5, D6 — a G major arpeggio

rng = np.random.default_rng(7)
n = int(SR * LENGTH)
t = np.arange(n) / SR
out = np.zeros(n)


def one_pole_lowpass(x, cutoff):
  """Lowpass with a per-sample cutoff (Hz), so the whoosh can open up as it rises."""
  y = np.zeros_like(x)
  acc = 0.0
  for i in range(len(x)):
    a = 1.0 - np.exp(-2.0 * np.pi * cutoff[i] / SR)
    acc += a * (x[i] - acc)
    y[i] = acc
  return y


# Whoosh: noise swelling into the impact, its filter opening from 300 Hz to 6 kHz.
w_end = int(SR * IMPACT)
noise = rng.standard_normal(w_end)
cut = np.geomspace(300, 6000, w_end)
whoosh = one_pole_lowpass(noise, cut) - one_pole_lowpass(noise, cut * 0.25)
whoosh *= np.linspace(0, 1, w_end) ** 2.2
out[:w_end] += 0.55 * whoosh / (np.abs(whoosh).max() + 1e-9)

# Impact: a sub "boom" falling 110 -> 42 Hz, a short noise transient, a low fifth under it.
i0 = int(SR * IMPACT)
tt = t[i0:] - IMPACT
freq = 42 + (110 - 42) * np.exp(-tt / 0.07)
phase = 2 * np.pi * np.cumsum(freq) / SR
boom = np.sin(phase) * np.exp(-tt / 0.55)
click = one_pole_lowpass(rng.standard_normal(len(tt)), np.full(len(tt), 2500.0)) * np.exp(-tt / 0.012)
body = (np.sin(2 * np.pi * 73.42 * tt) + 0.6 * np.sin(2 * np.pi * 110.0 * tt)) * np.exp(-tt / 0.9)
out[i0:] += 0.95 * boom + 0.35 * click / (np.abs(click).max() + 1e-9) + 0.22 * body

# Bells: a struck tone with inharmonic partials, fast attack, long ring.
for start, f in BELLS:
  j = int(SR * start)
  tb = t[j:] - start
  env = (1 - np.exp(-tb / 0.004)) * np.exp(-tb / 0.75)
  bell = np.sin(2 * np.pi * f * tb) + 0.45 * np.sin(2 * np.pi * f * 2.76 * tb) * np.exp(-tb / 0.25)
  bell += 0.25 * np.sin(2 * np.pi * f * 5.4 * tb) * np.exp(-tb / 0.08)
  out[j:] += 0.16 * env * bell

# Gentle fade at the very end, normalise to -1 dBFS.
fade = int(SR * 0.4)
out[-fade:] *= np.linspace(1, 0, fade)
out *= 10 ** (-1 / 20) / np.abs(out).max()

pcm = (out * 32767).astype(np.int16)
with wave.open(sys.argv[1] if len(sys.argv) > 1 else "strcomm_intro.wav", "wb") as f:
  f.setnchannels(1)
  f.setsampwidth(2)
  f.setframerate(SR)
  f.writeframes(pcm.tobytes())
