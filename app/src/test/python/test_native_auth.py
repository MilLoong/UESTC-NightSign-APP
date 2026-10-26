import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[2] / 'main' / 'python'))
import native_auth


class Callback:
    def cancelled(self):
        return False

    def progress(self, message):
        pass

    def diagnostic(self, message):
        pass

    def credentials(self, value):
        self.saved = json.loads(value)


class LocalAuthTest(unittest.TestCase):
    def test_crypto_and_protocol_imports(self):
        self.assertTrue(native_auth.self_test())

    def test_protocol_poll_status(self):
        self.assertEqual(native_auth.poll_result(b'window.wx_errcode=408;'), (408, None))
        with self.assertRaises(RuntimeError):
            native_auth.poll_result(b'not a valid response')

    def test_saved_authorization_returns_code_without_attendance(self):
        credentials = {'school_user_id': 1, 'expires_at': 9999999999}
        callback = Callback()
        with patch.object(native_auth.Client, 'school', return_value={'userData': {'id': 1}}) as school, \
             patch.object(native_auth, 'request_code', return_value='c' * 32):
            result = native_auth.acquire('own-session', json.dumps(credentials), callback, False)
        self.assertEqual(json.loads(result), {'code': 'c' * 32})
        self.assertEqual(school.call_count, 1)
        self.assertEqual(school.call_args.args[0], 'GET')

    def test_different_school_account_requires_new_authorization(self):
        with patch.object(native_auth.Client, 'school', return_value={'userData': {'id': 2}}):
            with self.assertRaisesRegex(RuntimeError, 'authorization_required'):
                native_auth.acquire('own-session', json.dumps({'school_user_id': 1}), Callback(), False)

    def test_login_buffer_code_is_bound_to_target_app(self):
        with patch.object(native_auth, 'login_buffer', return_value='buffer'), \
             patch.object(native_auth, 'get_code', return_value='c' * 32) as get_code:
            native_auth.request_code(None, {}, Callback())
        self.assertEqual(get_code.call_args.args, ('buffer', 'wxaa78bf2905f01cee'))

    def test_school_session_cannot_reach_wechat_host(self):
        self.assertNotIn(('POST', '/daily-checkin/api/student-checkin-record/checkin'), native_auth.ALLOWED_SCHOOL)
        with self.assertRaisesRegex(RuntimeError, 'request_blocked'):
            native_auth.Client().request('GET', 'https://untrusted.example/')


if __name__ == '__main__':
    unittest.main()
