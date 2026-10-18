"""LLM executive summary for the diagnosis report.

Sends only finding titles/details to Groq (qwen/qwen3.8-27b) -- never
audio. Every failure mode (no key, no network, bad response) returns ""
so the report never depends on the LLM.
"""

import json
import re

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
MODEL = "qwen/qwen3.8-27b"

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


def build_prompt(findings, profile):
    """Render the user prompt for a findings list and profile id."""
    frame = PROFILE_FRAMING.get(profile, PROFILE_FRAMING["podcast"])
    lines = []
    for f in findings:
        tag = _SEV_TAG.get(f.get("severity", "info"), "NOTE")
        lines.append("- %s: %s -- %s"
                     % (tag, f.get("title", ""), f.get("detail", "")))
    return ("The user recorded %s. Here is the automated diagnosis:\n%s\n\n"
            "Summarize this for them in plain language."
            % (frame, "\n".join(lines)))


def _strip_thinking(text):
    return re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()


def build_request_body(findings_json, profile):
    """Build the Groq chat-completions request body JSON, or "" if the
    findings are unusable. The HTTP itself is done in Java
    (HttpsURLConnection uses Android's system trust store; Python's ssl
    under Chaquopy has unreliable CA verification)."""
    try:
        findings = json.loads(findings_json)
    except (TypeError, ValueError):
        return ""
    if not isinstance(findings, list) or not findings:
        return ""
    return json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user",
             "content": build_prompt(findings, profile)},
        ],
        "temperature": 0.7,
        "max_tokens": 150,
    })


def parse_response(response_json):
    """Extract the summary text from a Groq response body, or ""."""
    try:
        payload = json.loads(response_json)
        text = payload["choices"][0]["message"]["content"]
    except (TypeError, ValueError, KeyError, IndexError):
        return ""
    if not isinstance(text, str):
        return ""
    return _strip_thinking(text)
