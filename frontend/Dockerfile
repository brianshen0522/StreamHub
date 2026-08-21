FROM node:24-alpine AS build

# Mirrors the repository layout so the bundle can import shared/ with the same
# relative path the dev server uses.
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json* ./frontend/

WORKDIR /app/frontend
RUN npm install

WORKDIR /app
COPY shared ./shared
COPY frontend ./frontend

WORKDIR /app/frontend
RUN npm run build

FROM nginx:1.27-alpine

# nginx processes files in /etc/nginx/templates/ with envsubst on startup
COPY frontend/nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_TEMPLATE_VARS=PUBLIC_DOMAIN
COPY --from=build /app/frontend/dist /usr/share/nginx/html

EXPOSE 80
