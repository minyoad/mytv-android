#!/usr/bin/env python3
"""Summarize captured logcat Performance lines. No fabricated measurements or URLs are included."""
import argparse
import json
import math
from pathlib import Path
import re

METRIC = re.compile(r'\b(startup_to_composition_ms|startup_to_first_video_frame_ms|playback_to_first_frame_ms)=(\d+)\b')


def summarize(text):
    samples = {}
    for name, value in METRIC.findall(text):
        samples.setdefault(name, []).append(int(value))
    result = {}
    for name, values in samples.items():
        values.sort()
        result[name] = {'count': len(values), 'min_ms': values[0], 'max_ms': values[-1],
                        'p50_ms': values[math.ceil(len(values) * .50) - 1],
                        'p95_ms': values[math.ceil(len(values) * .95) - 1]}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    args = parser.parse_args()
    result = summarize(args.log.read_text())
    if not result:
        parser.error('No performance samples found; run on a device before generating a report')
    print(json.dumps(result, indent=2))
