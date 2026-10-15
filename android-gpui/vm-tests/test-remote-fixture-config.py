#!/usr/bin/env python3
"""Fixture allowlist boundary tests. No SSH, emulator, or account access."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('fixture_config', Path(__file__).with_name('remote-fixture-config.py'))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)

class FixtureConfigTest(unittest.TestCase):
    def fixture(self):
        return dict(synthetic=True, transport='tcp', host='127.0.0.1', port=22222,
                    username='synthetic-fixture', password='public-synthetic-test-password',
                    session='android-test', expected_fingerprint='SHA256:' + 'A' * 43)

    def test_accepts_checked_in_fixture(self):
        self.assertEqual(config.fixture_values(self.fixture()), ('10.0.2.2', 22222, 'SHA256:' + 'A' * 43))

    def test_guest_route_requires_explicit_selection(self):
        self.assertEqual(config.fixture_values(self.fixture(), 'guest'), ('127.0.0.1', 22222, 'SHA256:' + 'A' * 43))
        self.assertEqual(config.fixture_values(self.fixture(), 'host')[0], '10.0.2.2')

    def test_refuses_unknown_routes(self):
        for route in ('', 'auto', '127.0.0.1', '10.0.2.2', 'Guest', None):
            with self.subTest(route=route):
                with self.assertRaises(ValueError): config.fixture_values(self.fixture(), route)

    def test_refuses_real_hosts_credentials_and_sessions(self):
        for key, value in [('host', 'example.com'), ('host', '10.0.2.2'), ('username', 'root'),
                           ('password', 'user-secret'), ('session', 'default'), ('transport', 'iroh'),
                           ('synthetic', False), ('synthetic', 1)]:
            with self.subTest(key=key, value=value):
                fixture = self.fixture(); fixture[key] = value
                for route in ('host', 'guest'):
                    with self.assertRaises(ValueError): config.fixture_values(fixture, route)

    def test_rejects_invalid_port_or_fingerprint(self):
        for key, value in [('port', 0), ('port', 65536), ('port', True), ('port', '22'),
                           ('expected_fingerprint', ''), ('expected_fingerprint', 'SHA256:wrong'),
                           ('expected_fingerprint', 'SHA256:' + 'A' * 43 + '\n')]:
            with self.subTest(key=key, value=value):
                fixture = self.fixture(); fixture[key] = value
                with self.assertRaises(ValueError): config.fixture_values(fixture)

    def test_rejects_missing_metadata(self):
        for value in [None, [], {}, {'synthetic': True}]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError): config.fixture_values(value)

if __name__ == '__main__':
    unittest.main()
