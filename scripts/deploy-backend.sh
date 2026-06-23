#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/bybit-payer/backend}"
BRANCH="${BRANCH:-master}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"
BACKEND_SERVICE="${BACKEND_SERVICE:-backend}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
BACKEND_IMAGE="${BACKEND_IMAGE:-bybit-payer-backend:local}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
SMOKE_PATH="${SMOKE_PATH:-/api/auth/csrf}"

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

wait_for_backend_health() {
  local phase="${1:-deploy}"
  local container_id status deadline

  container_id="$(docker compose -f "$COMPOSE_FILE" ps -q "$BACKEND_SERVICE")"
  if [[ -z "$container_id" ]]; then
    log "Backend container was not found during ${phase}"
    return 1
  fi

  deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  while ((SECONDS < deadline)); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
    case "$status" in
      healthy)
        log "Backend is healthy (${phase})"
        return 0
        ;;
      unhealthy|exited|dead)
        docker compose -f "$COMPOSE_FILE" logs --tail=200 "$BACKEND_SERVICE" >&2 || true
        log "Backend became ${status} during ${phase}"
        return 1
        ;;
      *)
        sleep 5
        ;;
    esac
  done

  docker compose -f "$COMPOSE_FILE" logs --tail=200 "$BACKEND_SERVICE" >&2 || true
  log "Backend did not become healthy in ${HEALTH_TIMEOUT_SECONDS}s during ${phase}"
  return 1
}

run_smoke_check() {
  local endpoint host port url

  endpoint="$(docker compose -f "$COMPOSE_FILE" port "$BACKEND_SERVICE" 8080 | tail -n 1 || true)"
  if [[ -z "$endpoint" ]]; then
    log "Published backend port was not found"
    return 1
  fi

  host="${endpoint%:*}"
  port="${endpoint##*:}"
  host="${host#[}"
  host="${host%]}"
  if [[ -z "$host" || "$host" == "0.0.0.0" || "$host" == "::" ]]; then
    host="127.0.0.1"
  fi

  url="http://${host}:${port}${SMOKE_PATH}"
  if ! curl --fail --silent --show-error "$url" >/dev/null; then
    log "Smoke check failed: ${url}"
    return 1
  fi

  log "Smoke check passed: ${url}"
  return 0
}

rollback_backend() {
  local previous_image="${1:-}"

  if [[ -z "$previous_image" ]]; then
    log "Rollback skipped: previous backend image is unknown"
    return 1
  fi

  if ! docker image inspect "$previous_image" >/dev/null 2>&1; then
    log "Rollback skipped: previous backend image ${previous_image} is not available"
    return 1
  fi

  log "Rolling backend back to previous Docker image ${previous_image}"
  docker tag "$previous_image" "$BACKEND_IMAGE"
  docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate "$BACKEND_SERVICE"

  if ! wait_for_backend_health "rollback" || ! run_smoke_check; then
    log "Rollback failed"
    return 1
  fi

  log "Rollback finished"
  return 0
}

command -v git >/dev/null 2>&1 || fail "git is not installed"
command -v docker >/dev/null 2>&1 || fail "docker is not installed"
docker compose version >/dev/null 2>&1 || fail "docker compose plugin is not installed"

[[ -d "$APP_DIR" ]] || fail "APP_DIR does not exist: ${APP_DIR}"
cd "$APP_DIR"

[[ -d .git ]] || fail "APP_DIR is not a git repository: ${APP_DIR}"
[[ -f "$COMPOSE_FILE" ]] || fail "Compose file was not found: ${COMPOSE_FILE}"
[[ -f .env ]] || fail ".env was not found in ${APP_DIR}"

if ! git diff --quiet || ! git diff --cached --quiet; then
  fail "Tracked local changes found on the server. Commit or revert them before deploy."
fi

current_branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$current_branch" != "$BRANCH" ]]; then
  log "Switching branch from ${current_branch} to ${BRANCH}"
  git switch "$BRANCH"
fi

current_sha="$(git rev-parse HEAD)"
previous_container="$(docker compose -f "$COMPOSE_FILE" ps -q "$BACKEND_SERVICE" || true)"
previous_image=""
if [[ -n "$previous_container" ]]; then
  previous_image="$(docker inspect --format '{{.Image}}' "$previous_container" 2>/dev/null || true)"
fi

log "Fetching origin/${BRANCH}"
git fetch --prune origin "$BRANCH"
target_sha="$(git rev-parse "origin/${BRANCH}")"

if [[ "$current_sha" == "$target_sha" ]]; then
  log "Repository is already at ${target_sha}"
else
  log "Updating repository: ${current_sha} -> ${target_sha}"
  git merge --ff-only "origin/${BRANCH}"
fi

export BACKEND_IMAGE

log "Validating Docker Compose config"
docker compose -f "$COMPOSE_FILE" config --quiet

log "Ensuring PostgreSQL is running"
docker compose -f "$COMPOSE_FILE" up -d "$POSTGRES_SERVICE"

log "Building backend image"
docker compose -f "$COMPOSE_FILE" build "$BACKEND_SERVICE"

log "Restarting backend"
if ! docker compose -f "$COMPOSE_FILE" up -d --no-deps "$BACKEND_SERVICE"; then
  docker compose -f "$COMPOSE_FILE" logs --tail=200 "$BACKEND_SERVICE" >&2 || true
  rollback_backend "$previous_image" || true
  fail "Backend restart failed"
fi

if ! wait_for_backend_health "deploy" || ! run_smoke_check; then
  log "Deploy checks failed; trying rollback"
  rollback_backend "$previous_image" || true
  fail "Deploy checks failed"
fi

docker image prune --force --filter "until=168h" >/dev/null || true

deployed_sha="$(git rev-parse HEAD)"
log "Deploy finished: ${deployed_sha}"
