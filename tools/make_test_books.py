#!/usr/bin/env python3
"""Writes two small audiobooks for the emulator smoke test.

    testbooks/Audiobooks/Smoke Book/Chapter 1.wav, Chapter 2.wav, Chapter 10.wav
                                    cover.png
    testbooks/Audiobooks/Short Book/01 Opening.wav, 02 Closing.wav

"Chapter 10" sorts between 1 and 2 as plain text, so the test can check the
app plays files in number order. Standard library only: no network, no ffmpeg.
"""
import math
import os
import struct
import sys
import wave
import zlib

RATE = 8000


def tone(path, seconds, pitch):
    """A quiet beeping tone: half a second on, half off, so it is plainly audio."""
    frames = bytearray()
    for i in range(int(seconds * RATE)):
        t = i / RATE
        on = (t % 1.0) < 0.5
        v = int(6000 * math.sin(2 * math.pi * pitch * t)) if on else 0
        frames += struct.pack('<h', v)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(frames))


def png(path, size, top, bottom):
    """A vertical gradient PNG, written by hand."""
    rows = bytearray()
    for y in range(size):
        k = y / (size - 1)
        px = bytes(round(a + (b - a) * k) for a, b in zip(top, bottom))
        rows += b'\x00' + px * size

    def chunk(kind, data):
        c = kind + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xFFFFFFFF)

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(bytes(rows), 9)))
        f.write(chunk(b'IEND', b''))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'testbooks'
    smoke = os.path.join(out, 'Audiobooks', 'Smoke Book')
    short = os.path.join(out, 'Audiobooks', 'Short Book')
    os.makedirs(smoke, exist_ok=True)
    os.makedirs(short, exist_ok=True)
    # 90 seconds each: long enough that a minute of tests never runs off the end.
    tone(os.path.join(smoke, 'Chapter 1.wav'), 90, 440)
    tone(os.path.join(smoke, 'Chapter 2.wav'), 90, 523)
    tone(os.path.join(smoke, 'Chapter 10.wav'), 90, 659)
    png(os.path.join(smoke, 'cover.png'), 96, (233, 120, 60), (60, 40, 120))
    # Short files for the sleep timer's "end of this chapter".
    tone(os.path.join(short, '01 Opening.wav'), 15, 392)
    tone(os.path.join(short, '02 Closing.wav'), 15, 330)
    for root, _, files in os.walk(out):
        for f in sorted(files):
            p = os.path.join(root, f)
            print(f'{os.path.getsize(p):>9}  {p}')


if __name__ == '__main__':
    main()
