#!/usr/bin/env sh
# =============================================================================
#  One-command startup for the whole stack (middleware + backend + frontend
#  + observability), all in Docker. Linux/macOS counterpart of scripts/up.ps1.
#
#  Usage:
#      ./scripts/up.sh            # build images and start everything
#      ./scripts/up.sh --no-build # start from existing images
# =============================================================================
set -e

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

if [ ! -f .env ]; then
    if [ ! -f .env.example ]; then
        echo "ERROR: .env.example not found in repo root" >&2
        exit 1
    fi
    cp .env.example .env
    echo "WARNING: .env was missing - created it from .env.example."
    echo "WARNING: MYSQL_ROOT_PASSWORD is still empty. Edit .env, or the stack will refuse to start."
fi

build_flag=""
if [ "$1" != "--no-build" ]; then
    build_flag="--build"
fi

docker compose up -d $build_flag

cat <<'EOF'

=== Stack started ===
  Frontend console : http://localhost:8080
  Backend API      : http://localhost:8081  (management :9091/actuator)
  Grafana          : http://localhost:3000 (admin/admin)
  Prometheus       : http://localhost:9090
  Alertmanager     : http://localhost:9093

  Follow backend logs: docker compose logs -f backend
EOF
