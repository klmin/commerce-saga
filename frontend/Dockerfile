# 화면 SPA 이미지 (ADR 0017, 설계 문서 7-2·7-4). 앱 하나를 골라 정적 파일로 빌드해 nginx 로 낸다.
#
#   docker build --build-arg APP=web   -t commerce-saga/web   frontend
#   docker build --build-arg APP=admin -t commerce-saga/admin frontend
#   docker build --build-arg APP=auth  -t commerce-saga/auth  frontend
#   docker run -p 8080:8080 -e BASE_PATH=/admin/ commerce-saga/admin
#
# 같은 이미지를 호스트 방식(BASE_PATH=/)과 경로 방식(/admin/ 등)에 그대로 쓴다: 앞 경로는 빌드에 넣지 않고
# 시작할 때 index.html 의 <base href> 를 바꾼다(nginx/40-base-path.sh). /api 등 Gateway 경로는 앞단 프록시가 나눈다

FROM node:26-alpine AS build
WORKDIR /src
# 버전은 package.json 의 packageManager 와 같게
RUN npm install -g pnpm@12.23.0
# 의존성 레이어: package.json·lockfile 만 바뀌지 않으면 다시 받지 않는다
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/
COPY apps/admin/package.json apps/admin/
COPY apps/auth/package.json apps/auth/
COPY packages/ui/package.json packages/ui/
COPY packages/bff/package.json packages/bff/
RUN pnpm install --frozen-lockfile
COPY . .
ARG APP
RUN test -n "$APP" && pnpm --filter "$APP" build

# 루트가 아닌 사용자(101)로 8080 에서 듣는 nginx
FROM nginxinc/nginx-unprivileged:1.29-alpine
ARG APP
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 nginx/40-base-path.sh /docker-entrypoint.d/40-base-path.sh
# index.html 을 시작할 때 고치므로 nginx 사용자가 쓸 수 있게
COPY --from=build --chown=101:101 /src/apps/${APP}/dist /usr/share/nginx/html
ENV BASE_PATH=/
EXPOSE 8080
