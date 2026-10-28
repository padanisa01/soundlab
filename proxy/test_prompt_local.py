#!/usr/bin/env python3
"""Ad-hoc local test of the summary proxy's prompt + Groq call.

Runs the proxy's OWN code (proxy/main.py: _build_request_body,
_parse_response, SYSTEM, MODEL) against a real findings payload
produced by the app's own diagnose_file on a synthesized problem
take (clipped + hissy). App Check and the Firestore quota are the
only steps skipped -- everything the prompt decides is identical
to production.

Usage:
  python3 proxy/test_prompt_local.py            # prompt only
  GROQ_API_KEY=... python3 proxy/test_prompt_local.py
                                                # + timed live call

Get the key from Secret Manager (never paste it in chat):
  GROQ_API_KEY=$(gcloud secrets versions access latest \
      --secret=groq-api-key --project garednx) \
      python3 proxy/test_prompt_local.py
"""

import importlib.util
import json
import math
import os
import struct
import sys
import time
import types
import urllib.request
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def _stub_modules():
    """Stub firebase_admin + flask so proxy/main.py imports without
    credentials; only its pure prompt/parse functions are used."""
    fa = types.ModuleType("firebase_admin")
    fa.initialize_app = lambda *a, **k: None
    creds = types.SimpleNamespace(ApplicationDefault=lambda: None)
    fa.credentials = creds
    fs = types.SimpleNamespace(
        client=lambda *a, **k: types.SimpleNamespace(
            collection=lambda *a, **k: None,
            transaction=lambda: None),
        transactional=lambda fn: fn)
    fa.firestore = fs
    fa.app_check = types.SimpleNamespace(
        verify_token=lambda *a, **k: None)
    sys.modules["firebase_admin"] = fa

    flask = types.ModuleType("flask")

    class _Flask:
        def __init__(self, name):
            self.name = name

        def get(self, *a, **k):
            return lambda fn: fn

        def post(self, *a, **k):
            return lambda fn: fn

    flask.Flask = _Flask
    flask.jsonify = lambda *a, **k: a[0] if a else k
    flask.request = types.SimpleNamespace(get_json=lambda silent=True: {})
    sys.modules["flask"] = flask


def _load_proxy_main():
    _stub_modules()
    os.environ.setdefault("FIREBASE_PROJECT_ID", "audiomech-a86dc")
    spec = importlib.util.spec_from_file_location(
        "proxy_main", os.path.join(HERE, "main.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _make_findings():
    """Real findings + quality from the app's own diagnose_file on
    a synthesized clipped, hissy 6 s take (deterministic)."""
    sys.path.insert(0, os.path.join(REPO, "app", "src", "main",
                                    "python"))
    from audiofx import android_bridge
    sr = 44100
    frames = []
    for i in range(sr * 6):
        t = i / sr
        v = 0.9 * math.sin(2 * math.pi * 220 * t)
        v = max(-1.0, min(1.0, v * 1.4))
        v += ((i * 2654435761) % 1000) / 1000.0 * 0.02 - 0.01
        s = int(max(-32768, min(32767, v * 32767)))
        frames.append(struct.pack("<hh", s, s))
    path = "/tmp/test_prompt_take.wav"
    w = wave.open(path, "wb")
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(sr)
    w.writeframes(b"".join(frames))
    w.close()
    d = json.loads(android_bridge.diagnose_file(path, "proxy"))
    findings = d["findings"] if "findings" in d else d
    q = d.get("quality") or {}
    # The app sends the proxy full-word keys (postProxySummary).
    quality = {"signal": q.get("sig"), "background": q.get("bak"),
               "overall": q.get("ovrl")}
    return json.dumps(findings), quality


def main():
    proxy = _load_proxy_main()
    findings_json, quality = _make_findings()
    body = proxy._build_request_body(findings_json, "singing", quality)
    if not body:
        print("ERROR: proxy built an empty request body")
        return 1
    print("=== SYSTEM ===")
    print(body["messages"][0]["content"])
    print("=== USER ===")
    print(body["messages"][1]["content"])
    print("=== REQUEST ===")
    print("model=%s temperature=%s max_tokens=%s"
          % (body["model"], body["temperature"], body["max_tokens"]))

    key = os.environ.get("GROQ_API_KEY", "").strip()
    if not key:
        print("=== LIVE CALL SKIPPED ===")
        print("GROQ_API_KEY is not set in this shell. To run the "
              "live leg:\n  GROQ_API_KEY=$(gcloud secrets versions "
              "access latest --secret=groq-api-key"
              " --project garednx) python3 proxy/test_prompt_local.py")
        return 0

    req = urllib.request.Request(
        proxy.GROQ_URL,
        data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer %s" % key,
                 "Content-Type": "application/json"},
        method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            status = resp.status
    except Exception as e:
        print("=== LIVE CALL FAILED after %.1f s: %s ==="
              % (time.time() - t0, e))
        return 1
    elapsed = time.time() - t0
    summary = proxy._parse_response(raw)
    print("=== LIVE CALL: HTTP %s in %.1f s ===" % (status, elapsed))
    print("--- summary (%d words) ---" % len(summary.split()))
    print(summary if summary else "(empty after parsing!)")
    return 0 if summary else 1


if __name__ == "__main__":
    sys.exit(main())
