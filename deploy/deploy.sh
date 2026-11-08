#!/usr/bin/env bash
# Deploys the POS SaaS stack on the VPS. Called by GitHub Actions over SSH, or by hand:
#   bash deploy.sh backend [branch]   # update backend repo, rebuild + restart backend
#   bash deploy.sh web [branch]       # update frontend repo, rebuild + restart web (Caddy)
#   bash deploy.sh all [branch]       # both
# Everything runs inside main() so bash has parsed the whole script before
# "git reset" can replace this file on disk.

main() {
  set -euo pipefail

  local service="${1:-all}" branch="${2:-main}"
  case "$service" in backend|web|all) ;; *) echo "Usage: $0 <backend|web|all> [branch]" >&2; exit 2 ;; esac

  local deploy_dir be_dir fe_dir
  deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  be_dir="$(dirname "$deploy_dir")"
  cd "$deploy_dir"
  [[ -f .env ]] || { echo "Missing $deploy_dir/.env" >&2; exit 1; }

  # Same default as docker-compose.yml; FE_DIR in .env is relative to this folder.
  fe_dir="$(grep -E '^FE_DIR=' .env | tail -n 1 | cut -d= -f2- || true)"
  fe_dir="$(cd "$deploy_dir/${fe_dir:-../../pos-saas-web}" && pwd)"

  # Backend and frontend workflows may fire together; build one at a time.
  exec 9>/tmp/pos-saas-deploy.lock
  echo "==> Waiting for deploy lock"
  flock 9

  update_repo() {
    echo "==> Updating $1 to origin/$branch"
    git -C "$1" fetch --prune --quiet origin "$branch"
    git -C "$1" checkout --quiet "$branch"
    git -C "$1" reset --quiet --hard "origin/$branch"
    git -C "$1" log -1 --format='    %h %s (%an, %ar)'
  }

  backup_db() {
    # Snapshot before new Flyway migrations run; keep the 5 most recent.
    if [[ -z "$(docker compose ps -q postgres 2>/dev/null)" ]]; then return 0; fi
    mkdir -p "$HOME/backups"
    local file
    file="$HOME/backups/pre-deploy_$(date +%Y%m%d-%H%M%S).dump"
    echo "==> Backing up database to $file"
    docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > "$file"
    ls -1t "$HOME"/backups/pre-deploy_*.dump 2>/dev/null | tail -n +6 | xargs -r rm -f
  }

  rollout() {
    echo "==> Building $1"
    docker compose build "$1"
    echo "==> Starting $1"
    if ! docker compose up -d --wait --wait-timeout 300 "$1"; then
      echo "!! $1 did not become healthy. Last log lines:" >&2
      docker compose logs --tail 80 "$1" >&2
      exit 1
    fi
  }

  if [[ "$service" == backend || "$service" == all ]]; then
    update_repo "$be_dir"
    backup_db
    rollout backend
  fi
  if [[ "$service" == web || "$service" == all ]]; then
    update_repo "$fe_dir"
    rollout web
  fi

  echo "==> Cleaning up old images"
  docker image prune -f >/dev/null
  docker compose ps
  echo "==> Deploy of '$service' finished"
}

main "$@"
exit $?
