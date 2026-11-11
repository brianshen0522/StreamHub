FROM node:24-alpine

# The image mirrors the repository layout so `shared/` sits at the same
# relative path here as it does locally, and one import works in both.
WORKDIR /app

# `prisma db push` regenerates the client on every boot, so the app's own
# directory has to stay writable by the user it runs as. Files are owned by
# that user as they land (COPY --chown, npm ci run as node) instead of a
# `chown -R` afterwards: on overlayfs that walks and copies up every file in
# node_modules, and on this host it held deploys for tens of minutes.
RUN chown node:node /app
USER node

COPY --chown=node:node server/package.json server/package-lock.json* ./server/
COPY --chown=node:node server/prisma ./server/prisma

WORKDIR /app/server
RUN npm ci

WORKDIR /app
COPY --chown=node:node shared ./shared
COPY --chown=node:node server/src ./server/src
COPY --chown=node:node --chmod=755 server/docker-entrypoint.sh ./server/docker-entrypoint.sh

WORKDIR /app/server

EXPOSE 8787

CMD ["./docker-entrypoint.sh"]
