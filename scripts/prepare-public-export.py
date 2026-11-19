#!/usr/bin/env python3
"""Copy Git-visible working files without history, ignored secrets or build outputs."""
import argparse
import pathlib
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('destination', type=pathlib.Path, help='New directory outside this checkout')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
destination = args.destination.expanduser().resolve()
if destination == root or root in destination.parents:
    parser.error('Destination must be outside the source checkout')
if destination.exists():
    parser.error('Destination already exists; choose a new empty location')
paths = subprocess.check_output(
    ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root
).decode().split('\0')
files = []
for name in sorted(set(paths) - {''}):
    source = root / name
    if source.is_symlink():
        parser.error(f'Review symlink before exporting: {name}')
    if source.is_file():
        files.append((source, destination / name))
for source, target in files:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
print(f'Exported {len(files)} working files to {destination}. No Git history was copied.')
print('Review the export, choose a source license, scan for secrets, then git init and publish there.')
