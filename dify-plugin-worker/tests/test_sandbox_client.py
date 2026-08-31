"""验证控制 Worker 只通过来源专用 Unix Socket 调用 Docker 沙箱。"""

import json
import unittest
from unittest.mock import MagicMock, patch

from app.sandbox_client import SandboxClient, SandboxError


class SandboxClientTest(unittest.TestCase):
    """覆盖成功转发、有限错误和固定本地传输。"""

    @patch("app.sandbox_client.UnixHTTPConnection")
    def test_uses_fixed_unix_socket_and_limited_json(self, connection_type: MagicMock) -> None:
        """调用方只能选择类型化操作，不能提供网络地址或 Docker 参数。"""
        response = MagicMock(status=200)
        response.read.return_value = json.dumps({"success": True, "output": {"ok": True}}).encode()
        connection_type.return_value.getresponse.return_value = response
        client = SandboxClient("/run/dify-sandbox/broker.sock")

        result = client.request("invoke", {"fingerprint": "a" * 64, "componentId": "action"})

        self.assertTrue(result["success"])
        connection_type.assert_called_once_with("/run/dify-sandbox/broker.sock", client.timeout)
        request = connection_type.return_value.request.call_args
        self.assertEqual(("POST", "/sandbox/invoke"), request.args[:2])
        self.assertNotIn(b"PLUGIN_WORKER_INTERNAL_TOKEN", request.args[2])

    def test_rejects_unknown_operation_before_connecting(self) -> None:
        """任意 Docker 风格动作必须在建立 Broker 连接前拒绝。"""
        with self.assertRaisesRegex(SandboxError, "PLUGIN_SANDBOX_OPERATION_INVALID"):
            SandboxClient("/run/dify-sandbox/broker.sock").request("run", {"image": "evil"})


if __name__ == "__main__":
    unittest.main()
