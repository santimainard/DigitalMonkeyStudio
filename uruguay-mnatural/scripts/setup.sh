#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ]; then echo "Ya existe .env; se conserva."; exit 0; fi
umask 077
secret() { openssl rand -hex 24; }
cat > .env <<EOF
DATABASE_PASSWORD=$(secret)
PAYMENTS_DATABASE_PASSWORD=$(secret)
ADMIN_EMAIL=admin@example.test
ADMIN_PASSWORD=$(secret)
DEMO_DATA=true
DEMO_PASSWORD=$(secret)
PAYMENT_API_KEY=$(secret)
PAYMENT_WEBHOOK_TOKEN=$(secret)
EOF
echo 'Configuración creada en .env. Allí están tus contraseñas. Ejecutá: docker compose up --build'
