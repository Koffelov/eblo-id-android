"""Evaluate JavaScript in the debug build's WebView over the DevTools protocol.

  cdp.py [--gesture] EXPRESSION   -> prints the JSON value of EXPRESSION
  cdp.py --call METHOD PARAMS     -> prints the JSON result of a raw DevTools METHOD

Expects `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` to be set up.
Only works with the debug APK (WebView debugging is disabled in release builds).
"""
import json
import sys
import time
import urllib.request

import websocket


def target():
    for _ in range(30):
        try:
            with urllib.request.urlopen("http://127.0.0.1:9222/json/list", timeout=5) as r:
                pages = [p for p in json.load(r) if p.get("type") == "page"]
            if pages:
                return pages[0]["webSocketDebuggerUrl"]
        except OSError:
            pass
        time.sleep(1)
    raise SystemExit("no DevTools target")


def call(method, params):
    ws = websocket.create_connection(target(), timeout=60, suppress_origin=True)
    try:
        ws.send(json.dumps({"id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(ws.recv())
            if msg.get("id") == 1:
                return msg
    finally:
        ws.close()


def evaluate(expression, gesture=False):
    msg = call("Runtime.evaluate", {"expression": expression, "returnByValue": True,
                                    "awaitPromise": True, "userGesture": gesture})
    result = msg.get("result", {})
    if "exceptionDetails" in result:
        raise SystemExit("JS error: " + json.dumps(result["exceptionDetails"])[:500])
    return result.get("result", {}).get("value")


if __name__ == "__main__":
    if sys.argv[1] == "--call":
        print(json.dumps(call(sys.argv[2], json.loads(sys.argv[3])).get("result"), ensure_ascii=False))
        sys.exit(0)
    args = [a for a in sys.argv[1:] if a != "--gesture"]
    print(json.dumps(evaluate(args[0], "--gesture" in sys.argv[1:]), ensure_ascii=False))
