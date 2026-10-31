#!/usr/bin/env python3
"""Majung-only host checks. Backups and restored data never leave the VM."""
import argparse
import fcntl
import grp
import json
import os
import pathlib
import re
import shlex
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

STATE = pathlib.Path('/var/lib/majung-operations')
BACKUPS = pathlib.Path('/var/backups/majung')
CORE = {'pickup', 'member_account', 'registered_vehicle', 'ride_request', 'arrival_push',
        'member_connection', 'vehicle_account_link', 'spring_session', 'spring_session_attributes'}


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, timeout=180, **kwargs)


def sql(database, statement):
    if database != 'majung' and not re.fullmatch(r'majung_restore_check_[0-9a-f]{16}', database):
        raise ValueError('Unexpected database')
    return run(['runuser', '-u', 'postgres', '--', 'psql', '-X', '-At', '-v',
                'ON_ERROR_STOP=1', '-d', database, '-c', statement], text=True).stdout.strip()


def write_state(name, data, public=False):
    STATE.mkdir(mode=0o750, exist_ok=True)
    fd, filename = tempfile.mkstemp(dir=STATE, prefix='.state-')
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(data, output)
        if public:
            os.chown(filename, 0, grp.getgrnam('majung').gr_gid)
            os.chmod(filename, 0o640)
        os.replace(filename, STATE / name)
    finally:
        pathlib.Path(filename).unlink(missing_ok=True)


def latest_backup():
    files = [p for p in BACKUPS.glob('majung-*.dump') if not p.is_symlink() and p.is_file()]
    if not files:
        raise RuntimeError('Backup missing')
    result = max(files, key=lambda p: p.stat().st_mtime)
    if result.stat().st_size == 0 or result.stat().st_mode & 0o077:
        raise RuntimeError('Unsafe backup')
    return result


def environment():
    result = {}
    for line in pathlib.Path('/etc/majung/server.env').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if sep:
            parsed = shlex.split(value)
            result[key.strip()] = parsed[0] if len(parsed) == 1 else value.strip()
    if not re.fullmatch(r'jdbc:postgresql://(?:127\.0\.0\.1|localhost):5432/majung(?:\?.*)?', result.get('DB_URL', '')):
        raise RuntimeError('Unexpected source database')
    return result


