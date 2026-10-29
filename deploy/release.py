#!/usr/bin/env python3
"""Install once as root-owned /usr/local/sbin/majung-deploy; never execute a runner-supplied installer."""
import fcntl
import hashlib
import io
import json
import os
import pathlib
import pwd
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

RELEASE_ROOT = pathlib.Path('/var/lib/majung-deploy/releases')
TARGETS = {'backend': pathlib.Path('/opt/majung/majung-server.jar'), 'web': pathlib.Path('/srv/majung/web')}
SLOT_ROOT = pathlib.Path('/opt/majung/slots')
SLOT_STATE = pathlib.Path('/var/lib/majung-deploy/active-backend.json')
ACTIVE_SLOT = pathlib.Path('/etc/majung/active-slot')
UPSTREAM = pathlib.Path('/etc/caddy/majung-upstream.caddy')
SLOT_PORTS = {'blue': 8081, 'green': 8082}
DRAIN_SECONDS = 20

WEB_FILES = {'privacy.html', 'index.html', 'style.css', 'app.js', 'api.js', 'model.js', 'map.js', 'push.js', 'proposal.js', 'stream.js', 'sw.js', 'manifest.webmanifest',
             'account.html', 'account.css', 'account.js', 'account-api.js',
             'favicon.svg', 'favicon.png', 'apple-touch-icon.png', 'icon-192.png', 'icon-512.png'}


def checked_payload(stream, digest, limit):
    data = stream.read(limit + 1)
    if len(data) > limit or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError('Invalid artifact size or checksum')
    return data


def unpack_web(data, destination):
    """Reject links, traversal and unexpected files before writing any member."""
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        if len(names) != len(set(names)) or not {'index.html', 'app.js', 'api.js', 'model.js', 'style.css'} <= set(names):
            raise ValueError('Incomplete or duplicate web artifact')
        if any(not member.isfile() or member.name not in WEB_FILES or member.size > 2_000_000 for member in members):
            raise ValueError('Unexpected web member')
        if sum(member.size for member in members) > 10_000_000:
            raise ValueError('Web artifact too large')
        for member in members:
            path = destination / member.name
            path.write_bytes(archive.extractfile(member).read())
            path.chmod(0o644)


def verify_jar(data, revision):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        metadata = archive.getinfo('BOOT-INF/classes/majung-release.txt')
        if metadata.file_size > 64 or archive.read(metadata).decode().strip() != revision:
            raise ValueError('JAR revision does not match')
        if 'org/springframework/boot/loader/launch/JarLauncher.class' not in archive.namelist():
            raise ValueError('Not a Spring Boot executable JAR')


def probe(url, expected_revision=None, attempts=30, expected_slot=None):
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                body = response.read(65536)
                if response.status == 200:
                    if expected_revision is None and expected_slot is None: return True
                    state = json.loads(body)
                    if (expected_revision is None or state.get('revision') == expected_revision) and (
                            expected_slot is None or state.get('slot') == expected_slot): return True
        except Exception:
            pass
        time.sleep(1)
    return False


def run(*args):
    # No output of service logs/environment or application data in CI logs.
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)


def atomic_text(path, value):
    if path.is_symlink(): raise ValueError('Deployment state must not be a symlink')
    descriptor, name = tempfile.mkstemp(prefix='.majung-', dir=path.parent)
    temporary = pathlib.Path(name)
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(0o644)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def upstream_config(slot):
    port = SLOT_PORTS[slot]
    return (f'reverse_proxy 127.0.0.1:{port} {{\n'
            '    transport http {\n'
            '        dial_timeout 3s\n'
            '        response_header_timeout 10s\n'
            '    }\n'
            '}\n')


