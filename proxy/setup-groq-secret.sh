#!/usr/bin/env bash
# Create (or update) the groq-api-key secret in the deploy project and
# grant the summary proxy's service account access to it.
#
# Usage: ./setup-groq-secret.sh
# Prompts for the NEW Groq API key with hidden input: the key is never
# echoed, never written to shell history, and never stored in a file.
set -euo pipefail

PROJECT_ID="garednx"
SECRET_ID="groq-api-key"
SA_EMAIL="audio-mechanic-proxy@${PROJECT_ID}.iam.gserviceaccount.com"

if ! gcloud iam service-accounts describe "$SA_EMAIL" \
      --project "$PROJECT_ID" >/dev/null 2>&1; then
  echo "Service account $SA_EMAIL does not exist yet. Create it first:" >&2
  echo "  gcloud iam service-accounts create audio-mechanic-proxy --project $PROJECT_ID" >&2
  exit 1
fi

read -s -p "Paste the NEW Groq API key (input hidden): " KEY
echo
if [ -z "$KEY" ]; then
  echo "No key entered, nothing done." >&2
  exit 1
fi

if gcloud secrets describe "$SECRET_ID" \
      --project "$PROJECT_ID" >/dev/null 2>&1; then
  printf '%s' "$KEY" | gcloud secrets versions add "$SECRET_ID" \
    --project "$PROJECT_ID" --data-file=-
  echo "Added a new version to the existing secret $SECRET_ID."
else
  printf '%s' "$KEY" | gcloud secrets create "$SECRET_ID" \
    --project "$PROJECT_ID" --data-file=-
  echo "Created secret $SECRET_ID."
fi
unset KEY

gcloud secrets add-iam-policy-binding "$SECRET_ID" \
  --project "$PROJECT_ID" \
  --member "serviceAccount:${SA_EMAIL}" \
  --role "roles/secretmanager.secretAccessor"

echo "Done. $SA_EMAIL can now read $SECRET_ID in $PROJECT_ID."
