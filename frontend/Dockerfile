FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm install

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

# nginx processes files in /etc/nginx/templates/ with envsubst on startup
COPY nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_TEMPLATE_VARS=PUBLIC_DOMAIN
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