def publish_blue_green(revision, data):
    verify_jar(data, revision)
    state = json.loads(SLOT_STATE.read_text())
    active = state.get('active')
    if active not in SLOT_PORTS or not re.fullmatch('[0-9a-f]{40}', state.get('revision', '')):
        raise ValueError('Invalid active backend state')
    candidate = 'green' if active == 'blue' else 'blue'
    directory = SLOT_ROOT / candidate
    if not directory.is_dir() or directory.is_symlink(): raise ValueError('Slot is not prepared')
    previous_route = UPSTREAM.read_text()
    previous_slot = ACTIVE_SLOT.read_text()
    previous_state = SLOT_STATE.read_text()
    if previous_slot.strip() != active: raise ValueError('Active slot state disagrees')
    route_changed = False
    try:
        run('systemctl', 'stop', f'majung@{candidate}.service')
        descriptor, name = tempfile.mkstemp(prefix='server-', suffix='.jar', dir=directory)
        staged = pathlib.Path(name)
        try:
            with os.fdopen(descriptor, 'wb') as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            staged.chmod(0o644)
            staged.replace(directory / 'server.jar')
        finally:
            staged.unlink(missing_ok=True)
        run('systemctl', 'start', f'majung@{candidate}.service')
        if not probe(f'http://127.0.0.1:{SLOT_PORTS[candidate]}/api/v1/health', revision, expected_slot=candidate):
            raise RuntimeError('Candidate did not become healthy')
        run('systemctl', 'enable', f'majung@{candidate}.service')
        atomic_text(UPSTREAM, upstream_config(candidate))
        route_changed = True
        run('caddy', 'validate', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile')
        run('systemctl', 'reload', 'caddy')
        atomic_text(ACTIVE_SLOT, candidate + '\n')
        if not probe('https://majung.cloud/api/v1/health', revision, expected_slot=candidate):
            raise RuntimeError('Public candidate health check failed')
        atomic_text(SLOT_STATE, json.dumps({'active': candidate, 'revision': revision}) + '\n')
    except BaseException:
        if route_changed:
            atomic_text(UPSTREAM, previous_route)
            atomic_text(ACTIVE_SLOT, previous_slot)
            atomic_text(SLOT_STATE, previous_state)
            run('systemctl', 'reload', 'caddy')
            if not probe('https://majung.cloud/api/v1/health', state['revision'], expected_slot=active):
                raise RuntimeError('Previous backend recovery check failed') from None
        run('systemctl', 'disable', f'majung@{candidate}.service')
        run('systemctl', 'stop', f'majung@{candidate}.service')
        raise RuntimeError('Candidate rejected; previous backend remains selected') from None
    time.sleep(DRAIN_SECONDS)
    drained = True
    try:
        run('systemctl', 'disable', f'majung@{active}.service')
        run('systemctl', 'stop', f'majung@{active}.service')
    except Exception:
        drained = False
    print(json.dumps({'component': 'backend', 'revision': revision, 'status': 'deployed',
                      'slot': candidate, 'previousStopped': drained}))


def publish(kind, revision, data, policy):
    if kind == 'backend' and policy.get('backend_mode') == 'blue-green':
        return publish_blue_green(revision, data)
    base = RELEASE_ROOT
    base.mkdir(parents=True, exist_ok=True, mode=0o755)
    with tempfile.TemporaryDirectory(prefix=kind + '-', dir=base) as temporary:
        candidate = pathlib.Path(temporary)
        if kind == 'backend':
            verify_jar(data, revision)
            staged = candidate / 'server.jar'
            staged.write_bytes(data)
            staged.chmod(0o644)
            target = TARGETS['backend']
            health = 'http://127.0.0.1:8080/api/v1/health'
            fallback = 'http://127.0.0.1:8080/api/v1/pickups/config'
        else:
            staged = candidate / 'web'
            staged.mkdir(mode=0o755)
            unpack_web(data, staged)
            (staged / 'release.json').write_text(json.dumps({'revision': revision}))
            (staged / 'release.json').chmod(0o644)
            target = TARGETS['web']
            health = 'https://majung.cloud/release.json'
            fallback = 'https://majung.cloud/'
        if not target.exists() or target.is_symlink():
            raise ValueError('Expected an existing regular deployment target')
        previous = candidate / 'previous'
        swapped = False
        try:
            target.rename(previous)
            staged.rename(target)
            swapped = True
            if kind == 'backend': run('systemctl', 'restart', 'majung.service')
            if not probe(health, revision): raise RuntimeError('New release health check failed')
        except BaseException:
            if swapped:
                failed = candidate / 'failed'
                target.rename(failed)
            if previous.exists(): previous.rename(target)
            if kind == 'backend': run('systemctl', 'restart', 'majung.service')
            restored = probe(fallback, attempts=15)
            raise RuntimeError('Deployment failed; previous release restored' if restored else 'Deployment and recovery check failed') from None
        # Keep one previously working release per component for operator recovery.
        backup = base / (kind + '-previous')
        if backup.is_dir(): shutil.rmtree(backup)
        elif backup.exists(): backup.unlink()
        previous.rename(backup)
        print(json.dumps({'component': kind, 'revision': revision, 'status': 'deployed'}))


def main():
    def interrupted(signum, frame):
        raise InterruptedError('Deployment interrupted')
    for name in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(name, interrupted)
    if os.geteuid() != 0 or len(sys.argv) != 4:
        raise ValueError('Expected privileged deploy with component, revision and SHA256')
    kind, revision, digest = sys.argv[1:]
    if kind not in {'backend', 'web'} or not re.fullmatch('[0-9a-f]{40}', revision) or not re.fullmatch('[0-9a-f]{64}', digest):
        raise ValueError('Invalid deployment arguments')
    config = pathlib.Path('/etc/majung/deploy-policy.json')
    info = config.stat()
    if info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise ValueError('Deployment policy must be root-owned mode 600')
    policy = json.loads(config.read_text())
    caller = pwd.getpwuid(int(os.environ.get('SUDO_UID', '-1'))).pw_name
    if kind not in policy.get('allowed_users', {}).get(caller, []):
        raise ValueError('Caller is not allowed to deploy')
    request = urllib.request.Request('http://metadata.google.internal/computeMetadata/v1/instance/id', headers={'Metadata-Flavor': 'Google'})
    with urllib.request.urlopen(request, timeout=2) as response:
        actual = response.read(64).decode().strip()
    if actual != str(policy['instance_id']): raise ValueError('Unexpected instance')
    with open('/run/lock/majung-deploy.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        data = checked_payload(sys.stdin.buffer, digest, 100_000_000 if kind == 'backend' else 5_000_000)
        publish(kind, revision, data, policy)


if __name__ == '__main__':
    try: main()
    except Exception as failure:
        # Use controlled messages only; do not print exceptions that may contain private paths or request data.
        print('Majung deployment rejected or failed; check the server deployment state', file=sys.stderr)
        sys.exit(1)
