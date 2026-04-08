#!/bin/bash
# =============================================================
# deploy.sh — Build and deploy Card Showcase with Docker Compose
# =============================================================
set -e

echo "=== Card Showcase Deployment ==="

if [ ! -f .env ]; then
    echo "ERROR: .env file not found."
    echo "Copy .env.example to .env and fill in your values:"
    echo "  cp .env.example .env"
    exit 1
fi

echo "Bringing down existing containers..."
docker compose down

echo "Building image (no cache)..."
docker compose build --no-cache

echo "Starting containers..."
docker compose up -d

echo "Waiting for services to become ready..."
sleep 15

STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost/admin/login 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ]; then
    echo "App is running at http://localhost"
else
    echo "App may still be starting (HTTP $STATUS). Check logs with:"
    echo "  docker compose logs -f app"
fi

echo "=== Done ==="
