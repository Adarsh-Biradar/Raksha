#!/usr/bin/env bash
set -euo pipefail
context="${1:?Usage: bash k8s/setup-payments.sh YOUR_CONTEXT}"
kubectl --context "$context" -n raksha get deployment raksha-api -o name
read -rp "Razorpay TEST key ID (rzp_test_...): " key_id
[[ "$key_id" == rzp_test_* ]] || { echo 'Only test keys are supported.'; exit 1; }
read -rsp "Razorpay TEST key secret: " key_secret; echo
read -rsp "Webhook secret (choose at least 16 characters): " webhook_secret; echo
[[ -n "$key_secret" && ${#webhook_secret} -ge 16 ]] || { echo 'Both secrets are required.'; exit 1; }
printf 'RAZORPAY_KEY_ID=%s\nRAZORPAY_KEY_SECRET=%s\nRAZORPAY_WEBHOOK_SECRET=%s\n' "$key_id" "$key_secret" "$webhook_secret" |
  kubectl --context "$context" -n raksha create secret generic raksha-payments --from-env-file=/dev/stdin --dry-run=client -o yaml |
  kubectl --context "$context" apply --server-side --field-manager=raksha-payment-config -f -
unset key_id key_secret webhook_secret
kubectl --context "$context" -n raksha rollout restart deployment/raksha-api
kubectl --context "$context" -n raksha rollout status deployment/raksha-api
