#!/bin/bash
# =============================================================
# reset-password.sh — Reset Card Showcase admin credentials
# =============================================================
# Resets username → admin, password → admin123
# =============================================================

echo "=== Resetting admin credentials to default ==="
echo "    Username: admin"
echo "    Password: admin123"
echo ""

# Exact BCrypt hash from V2__seed_data.sql for password "admin123"
# Single quotes preserve $ literally — no shell escaping issues
HASH='$2a$10$ZKK9.C8rSdyySP.OCqAQoufV1ZhxsITNoOVd897JT5VXIANvDO9PW'
SQL="UPDATE admin_users SET username = 'admin', password = '${HASH}' WHERE id = 1;"
echo "    Using hash: ${HASH}"
echo ""

if docker ps | grep -q card-showcase-db; then
    echo "Found production database container..."
    docker exec -i card-showcase-db psql -U postgres card_showcase -c "$SQL"
elif docker ps | grep -q card-db; then
    echo "Found development database container..."
    docker exec -i card-db psql -U postgres card_showcase -c "$SQL"
else
    echo "ERROR: No database container found."
    echo "Make sure Docker is running and the database container is up."
    echo ""
    echo "Running containers:"
    docker ps --format '{{.Names}}'
    exit 1
fi

echo ""
echo "Done! Login with admin / admin123"
echo "Change your password immediately at /admin/settings"
