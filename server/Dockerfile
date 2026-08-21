FROM node:24-alpine

# The image mirrors the repository layout so `shared/` sits at the same
# relative path here as it does locally, and one import works in both.
WORKDIR /app

COPY server/package.json server/package-lock.json* ./server/
COPY server/prisma ./server/prisma

WORKDIR /app/server
RUN npm install

WORKDIR /app
COPY shared ./shared
COPY server/src ./server/src
COPY server/docker-entrypoint.sh ./server/docker-entrypoint.sh

WORKDIR /app/server
RUN chmod +x ./docker-entrypoint.sh

EXPOSE 8787

CMD ["./docker-entrypoint.sh"]
