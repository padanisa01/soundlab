"""Cloud Run proxy for the Audio Mechanic diagnosis summary (ticket AM-1).

The Groq API key lives here, server-side, behind Firebase App Check, so it
never ships inside the APK. The app POSTs its findings to /summarize and
gets back plain-language text, nothing else.

Request and response handling is a port of
app/src/main/python/audiofx/summarize.py (build_request_body,
parse_response): same model, same system prompt, same <think> stripping.
"""

import json
import logging
import os
import re
from datetime import datetime, timezone

import requests
from firebase_admin import app_check, credentials, firestore, initialize_app
from flask import Flask, jsonify, request

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("summary-proxy")

app = Flask(__name__)

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
MODEL = "qwen/qwen3.8-27b"

# Same system prompt as summarize.py in the app.
SYSTEM = (
    "You are a friendly audio mechanic explaining a diagnosis report to a "
    "non-technical user. Use plain everyday language with no jargon: never "
    "say LUFS, dBFS, sibilance, plosive, transient, notch, Hertz, or RMS. "
    "Write 2-3 sentences, under 60 words. Start with the overall state of "
    "the recording, name the main issues in plain words, end with "
    "reassurance that the tune-up can fix them. No markdown, no lists."
)

PROFILE_FRAMING = {
    "podcast": "a podcast episode (spoken voice)",
    "singing": "a vocal singing performance",
    "music": "a music recording",
}

_SEV_TAG = {"warn": "ISSUE", "info": "NOTE", "ok": "OK"}

DAILY_QUOTA = 30


def _init_firebase():
    """Initialize firebase_admin once, using ambient Cloud Run credentials."""
    project_id = os.environ.get("FIREBASE_PROJECT_ID")
    if not project_id:
        raise RuntimeError("FIREBASE_PROJECT_ID env var is not set")
    initialize_app(credentials.ApplicationDefault(), {"projectId": project_id})
    return firestore.client()


db = _init_firebase()


def _strip_thinking(text):
    return re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()


# Quality metrics the app may attach to a summary request, as
# (payload key, display name, one-phrase meaning). Metadata only --
# the same diagnosis the findings already describe, never audio.
_QUALITY_METRICS = (
    ("signal", "Signal", "how clean the voice itself sounds"),
    ("background", "Background", "how quiet the background noise is"),
    ("overall", "Overall", "Signal and Background combined"),
)

# Below this estimate the weakest quality dimension is worth a
# clause of the summary.
_QUALITY_WEAK_THRESHOLD = 3.5


def _quality_prompt_lines(quality):
    """Plain prompt lines for a payload quality object, or [] when
    the object is absent or malformed (the prompt then stays exactly
    as it was before quality was carried)."""
    if not isinstance(quality, dict):
        return []
    values = {}
    for key, _name, _meaning in _QUALITY_METRICS:
        v = quality.get(key)
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            return []
        values[key] = float(v)
    lines = ["Perceptual quality estimate, out of 5:"]
    for key, name, meaning in _QUALITY_METRICS:
        lines.append("- %s %.1f: %s." % (name, values[key], meaning))
    weakest_key = min(values, key=lambda k: values[k])
    if values[weakest_key] < _QUALITY_WEAK_THRESHOLD:
        weakest_name = dict(
            (k, n) for k, n, _m in _QUALITY_METRICS)[weakest_key]
        lines.append(
            "The weakest dimension is %s; mention %s in one clause."
            % (weakest_name, weakest_name))
    return lines


def _build_prompt(findings, profile, quality=None):
    frame = PROFILE_FRAMING.get(profile, PROFILE_FRAMING["podcast"])
    lines = []
    for f in findings:
        tag = _SEV_TAG.get(f.get("severity", "info"), "NOTE")
        lines.append("- %s: %s -- %s"
                     % (tag, f.get("title", ""), f.get("detail", "")))
    prompt = ("The user recorded %s. Here is the automated diagnosis:\n%s\n\n"
              "Summarize this for them in plain language."
              % (frame, "\n".join(lines)))
    quality_lines = _quality_prompt_lines(quality)
    if quality_lines:
        prompt += "\n\n" + "\n".join(quality_lines)
    return prompt


