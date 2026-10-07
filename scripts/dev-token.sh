#!/usr/bin/env bash
# Mints a short-lived HS256 JWT for LOCAL testing only.
# Usage: JWT_SECRET=... ./scripts/dev-token.sh "payments.read payments.write"
set -euo pipefail

: "${JWT_SECRET:?Set JWT_SECRET (same value the service uses)}"
SCOPE="${1:-payments.read payments.write}"
NOW=$(date +%s)
EXP=$((NOW + 3600))

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"sub":"local-dev","scope":"%s","iat":%d,"exp":%d}' "$SCOPE" "$NOW" "$EXP" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)

printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIGNATURE"
