#!/usr/bin/env python3
"""Accept only checked-in fixture metadata; never pass user SSH details to a VM."""
import argparse
import json
from pathlib import Path
import re
import sys

def fixture_values(value, route='host'):
    if route not in ('host', 'guest'):
        raise ValueError('Fixture route must be exactly host or guest')
    expected = {
        'synthetic': True,
        'transport': 'tcp',
        'host': '127.0.0.1',
        'username': 'synthetic-fixture',
        'password': 'public-synthetic-test-password',
        'session': 'android-test',
    }
    if not isinstance(value, dict) or value.get('synthetic') is not True or any(value.get(key) != item for key, item in expected.items()):
        raise ValueError('Only the checked-in loopback synthetic fixture metadata is accepted')
    port = value.get('port')
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('Fixture must name an explicit valid port')
    fingerprint = value.get('expected_fingerprint')
    if not isinstance(fingerprint, str) or not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}=?', fingerprint):
        raise ValueError('Fixture must contain its independently supplied SHA-256 fingerprint')
    # Metadata always describes the listener's bind address. Choosing guest is
    # explicit; a failed host route must never silently change the destination.
    return ('10.0.2.2' if route == 'host' else '127.0.0.1'), port, fingerprint

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--route', choices=('host', 'guest'), default='host')
    parser.add_argument('metadata')
    args = parser.parse_args()
    try:
        payload = Path(args.metadata).read_text()
        if len(payload) > 16384:
            raise ValueError('Oversized synthetic fixture metadata')
        for value in fixture_values(json.loads(payload), args.route):
            print(value)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error))
