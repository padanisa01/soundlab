# Audio Mechanic summary proxy (ticket AM-1)

Thin Cloud Run service that calls Groq for the diagnosis summary, so the
Groq API key no longer ships inside the APK. The app POSTs findings plus an
App Check token; the proxy verifies the token, enforces a per-installation
daily quota, forwards to Groq, and returns the plain-language summary.

No em dashes in this service: colons and commas only.

## Deployment layout (split projects, since 2026-10-20)

- Deploy project: `garednx` (has billing). Hosts Cloud Run, Secret
  Manager, and the dedicated service account `audio-mechanic-proxy`.
- Firebase project: `audiomech-a86dc` (Spark, no billing). Owns the App
  Check tokens and the Firestore quota database.
- The proxy reads one env var, `FIREBASE_PROJECT_ID`, for both App Check
  verification and Firestore, so the deploy script takes the two project
  IDs separately.

## Deploy steps (in this order)

1. Enable APIs and create the service account in the deploy project:

   ```bash
   gcloud services enable run.googleapis.com secretmanager.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com iam.googleapis.com --project garednx
   gcloud iam service-accounts create audio-mechanic-proxy --project garednx --display-name "Audio Mechanic summary proxy"
   ```

2. Create the Groq API key secret in the deploy project, using a NEW key
   from https://console.groq.com/keys (the old key passed through chat
   and is retired in step 6):

   ```bash
   read -s -p "New Groq key: " KEY; echo
   printf '%s' "$KEY" | gcloud secrets create groq-api-key --project garednx --data-file=-
   unset KEY
   gcloud secrets add-iam-policy-binding groq-api-key --project garednx --member "serviceAccount:audio-mechanic-proxy@garednx.iam.gserviceaccount.com" --role "roles/secretmanager.secretAccessor"
   ```

   The key is never committed to the repo and never pasted in chat.

3. Grant the service account Firestore access in the Firebase project:

   ```bash
   gcloud projects add-iam-policy-binding audiomech-a86dc --member "serviceAccount:audio-mechanic-proxy@garednx.iam.gserviceaccount.com" --role "roles/datastore.user"
   ```

4. Firebase console, https://console.firebase.google.com (project
   audiomech): register the Android app `com.audiofx.app` if needed and
   enable App Check with the Play Integrity provider. Firestore and the
   app registration are already done.

5. Deploy the service:

   ```bash
   ./deploy.sh garednx audiomech-a86dc
   ```

   `--project` is always passed explicitly; the script refuses to run
   without it. The service deploys with `--allow-unauthenticated` because
   access is gated by App Check verification in the app, not by IAM.

6. Point the app at the proxy: set `summary.proxy.url` in the app's
   `local.properties` to the bare Cloud Run URL printed by the deploy
   (no path -- the app appends the `/summarize` route itself in
   `summaryProxyUrl()`), then build a release APK. Verify end to
   end, then revoke the old Groq key in the Groq console.

## User todos

- [x] Firestore enabled in audiomech (done 2026-10-20).
- [x] Android app registered in audiomech (done).
- [ ] Enable APIs and create the service account in garednx (step 1).
- [ ] Create the `groq-api-key` secret with a new key (step 2).
- [ ] Firestore IAM grant in audiomech (step 3).
- [ ] App Check with Play Integrity in the Firebase console (step 4).
- [ ] Deploy, point the app at the URL, verify, revoke the old Groq key
      (steps 5 and 6).
- [ ] Real AdMob IDs (separate ticket).

## Endpoints

- `POST /summarize` with JSON `{"findings_json": str, "profile": str,
  "app_check_token": str}`. Returns `{"summary": text}`. Status codes:
  400 (bad findings), 401 (App Check verification failed), 429 (daily
  quota exceeded, 30 requests/day per installation), 502 (upstream failed).
- `GET /healthz` returns `{"ok": true}` for Cloud Run health checks.

The key is never logged: only Groq HTTP status codes are logged, never
request or response bodies.
