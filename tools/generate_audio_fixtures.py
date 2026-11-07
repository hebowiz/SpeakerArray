"""Generate original synthetic test audio (requires ffmpeg on PATH; no external media)."""
from pathlib import Path
import math
import struct
import subprocess
import wave

root = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/audio'
root.mkdir(parents=True, exist_ok=True)
rate = 44100
frames = rate
samples = [(0.2 * math.sin(2 * math.pi * 440 * i / rate),
            0.1 * math.sin(2 * math.pi * 880 * i / rate)) for i in range(frames)]
with wave.open(str(root / 'tone.wav'), 'wb') as out:
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(rate)
    out.writeframes(b''.join(struct.pack('<hh', round(l * 32767), round(r * 32767)) for l, r in samples))
pcm = b''.join(struct.pack('<ff', l, r) for l, r in samples)
(root / 'reference-float.wav').write_bytes(
    b'RIFF' + struct.pack('<I', 36 + len(pcm)) + b'WAVEfmt ' +
    struct.pack('<IHHIIHH', 16, 3, 2, rate, rate * 8, 8, 32) + b'data' + struct.pack('<I', len(pcm)) + pcm)
formats = [
    ('tone.mp3', ['-c:a', 'libmp3lame', '-b:a', '192k']),
    ('tone.flac', ['-c:a', 'flac']),
    ('tone.m4a', ['-c:a', 'aac', '-b:a', '192k']),
    ('tone.aac', ['-c:a', 'aac', '-b:a', '192k', '-f', 'adts']),
    ('tone.ogg', ['-c:a', 'libvorbis', '-q:a', '5']),
    ('tone.opus', ['-c:a', 'libopus', '-b:a', '128k']),
    ('tone-24.flac', ['-c:a', 'flac', '-sample_fmt', 's32']),
]
for name, options in formats:
    source = 'reference-float.wav' if name == 'tone-24.flac' else 'tone.wav'
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
                    '-i', str(root / source), '-map_metadata', '-1', *options, str(root / name)], check=True)
subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi',
                '-i', 'anullsrc=r=48000:cl=5.1', '-t', '0.1', '-c:a', 'flac', str(root / 'surround.flac')], check=True)
print(f'Generated {len(list(root.iterdir()))} audio fixtures in {root}')
