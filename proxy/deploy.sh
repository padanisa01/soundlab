#!/usr/bin/env bash
# Deploy the Audio Mechanic summary proxy to Cloud Run.
# Usage: ./deploy.sh YOUR_PROJECT_ID
set -euo pipefail

PROJECT_ID="${1:-}"
if [ -z "$PROJECT_ID" ]; then
  echo "Usage: ./deploy.sh YOUR_PROJECT_ID" >&2
  echo "Example: ./deploy.sh my-firebase-project" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

gcloud run deploy audio-mechanic-summary \
  --project "$PROJECT_ID" \
  --region us-central1 \
  --source "$SCRIPT_DIR" \
  --allow-unauthenticated \
  --set-secrets GROQ_API_KEY=groq-api-key:latest \
  --set-env-vars FIREBASE_PROJECT_ID="$PROJECT_ID"
