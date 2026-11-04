#!/usr/bin/env python3
"""Sample host and Java resource use without collecting command arguments."""

import argparse
import datetime
import json
from pathlib import Path
import re
import signal
import subprocess
import time


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=5)
    if result.returncode:
        raise RuntimeError(f'{args[0]} exited {result.returncode}: {result.stderr.strip()}')
    return result.stdout


def cpu_seconds(value):
    days = 0
    if '-' in value:
        day_text, value = value.split('-', 1)
        days = int(day_text)
    total = 0.0
    for part in value.split(':'):
        total = total * 60 + float(part)
    return total + days * 86400


def java_processes():
    processes = []
    for line in command('ps', '-axo', 'pid=,ppid=,time=,rss=,comm=').splitlines():
        fields = line.strip().split(maxsplit=4)
        if len(fields) != 5:
            continue
        if Path(fields[4]).name != 'java':
            continue
        processes.append({
            'pid': int(fields[0]),
            'ppid': int(fields[1]),
            'cpu_seconds': cpu_seconds(fields[2]),
            'rss_kib': int(fields[3]),
        })
    return processes


def snapshot(previous):
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    vm_text = command('vm_stat')
    page_size = int(re.search(r'page size of (\d+) bytes', vm_text)[1])
    vm = {
        key: int(value)
        for key, value in re.findall(r'^([^:]+):\s+(\d+)\.', vm_text, re.MULTILINE)
        if key in (
            'Pages free', 'Pages inactive', 'Pages occupied by compressor',
            'Compressions', 'Decompressions', 'Pageins', 'Pageouts', 'Swapins', 'Swapouts',
        )
    }
    swap = {
        key + '_mib': float(value)
        for key, value in re.findall(r'(total|used|free) = ([\d.]+)M', command('sysctl', 'vm.swapusage'))
    }
    processes = java_processes()
    # The second iostat sample covers one second instead of time since boot.
    iostat = command('iostat', '-c', '2', '-w', '1')
    sample = iostat.strip().splitlines()[-1].split()
    user, system, idle = map(float, sample[-6:-3])
    current = {
        'timestamp': stamp,
        'monotonic_seconds': time.monotonic(),
        'page_size_bytes': page_size,
        'vm_pages': vm,
        'swap': swap,
        'cpu_one_second_percent': {'user': user, 'system': system, 'idle': idle},
        'java': processes,
        'java_count': len(processes),
        'java_rss_kib': sum(p['rss_kib'] for p in processes),
    }
    if previous is not None:
        current['interval_seconds'] = current['monotonic_seconds'] - previous['monotonic_seconds']
        counters = ('Compressions', 'Decompressions', 'Pageins', 'Pageouts', 'Swapins', 'Swapouts')
        current['vm_delta_pages'] = {key: vm[key] - previous['vm_pages'][key] for key in counters}
        previous_cpu = {p['pid']: p['cpu_seconds'] for p in previous['java']}
        current['surviving_java_cpu_seconds_delta'] = sum(
            p['cpu_seconds'] - previous_cpu[p['pid']] for p in processes if p['pid'] in previous_cpu
        )
    return current


def stop(_signal, _frame):
    raise SystemExit(0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--interval', type=float, default=30)
    parser.add_argument('--samples', type=int)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, stop)
    print(json.dumps({
        'hardware': {
            key: command('sysctl', '-n', key).strip()
            for key in ('hw.memsize', 'hw.logicalcpu', 'hw.pagesize')
        }
    }), flush=True)
    previous = None
    count = 0
    while args.samples is None or count < args.samples:
        started = time.monotonic()
        try:
            current = snapshot(previous)
            print(json.dumps(current), flush=True)
            previous = current
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            print(json.dumps({'sampler_error': str(error)}), flush=True)
        count += 1
        if args.samples is not None and count == args.samples:
            break
        time.sleep(max(0, args.interval - (time.monotonic() - started)))


if __name__ == '__main__':
    main()
