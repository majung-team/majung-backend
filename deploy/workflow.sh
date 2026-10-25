#!/usr/bin/env bash
set -Eeuo pipefail
for key in GCP_PROJECT_ID GCP_ZONE GCP_INSTANCE GCP_INSTANCE_ID GCP_SSH_USER GCP_SSH_HOST_KEY DEPLOY_SHA; do
  [[ -n "${!key:-}" ]] || { echo "Missing configuration: $key" >&2; exit 1; }
done
[[ "$GCP_PROJECT_ID" =~ ^[a-z][a-z0-9-]+$ ]]
[[ "$GCP_ZONE" =~ ^[a-z0-9-]+$ ]]
[[ "$GCP_INSTANCE" =~ ^majung-[a-z0-9-]+$ ]]
[[ "$GCP_INSTANCE_ID" =~ ^[0-9]+$ ]]
[[ "$GCP_SSH_USER" =~ ^[a-z_][a-z0-9_-]+$ ]]
[[ "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$GITHUB_REF" == refs/heads/main && "$GITHUB_REPOSITORY" == majung-team/majung-backend ]]
[[ $# == 2 && "$1" == backend && -f "$2" ]]
ACTUAL_ID="$(gcloud compute instances describe "$GCP_INSTANCE" --project="$GCP_PROJECT_ID" --zone="$GCP_ZONE" --format='value(id)')"
[[ "$ACTUAL_ID" == "$GCP_INSTANCE_ID" ]] || { echo 'Deployment target does not match'; exit 1; }
mkdir -p "$HOME/.ssh"
chmod 700 "$HOME/.ssh"
printf 'compute.%s %s\n' "$GCP_INSTANCE_ID" "$GCP_SSH_HOST_KEY" > "$HOME/.ssh/google_compute_known_hosts"
chmod 600 "$HOME/.ssh/google_compute_known_hosts"
DIGEST="$(sha256sum "$2" | cut -d ' ' -f 1)"
[[ "$DIGEST" =~ ^[0-9a-f]{64}$ ]]
# The remote command contains only validated hex values. Binary data goes through stdin.
gcloud compute ssh "$GCP_SSH_USER@$GCP_INSTANCE" --project="$GCP_PROJECT_ID" --zone="$GCP_ZONE" \
  --tunnel-through-iap --strict-host-key-checking=yes --ssh-key-expire-after=10m --quiet \
  --command="sudo -n /usr/local/sbin/majung-deploy backend '$DEPLOY_SHA' '$DIGEST'" < "$2"
