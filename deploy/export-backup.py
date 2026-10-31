#!/usr/bin/env python3
"""Read one checked Majung archive for the backend Actions identity only."""
import importlib.machinery
import importlib.util
import os
import shutil
import socket
import sys
import time


def main():
    if os.geteuid() != 0 or socket.gethostname().split('.')[0] != 'majung-dev-01' or len(sys.argv) != 1:
        raise RuntimeError('Unexpected backup export target')
    loader = importlib.machinery.SourceFileLoader('majung_operations', '/usr/local/sbin/majung-operations')
    spec = importlib.util.spec_from_loader(loader.name, loader)
    ops = importlib.util.module_from_spec(spec)
    loader.exec_module(ops)
    archive = ops.latest_backup()
    if not 0 <= time.time() - archive.stat().st_mtime <= 30 * 3600:
        raise RuntimeError('Backup expired')
    with archive.open('rb') as source:
        ops.run(['runuser', '-u', 'postgres', '--', 'pg_restore', '--list'], stdin=source)
    with archive.open('rb') as source:
        shutil.copyfileobj(source, sys.stdout.buffer)


if __name__ == '__main__':
    main()
