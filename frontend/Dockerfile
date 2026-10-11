# 构建阶段
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 运行阶段：Nginx 托管静态资源并反代后端
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# BACKEND_ORIGIN 由 docker-compose 注入（服务名 http://backend:8081）；
# 这里只是镜像单独运行时的默认值
ENV BACKEND_ORIGIN=http://backend:8081
EXPOSE 80
