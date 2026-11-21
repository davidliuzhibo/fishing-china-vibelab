# v0.4.0: the line-out whirr and the rod-holder click (audio/whirr.wav, audio/click.wav), from the shared synth.
# uv run --with numpy python -I tools/sfx_whirr_click.py ../../tools/sfx_synth.py app/app/src/main/assets/audio
import sys, importlib.util
import numpy as np
spec = importlib.util.spec_from_file_location("s", sys.argv[1]); s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)
SR = s.SR
# Line paying off a spinning spool: a ratchet whose rate falls from ~70 to ~12 clicks/s over 1.3 s, over a soft hiss.
sec = 1.3
x = np.zeros(int(SR * sec))
t = 0.0
while t < sec - 0.03:
    u = t / sec
    rate = 70 * (1 - u) + 12 * u
    c = s.click_wave(98 + int(s.rng.integers(-2, 3)), 0.015)
    o = int(t * SR)
    x[o:o + len(c)] += c * (0.55 + 0.25 * s.rng.random()) * (1 - 0.6 * u)
    t += 1.0 / rate
tt = np.arange(len(x)) / SR
hiss = s.bp_fast(s.noise(sec), 1500, 6000) * 0.12 * np.exp(-tt / 0.6)
x = x + hiss[:len(x)]
s.write(sys.argv[2] + "/whirr.wav", s.norm(x, 0.7))
s.write(sys.argv[2] + "/click.wav", s.norm(s.woodblock(79, 0.18) * 0.6 + np.pad(s.click_wave(88, 0.05), (0, int(SR*0.18) - int(SR*0.05))), 0.8))