def restore():
    archive = latest_backup()
    env = environment()
    database = 'majung_restore_check_' + uuid.uuid4().hex[:16]
    created = False
    process = None
    start = time.monotonic()
    try:
        run(['runuser', '-u', 'postgres', '--', 'createdb', '--template=template0', database])
        created = True
        with archive.open('rb') as source:
            run(['runuser', '-u', 'postgres', '--', 'pg_restore', '--single-transaction',
                 '--no-owner', '--no-acl', '--dbname=' + database], stdin=source)
        tables = set(sql(database, "SELECT tablename FROM pg_tables WHERE schemaname='public'").splitlines())
        if not CORE <= tables:
            raise RuntimeError('Required tables missing')
        if sql(database, "SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace AND NOT convalidated") != '0':
            raise RuntimeError('Unvalidated constraints')
        role = env['DB_USER']
        if not re.fullmatch(r'[a-z_][a-z0-9_]*', role):
            raise RuntimeError('Invalid application role')
        # Grants affect only this throwaway DB, never the original majung database.
        sql(database, 'GRANT USAGE ON SCHEMA public TO "' + role + '"; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "' + role + '";')
        counts = {}
        for table in sorted(CORE):
            counts[table] = int(sql(database, 'BEGIN READ ONLY; SET LOCAL ROLE "' + role + '"; SELECT count(*) FROM "' + table + '"; COMMIT;').splitlines()[-2])
        slot = pathlib.Path('/etc/majung/active-slot').read_text().strip()
        if slot not in ('blue', 'green'):
            raise RuntimeError('Unknown active slot')
        jar = pathlib.Path('/opt/majung/slots') / slot / 'server.jar'
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', 18099)) == 0:
                raise RuntimeError('Restore port already used')
        child_env = os.environ | env | {
            'DB_URL': 'jdbc:postgresql://127.0.0.1:5432/' + database,
            'MAJUNG_SLOT': 'restore-check', 'MAJUNG_LOGIN_ENABLED': 'false',
            'VAPID_PUBLIC_KEY': '', 'VAPID_PRIVATE_KEY': '', 'VAPID_SUBJECT': '',
        }
        # Invalid slot prevents application cleanup/unlink jobs. No OAuth or push provider is enabled.
        log_fd = os.open(STATE / 'restore-server.log', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        functional = STATE / 'restore-functional.json'
        functional.unlink(missing_ok=True)
        with os.fdopen(log_fd, 'wb') as log:
            process = subprocess.Popen(['/usr/bin/java', '-Xms32m', '-Xmx192m', '-XX:MaxMetaspaceSize=160m',
                '-Dloader.main=app.majung.server.pickup.RestoreVerification',
                '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                env=child_env, stdout=log, stderr=log)
            healthy = False
            for _ in range(60):
                if process.poll() is not None:
                    break
                try:
                    with urllib.request.urlopen('http://127.0.0.1:18099/api/v1/health', timeout=2) as response:
                        healthy = json.load(response).get('status') == 'UP'
                    if healthy and functional.exists():
                        break
                    healthy = False
                except (OSError, ValueError):
                    pass
                time.sleep(1)
            if not healthy:
                raise RuntimeError('Restored server health failed')
        result = {'ok': True, 'checkedAt': int(time.time()), 'tablesChecked': len(counts),
                  'rowCounts': counts, 'serverHealth': 'UP', 'schemaValidation': True,
                  'functional': json.loads(functional.read_text()),
                  'seconds': round(time.monotonic() - start, 2)}
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if created:
            # Destructive commands are restricted to the exact random name generated above.
            if not re.fullmatch(r'majung_restore_check_[0-9a-f]{16}', database):
                raise RuntimeError('Unsafe cleanup target')
            run(['runuser', '-u', 'postgres', '--', 'dropdb', '--force', database])
    write_state('restore.json', result)
    (STATE / 'restore-failure.json').unlink(missing_ok=True)
    print(json.dumps(result))


def summarize_metrics(text, previous):
    # Only fixed metric names and aggregate values survive; no URI/user label is stored.
    counters = {'requests': 0, 'errors': 0, 'loginSuccess': 0, 'loginFailure': 0, 'buckets': {}}
    gauges = {'dbActive': 0, 'dbMax': 0, 'dbWaiting': 0, 'startedAt': 0}
    for line in text.splitlines():
        match = re.fullmatch(r'([a-zA-Z_][a-zA-Z0-9_]*)(?:\{(.*)\})? ([+0-9.eE-]+)', line)
        if not match:
            continue
        name, labels, raw = match.groups()
        labels = dict(re.findall(r'([a-zA-Z_][a-zA-Z0-9_]*)="([^"\\]*)"', labels or ''))
        value = float(raw)
        if name.startswith('http_server_requests_seconds_'):
            uri = labels.get('uri', '')
            # Health probes and long-lived SSE connections distort interactive API latency.
            if not uri.startswith('/api/') or '/health' in uri or 'events' in uri or 'stream' in uri:
                continue
            if name.endswith('_count'):
                counters['requests'] += value
                if labels.get('status', '').startswith('5'):
                    counters['errors'] += value
            elif name.endswith('_bucket'):
                upper = labels.get('le')
                if upper:
                    counters['buckets'][upper] = counters['buckets'].get(upper, 0) + value
        elif name == 'majung_login_results_total':
            key = {'success': 'loginSuccess', 'failure': 'loginFailure'}.get(labels.get('result'))
            if key:
                counters[key] += value
        else:
            key = {'hikaricp_connections_active': 'dbActive', 'hikaricp_connections_max': 'dbMax',
                   'hikaricp_connections_pending': 'dbWaiting', 'process_start_time_seconds': 'startedAt'}.get(name)
            if key:
                gauges[key] += value
    old = previous.get('metricCounters', {})
    if previous.get('startedAt') != gauges['startedAt']:
        old = counters
    count = max(0, counters['requests'] - old.get('requests', counters['requests']))
    errors = max(0, counters['errors'] - old.get('errors', counters['errors']))
    attempts = sum(max(0, counters[k] - old.get(k, counters[k])) for k in ('loginSuccess', 'loginFailure'))
    failures = max(0, counters['loginFailure'] - old.get('loginFailure', counters['loginFailure']))
    p95 = None
    if count:
        for upper, total in sorted(counters['buckets'].items(), key=lambda pair: float(pair[0])):
            delta = max(0, total - old.get('buckets', {}).get(upper, total))
            if delta >= count * 0.95:
                p95 = float(upper) if upper != '+Inf' else 11.0
                break
    return gauges | {'metricCounters': counters, 'intervalRequests': count,
                     'interval5xxRatio': errors / count if count else 0,
                     'p95UpperSeconds': p95, 'intervalLoginAttempts': attempts,
                     'intervalLoginFailureRatio': failures / attempts if attempts else 0}


def metric_alerts(metrics):
    reasons = []
    if metrics['intervalRequests'] >= 20:
        if metrics['interval5xxRatio'] > 0.05:
            reasons.append('API_ERROR_RATE_HIGH')
        if metrics['p95UpperSeconds'] is not None and metrics['p95UpperSeconds'] > 2:
            reasons.append('API_LATENCY_HIGH')
    if metrics['dbWaiting'] > 0 and metrics['dbMax'] > 0 and metrics['dbActive'] >= metrics['dbMax']:
        reasons.append('DATABASE_POOL_SATURATED')
    if metrics['intervalLoginAttempts'] >= 10 and metrics['intervalLoginFailureRatio'] > 0.5:
        reasons.append('LOGIN_FAILURE_RATE_HIGH')
    return reasons


def assess(now, backup_at, restore_at, active, stale, overdue, stale_since):
    reasons = []
    if now - backup_at > 30 * 3600 or backup_at > now + 5:
        reasons.append('BACKUP_OVERDUE')
    if now - restore_at > 8 * 86400 or restore_at > now + 5:
        reasons.append('RESTORE_CHECK_OVERDUE')
    if active > 0 and stale == active and now - stale_since >= 180:
        reasons.append('ALL_ACTIVE_TELEMETRY_STALE')
    if overdue > 0:
        reasons.append('EXPIRATION_DELAY')
    return reasons


def check():
    now = int(time.time())
    reasons = []
    details = {}
    try:
        backup_at = latest_backup().stat().st_mtime
        restored = json.loads((STATE / 'restore.json').read_text())
        restore_at = restored['checkedAt'] if restored.get('ok') is True else 0
        if (STATE / 'restore-failure.json').exists():
            reasons.append('RESTORE_CHECK_FAILED')
        slot = pathlib.Path('/etc/majung/active-slot').read_text().strip()
        if slot not in ('blue', 'green'):
            raise RuntimeError('Unknown active slot')
        with urllib.request.urlopen('http://127.0.0.1:' + ('8081' if slot == 'blue' else '8082') + '/api/v1/health', timeout=5) as response:
            health = json.load(response)
        if health.get('status') != 'UP' or health.get('slot') != slot:
            reasons.append('API_OR_DATABASE_UNAVAILABLE')
        metrics = json.loads(sql('majung', """SELECT json_build_object(
            'active', count(*) FILTER (WHERE status='ACTIVE' AND ended_at IS NULL AND expires_at>now()),
            'stale', count(*) FILTER (WHERE status='ACTIVE' AND ended_at IS NULL AND expires_at>now()
                AND created_at<now()-interval '30 seconds'
                AND (vehicle_source_time IS NULL OR vehicle_received_at IS NULL OR LEAST(vehicle_source_time,vehicle_received_at)<now()-interval '30 seconds')),
            'overdue', count(*) FILTER (WHERE status NOT IN ('COMPLETED','CANCELLED','EXPIRED')
                AND (expires_at<now()-interval '3 minutes' OR status='ACTIVE' AND COALESCE(last_vehicle_activity_at,vehicle_received_at,created_at)<now()-interval '33 minutes'
                    OR status='ARRIVED' AND arrived_at<now()-interval '33 minutes')))
            FROM pickup"""))
        previous = json.loads((STATE / 'status.json').read_text()) if (STATE / 'status.json').exists() else {}
        stale_since = previous.get('staleSince', now) if metrics['active'] > 0 and metrics['stale'] == metrics['active'] else now
        with urllib.request.urlopen('http://127.0.0.1:' + ('9081' if slot == 'blue' else '9082') + '/actuator/prometheus', timeout=5) as response:
            previous_metrics = json.loads((STATE / 'performance.json').read_text()) if (STATE / 'performance.json').exists() else {}
            performance = summarize_metrics(response.read(2_000_000).decode(), previous_metrics)
        write_state('performance.json', performance)
        performance.pop('metricCounters')
        reasons += metric_alerts(performance)
        reasons += assess(now, backup_at, restore_at, metrics['active'], metrics['stale'], metrics['overdue'], stale_since)
        details = metrics | performance | {'staleSince': stale_since, 'backupAgeSeconds': int(now - backup_at), 'restoreAgeSeconds': now - restore_at}
    except Exception:
        reasons.append('HOST_CHECK_FAILED')
    result = {'checkedAt': now, 'ok': not reasons, 'reasons': reasons} | details
    write_state('status.json', result, public=True)
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['check', 'restore'])
    mode = parser.parse_args().mode
    if os.geteuid() != 0 or not socket.gethostname().startswith('majung-'):
        raise RuntimeError('Only run as root on a Majung host')
    STATE.mkdir(mode=0o750, exist_ok=True)
    os.chown(STATE, 0, grp.getgrnam('majung').gr_gid)
    # systemd UMask=0077 otherwise turns mkdir(0750) into 0700.
    os.chmod(STATE, 0o750)
    with (STATE / (mode + '.lock')).open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            (restore if mode == 'restore' else check)()
        except Exception:
            if mode == 'restore':
                write_state('restore-failure.json', {'ok': False, 'checkedAt': int(time.time())})
            raise


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(json.dumps({'ok': False, 'error': type(failure).__name__}))
        raise SystemExit(1)