def _build_request_body(findings_json, profile, quality=None):
    """Build the Groq request body, mirroring summarize.build_request_body."""
    try:
        findings = json.loads(findings_json)
    except (TypeError, ValueError):
        return ""
    if not isinstance(findings, list) or not findings:
        return ""
    return {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user",
             "content": _build_prompt(findings, profile, quality)},
        ],
        "temperature": 0.7,
        "max_tokens": 150,
    }


def _parse_response(response_json):
    """Extract the summary text, mirroring summarize.parse_response."""
    try:
        payload = json.loads(response_json)
        text = payload["choices"][0]["message"]["content"]
    except (TypeError, ValueError, KeyError, IndexError):
        return ""
    if not isinstance(text, str):
        return ""
    return _strip_thinking(text)


def _verify_app_check(token):
    """Verify the App Check JWT; return the decoded claims or None."""
    try:
        # firebase_admin's verify_token takes (token, app=None) --
        # there is NO project_id kwarg in any released version
        # (checked 6.5.0 and 7.7.0 source). Passing one raised
        # TypeError, which the except below swallowed, so EVERY
        # token -- valid ones included -- was rejected with 401
        # from the first deploy until 2026-10-28. The project comes
        # from the app initialized in _init_firebase. (The AM-1
        # smoke test could not catch this: a bogus token is
        # supposed to get a 401 either way.)
        return app_check.verify_token(token)
    except Exception as e:  # verification failure: wrong issuer, expired, etc.
        # Log the reason, never the token: PyJWT messages name the
        # expected audience/issuer, which is what a real claims
        # mismatch diagnosis needs.
        logger.warning("app check verification failed: %s: %s",
                       type(e).__name__, str(e)[:200])
        return None


def _check_and_consume_quota(installation_id):
    """Atomically consume one daily quota slot; True if allowed."""
    day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    doc_ref = db.collection("summary_quota").document(
        "%s_%s" % (installation_id, day))

    @firestore.transactional
    def _consume(transaction):
        snap = doc_ref.get(transaction=transaction)
        count = snap.get("count") if snap.exists else 0
        if not isinstance(count, int):
            count = 0
        if count >= DAILY_QUOTA:
            return False
        transaction.set(doc_ref, {"count": count + 1}, merge=True)
        return True

    return _consume(db.transaction())


@app.get("/healthz")
def healthz():
    return jsonify({"ok": True})


@app.post("/summarize")
def summarize():
    data = request.get_json(silent=True) or {}
    findings_json = data.get("findings_json")
    profile = data.get("profile", "podcast")
    quality = data.get("quality")
    app_check_token = data.get("app_check_token")

    if not app_check_token:
        return jsonify({"error": "app check failed"}), 401
    claims = _verify_app_check(app_check_token)
    if not claims:
        # Never log the token itself.
        return jsonify({"error": "app check failed"}), 401
    installation_id = claims.get("sub")
    if not installation_id:
        return jsonify({"error": "app check failed"}), 401

    try:
        if not _check_and_consume_quota(installation_id):
            return jsonify({"error": "daily quota exceeded"}), 429
    except Exception as e:
        # Message included: Firestore errors name the project,
        # database and missing permission -- exactly what a quota
        # failure diagnosis needs. No user data or secrets in them.
        logger.error("quota check failed: %s: %s",
                     type(e).__name__, str(e)[:300])
        return jsonify({"error": "upstream failed"}), 502

    groq_body = _build_request_body(findings_json, profile, quality)
    if not groq_body:
        return jsonify({"error": "bad findings"}), 400

    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        logger.error("GROQ_API_KEY env var is not set")
        return jsonify({"error": "upstream failed"}), 502

    try:
        resp = requests.post(
            GROQ_URL,
            json=groq_body,
            headers={"Authorization": "Bearer %s" % api_key},
            timeout=60,
        )
    except requests.RequestException as e:
        # Type + message: requests exceptions never contain the
        # Authorization header, so the key cannot reach the logs.
        logger.error("groq request failed: %s: %s",
                     type(e).__name__, str(e)[:300])
        return jsonify({"error": "upstream failed"}), 502

    # Log HTTP status only, never bodies or the key.
    logger.info("groq responded with status %s", resp.status_code)
    if not resp.ok:
        return jsonify({"error": "upstream failed"}), 502

    summary = _parse_response(resp.text)
    if not summary:
        return jsonify({"error": "upstream failed"}), 502
    return jsonify({"summary": summary})
