#!/usr/bin/env python3
"""Copy a private archive over pinned SSH; verify a downloaded GCS generation."""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import uuid


def run(args, **kwargs):
    return subprocess.run(args, check=True, timeout=240, **kwargs)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def main():
    values = {key: os.environ[key] for key in ('GCP_PROJECT_ID', 'GCP_ZONE', 'GCP_INSTANCE',
              'GCP_INSTANCE_ID', 'GCP_SSH_USER', 'GCP_SSH_HOST_KEY')}
    project, zone, instance, identity, user, host_key = values.values()
    if project != 'fleaflea-511001' or zone != 'asia-northeast3-a' or instance != 'majung-dev-01' or identity != '5245204336249082788':
        raise RuntimeError('Unexpected backup target')
    if not re.fullmatch(r'[a-z_][a-z0-9_-]+', user) or '\n' in host_key:
        raise RuntimeError('Invalid SSH configuration')
    if os.environ.get('GITHUB_REPOSITORY') != 'majung-team/majung-backend' or os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise RuntimeError('Only the backend main workflow can export backups')
    actual = run(['gcloud', 'compute', 'instances', 'describe', instance, '--project=' + project,
                  '--zone=' + zone, '--format=value(id)'], capture_output=True, text=True).stdout.strip()
    if actual != identity:
        raise RuntimeError('Instance identity mismatch')
    ssh = pathlib.Path.home() / '.ssh'
    ssh.mkdir(mode=0o700, exist_ok=True)
    # gcloud's first SSH key generation writes informational text to stdout.
    # Generate it before opening the binary archive stream.
    key = ssh / 'google_compute_engine'
    if not key.exists():
        run(['ssh-keygen', '-q', '-t', 'rsa', '-b', '3072', '-N', '', '-f', str(key)],
            stdout=subprocess.DEVNULL)
    known = ssh / 'google_compute_known_hosts'
    known.write_text('compute.' + identity + ' ' + host_key + '\n')
    known.chmod(0o600)
    bucket = 'gs://majung-private-backups-305499581905'
    with tempfile.TemporaryDirectory(prefix='majung-backup-') as folder:
        archive = pathlib.Path(folder) / 'backup.dump'
        with archive.open('xb') as output:
            archive.chmod(0o600)
            run(['gcloud', 'compute', 'ssh', user + '@' + instance, '--project=' + project,
                 '--zone=' + zone, '--tunnel-through-iap', '--strict-host-key-checking=yes',
                 '--ssh-key-expire-after=10m', '--quiet',
                 '--command=sudo -n /usr/local/sbin/majung-export-backup'], stdout=output)
        with archive.open('rb') as source:
            signature = source.read(5)
        if not archive.stat().st_size or signature != b'PGDMP':
            raise RuntimeError('Not a PostgreSQL custom archive')
        sha = digest(archive)
        target = bucket + '/postgresql/' + uuid.uuid4().hex + '.dump'
        run(['gcloud', 'storage', 'cp', str(archive), target, '--if-generation-match=0',
             '--custom-metadata=sha256=' + sha], stdout=subprocess.DEVNULL)
        metadata = json.loads(run(['gcloud', 'storage', 'objects', 'describe', target,
                                  '--format=json'], capture_output=True, text=True).stdout)
        generation = str(metadata['generation'])
        if not generation.isdigit():
            raise RuntimeError('Invalid object generation')
        restored = pathlib.Path(folder) / 'download.dump'
        run(['gcloud', 'storage', 'cp', target + '#' + generation, str(restored)], stdout=subprocess.DEVNULL)
        restored.chmod(0o600)
        if digest(restored) != sha:
            raise RuntimeError('Downloaded backup differs')
        print(json.dumps({'uploaded': True, 'downloadVerified': True, 'bytes': archive.stat().st_size}))


if __name__ == '__main__':
    os.umask(0o077)
    main()
