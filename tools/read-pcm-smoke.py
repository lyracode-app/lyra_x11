"""Verify real PCM reaches the host FIFO at the expected pitch and stereo layout."""
import array
import os
import select
import sys
import time

fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NONBLOCK)
data = bytearray()
started = time.monotonic()
deadline = started + 50
last_signal = None
try:
    while time.monotonic() < deadline:
        if select.select([fd], [], [], 0.1)[0]:
            block = os.read(fd, 8192)
            if block:
                data.extend(block)
                if any(block):
                    last_signal = time.monotonic()
                if len(data) > 192000 * (time.monotonic() - started + 1):
                    raise RuntimeError('PCM producer ran faster than real time')
            else:
                time.sleep(0.01)
        if last_signal and time.monotonic() - last_signal > 0.5:
            break
finally:
    os.close(fd)
samples = array.array('h', data[:len(data) // 4 * 4])
if sys.byteorder != 'little':
    samples.byteswap()
left, right = samples[::2], samples[1::2]
assert left == right, 'Stereo channel/frame alignment changed'
signal = [i for i, sample in enumerate(left) if abs(sample) > 100]
assert len(signal) > 30000, f'Too little audio: {len(signal)} samples'
first, last = signal[0], signal[-1]
crossings = sum(left[i - 1] <= 0 < left[i] for i in range(first + 1, last + 1))
pitch = crossings * 48000 / (last - first)
assert 430 < pitch < 450, f'Wrong PCM pitch: {pitch}'
with open(sys.argv[2], 'w') as output:
    output.write(f'AUDIO_FIFO_OK backend={sys.argv[3]} rate=48000 channels=2 pitch={pitch:.1f}Hz frames={len(left)} elapsed={time.monotonic()-started:.1f}s\n')
