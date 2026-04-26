#!/bin/sh
set -eu

echo "Waiting for database readiness..."

attempt=0
until npx prisma db push >/tmp/prisma-db-push.log 2>&1
do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "Database initialization failed after ${attempt} attempts."
    cat /tmp/prisma-db-push.log
    exit 1
  fi
  echo "Database not ready yet, retrying (${attempt}/30)..."
  sleep 2
done

cat /tmp/prisma-db-push.log
npm run seed
exec node src/index.js
