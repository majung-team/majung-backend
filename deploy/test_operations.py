import importlib.util
import pathlib
import os
import types
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('operations', pathlib.Path(__file__).with_name('operations.py'))
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


class OperationsTest(unittest.TestCase):
    def test_idle_pickups_do_not_report_a_car_outage(self):
        self.assertEqual([], ops.assess(1000, 900, 900, 0, 0, 0, 0))

    def test_all_active_cars_must_remain_stale_for_three_minutes(self):
        self.assertEqual([], ops.assess(1000, 900, 900, 2, 2, 0, 821))
        self.assertIn('ALL_ACTIVE_TELEMETRY_STALE', ops.assess(1000, 900, 900, 2, 2, 0, 820))
        self.assertEqual([], ops.assess(1000, 900, 900, 2, 1, 0, 0))

    def test_overdue_backup_restore_and_expiration_are_distinct(self):
        issues = ops.assess(900000, 1, 1, 0, 0, 1, 900000)
        self.assertEqual(['BACKUP_OVERDUE', 'RESTORE_CHECK_OVERDUE', 'EXPIRATION_DELAY'], issues)

    def test_sql_never_accepts_another_service_database_or_injection(self):
        with patch.object(ops, 'run') as command:
            for database in ['fleaflea', 'postgres', 'majung; DROP DATABASE majung', 'majung_restore_check_']:
                with self.assertRaises(ValueError): ops.sql(database, 'SELECT 1')
            command.assert_not_called()

    def test_restore_failure_cleans_only_the_generated_scratch_database(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = pathlib.Path(folder) / 'backup.dump'
            archive.write_bytes(b'fixture')
            commands = []
            def run(args, **kwargs):
                commands.append(args)
                if 'pg_restore' in args: raise RuntimeError('invalid archive')
            with patch.object(ops, 'latest_backup', return_value=archive), patch.object(ops, 'environment', return_value={}), patch.object(ops, 'run', side_effect=run):
                with self.assertRaises(RuntimeError): ops.restore()
            created = commands[0][-1]
            self.assertRegex(created, r'^majung_restore_check_[0-9a-f]{16}$')
            self.assertEqual(['runuser', '-u', 'postgres', '--', 'dropdb', '--force', created], commands[-1])
            self.assertNotIn('majung', commands[-1])

    def test_private_service_umask_keeps_state_readable_by_application_group(self):
        with tempfile.TemporaryDirectory() as folder:
            state = pathlib.Path(folder) / 'state'
            original = os.umask(0o077)
            try:
                with patch.object(ops, 'STATE', state), patch.object(ops.os, 'geteuid', return_value=0), \
                     patch.object(ops.os, 'chown'), patch.object(ops.grp, 'getgrnam', return_value=types.SimpleNamespace(gr_gid=1)), \
                     patch.object(ops.socket, 'gethostname', return_value='majung-test'), \
                     patch('sys.argv', ['operations', 'check']), patch.object(ops, 'check') as check:
                    ops.main()
                    check.assert_called_once()
                self.assertEqual(0o750, state.stat().st_mode & 0o777)
            finally:
                os.umask(original)


class MetricsTest(unittest.TestCase):
    def test_interval_error_ratio_latency_and_reset(self):
        def sample(count, errors, bucket):
            return '\n'.join([
                'process_start_time_seconds 100',
                'hikaricp_connections_active{pool="main"} 10',
                'hikaricp_connections_max{pool="main"} 10',
                'hikaricp_connections_pending{pool="main"} 1',
                f'http_server_requests_seconds_count{{uri="/api/v1/pickups/{{id}}",status="200"}} {count}',
                f'http_server_requests_seconds_count{{uri="/api/v1/pickups/{{id}}",status="503"}} {errors}',
                f'http_server_requests_seconds_bucket{{uri="/api/v1/pickups/{{id}}",le="3.0"}} {bucket}',
                'http_server_requests_seconds_count{uri="/api/v1/health",status="200"} 10000'])
        first = ops.summarize_metrics(sample(100, 0, 100), {})
        self.assertEqual(0, first['intervalRequests'])
        second = ops.summarize_metrics(sample(120, 2, 122), first)
        self.assertEqual(22, second['intervalRequests'])
        self.assertAlmostEqual(2 / 22, second['interval5xxRatio'])
        self.assertEqual(3, second['p95UpperSeconds'])
        self.assertEqual(['API_ERROR_RATE_HIGH', 'API_LATENCY_HIGH', 'DATABASE_POOL_SATURATED'], ops.metric_alerts(second))
        reset = ops.summarize_metrics(sample(1, 0, 1).replace('seconds 100', 'seconds 200'), second)
        self.assertEqual(0, reset['intervalRequests'])
