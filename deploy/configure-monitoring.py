#!/usr/bin/env python3
"""Create Majung-only uptime checks and an email alert; leave other policies untouched."""
import argparse
import json
import subprocess
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', required=True)
    parser.add_argument('--email', required=True)
    parser.add_argument('--gcloud', default='gcloud')
    args = parser.parse_args()
    # Do not enable an alert before the matching application/host check is installed.
    for path in ['/api/v1/health', '/api/v1/health/operations']:
        with urllib.request.urlopen('https://majung.cloud' + path, timeout=10) as response:
            if json.load(response).get('status') != 'UP':
                raise RuntimeError('Deploy and verify Majung health checks first')
    token = subprocess.run([args.gcloud, 'auth', 'print-access-token'], check=True,
                           capture_output=True, text=True).stdout.strip()
    base = 'https://monitoring.googleapis.com/v3/projects/' + args.project + '/'

    def api(path, value=None):
        request = urllib.request.Request(base + path,
            data=json.dumps(value).encode() if value is not None else None,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def listing(kind):
        result, page = [], ''
        while True:
            response = api(kind + ('?pageToken=' + page if page else ''))
            result += response.get(kind, [])
            page = response.get('nextPageToken', '')
            if not page: return result

    channels = listing('notificationChannels')
    channel = next((c for c in channels if c.get('displayName') == 'Majung operations email'
                    and c.get('type') == 'email' and c.get('labels', {}).get('email_address') == args.email), None)
    if channel is None:
        channel = api('notificationChannels', {'type': 'email', 'displayName': 'Majung operations email',
            'labels': {'email_address': args.email}, 'enabled': True})
    checks, conditions = listing('uptimeCheckConfigs'), []
    for title, path in [('Majung API availability', '/api/v1/health'),
                        ('Majung operations availability', '/api/v1/health/operations')]:
        check = next((c for c in checks if c.get('displayName') == title), None)
        if check is None:
            check = api('uptimeCheckConfigs', {'displayName': title,
                'monitoredResource': {'type': 'uptime_url', 'labels': {'project_id': args.project, 'host': 'majung.cloud'}},
                'httpCheck': {'path': path, 'port': 443, 'useSsl': True, 'validateSsl': True, 'requestMethod': 'GET'},
                'period': '60s', 'timeout': '10s',
                'contentMatchers': [{'content': '"status":"UP"', 'matcher': 'CONTAINS_STRING'}]})
        if check['monitoredResource']['labels'].get('host') != 'majung.cloud' or check['httpCheck']['path'] != path:
            raise RuntimeError('Existing Majung check has a different target; inspect it first')
        check_id = check['name'].rsplit('/', 1)[1]
        conditions.append({'displayName': title + ' failed in two locations', 'conditionThreshold': {
            'filter': 'metric.type="monitoring.googleapis.com/uptime_check/check_passed" AND metric.label.check_id="'
                      + check_id + '" AND resource.type="uptime_url"',
            'aggregations': [{'alignmentPeriod': '60s', 'perSeriesAligner': 'ALIGN_NEXT_OLDER',
                              'crossSeriesReducer': 'REDUCE_COUNT_FALSE', 'groupByFields': ['resource.label.*']}],
            'comparison': 'COMPARISON_GT', 'thresholdValue': 1, 'duration': '120s', 'trigger': {'count': 1}}})
    policies = listing('alertPolicies')
    policy = next((p for p in policies if p.get('displayName') == 'Majung service and recovery checks'), None)
    if policy is None:
        policy = api('alertPolicies', {'displayName': 'Majung service and recovery checks', 'combiner': 'OR',
            'enabled': True, 'conditions': conditions, 'notificationChannels': [channel['name']],
            'alertStrategy': {'autoClose': '1800s'},
            'documentation': {'mimeType': 'text/markdown', 'content':
                '마중 운영 점검 실패. 서버 health와 /api/v1/health/operations를 확인하세요. '
                '마중 VM에서 sudo /usr/local/sbin/majung-operations check 실행 후 '
                '/var/lib/majung-operations/status.json의 reasons를 확인하세요. '
                '백업·복원 실패 시 원인을 확인하고 재시험하세요. 운영 DB를 덮어쓰지 마세요.'}})
    print(json.dumps({'policy': policy['name'], 'checks': [c['displayName'] for c in conditions],
                      'channel': channel['name'], 'emailDeliveryTested': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
