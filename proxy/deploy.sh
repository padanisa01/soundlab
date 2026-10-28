#!/usr/bin/env bash
# Deploy the Audio Mechanic summary proxy to Cloud Run.
# Usage: ./deploy.sh DEPLOY_PROJECT_ID [FIREBASE_PROJECT_ID]
#
# The two projects may differ: the service runs in a project that has
# billing (e.g. garednx) while the App Check tokens are issued by the
# Firebase project (e.g. audiomech-a86dc). FIREBASE_PROJECT_ID is used
# ONLY to verify those tokens (local JWKS signature checking; no IAM
# grant needed there) and defaults to the deploy project when omitted.
# The Firestore quota database lives in the DEPLOY project
# (QUOTA_PROJECT_ID below), beside the service and its secret --
# rearchitected 2026-10-28 so no cross-project IAM grant is needed.
set -euo pipefail

PROJECT_ID="${1:-}"
FIREBASE_PROJECT_ID="${2:-${1:-}}"
if [ -z "$PROJECT_ID" ]; then
  echo "Usage: ./deploy.sh DEPLOY_PROJECT_ID [FIREBASE_PROJECT_ID]" >&2
  echo "Example: ./deploy.sh garednx audiomech-a86dc" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SA_EMAIL="audio-mechanic-proxy@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud run deploy audio-mechanic-summary \
  --project "$PROJECT_ID" \
  --region us-central1 \
  --source "$SCRIPT_DIR" \
  --allow-unauthenticated \
  --service-account "$SA_EMAIL" \
  --set-secrets GROQ_API_KEY=groq-api-key:latest \
  --set-env-vars FIREBASE_PROJECT_ID="$FIREBASE_PROJECT_ID",QUOTA_PROJECT_ID="$PROJECT_ID"
