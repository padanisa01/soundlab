# Audio Mechanic summary proxy (ticket AM-1)

Thin Cloud Run service that calls Groq for the diagnosis summary, so the
Groq API key no longer ships inside the APK. The app POSTs findings plus an
App Check token; the proxy verifies the token, enforces a per-installation
daily quota, forwards to Groq, and returns the plain-language summary.

No em dashes in this service: colons and commas only.

## Deploy steps (in this order)

1. Create the Groq API key secret in Secret Manager (do this first):

   ```bash
   printf '%s' "$KEY" | gcloud secrets create groq-api-key --project YOUR_PROJECT_ID --data-file=-
   ```

   The key is never committed to the repo and never pasted in chat. `$KEY`
   is a shell variable holding the key from the Groq console.

2. Firebase console, https://console.firebase.google.com:
   - Create or select the Firebase project.
   - Register the Android app with package name `com.audiofx.app`.
   - Enable App Check with the Play Integrity provider.
   - Enable Firestore (the proxy uses it for the daily quota).

3. Deploy the service:

   ```bash
   ./deploy.sh YOUR_PROJECT_ID
   ```

   `--project` is always passed explicitly; the script refuses to run
   without it. The service deploys with `--allow-unauthenticated` because
   access is gated by App Check verification in the app, not by IAM.

4. Point the app at the proxy: set `summary.proxy.url` in the app's
   `local.properties` to the Cloud Run URL printed by the deploy, then
   build a release APK.

5. Rotate the Groq key: in the Groq console, https://console.groq.com/keys,
   create a new key, update the `groq-api-key` secret to the new value, and
   revoke the old one, since the old key passed through chat. Deleting or
   disabling the old key is enough; no app rebuild is needed because the
   app no longer carries the key.

## User todos

- [ ] Create the `groq-api-key` secret (step 1 above).
- [ ] Firebase console setup: project, Android app registration, App Check
      with Play Integrity, Firestore enabled (step 2 above).
- [ ] Rotate the Groq key and revoke the old chat-exposed one (step 5).
- [ ] Real AdMob IDs (separate ticket).

## Endpoints

- `POST /summarize` with JSON `{"findings_json": str, "profile": str,
  "app_check_token": str}`. Returns `{"summary": text}`. Status codes:
  400 (bad findings), 401 (App Check verification failed), 429 (daily
  quota exceeded, 30 requests/day per installation), 502 (upstream failed).
- `GET /healthz` returns `{"ok": true}` for Cloud Run health checks.

The key is never logged: only Groq HTTP status codes are logged, never
request or response bodies.
