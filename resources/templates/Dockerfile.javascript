# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Generic multi-stage build for the JS/Vite frontends driven by jsPipeline.
# Written into app repos that do NOT ship their own Dockerfile (jsPipeline
# falls back to this library resource — see vars/jsPipeline.groovy).
#
# Package manager is auto-detected from the committed lockfile, mirroring the
# pipeline's "Install deps" stage (yarn -> pnpm -> npm), so the same template
# serves every project regardless of which PM it standardised on. The build
# output is served as static files with `serve`, matching the convention used
# across our existing UI images (serve -n -s dist -l 5173).
# ---------------------------------------------------------------------------

ARG NODE_VERSION=20
ARG BUILD_OUTPUT_DIR=dist
ARG APP_PORT=5173

# --- build stage -----------------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

# corepack ships the yarn/pnpm shims with Node; the lockfile selects the PM.
RUN corepack enable

# Copy manifests first so the dependency layer caches independently of source.
# package.json always matches, so the wildcard sources never error when a given
# lockfile/rc file is absent.
COPY package.json yarn.lock* pnpm-lock.yaml* package-lock.json* npm-shrinkwrap.json* .npmrc* .yarnrc.yml* ./

RUN --mount=type=cache,target=/root/.yarn \
    --mount=type=cache,target=/root/.cache \
    --mount=type=cache,target=/root/.npm \
    set -eux; \
    if [ -f yarn.lock ]; then \
      yarn install --immutable || yarn install; \
    elif [ -f pnpm-lock.yaml ]; then \
      corepack prepare pnpm@latest --activate; \
      pnpm install --frozen-lockfile || pnpm install; \
    elif [ -f package-lock.json ] || [ -f npm-shrinkwrap.json ]; then \
      npm ci || npm install; \
    else \
      npm install; \
    fi

COPY . .

# Vite reads VITE_API_BASE at build time; jsPipeline can forward it via
# --build-arg VITE_API_BASE=... . Harmless (empty) when not provided.
ARG VITE_API_BASE
ENV VITE_API_BASE=${VITE_API_BASE}

RUN set -eux; \
    if [ -f yarn.lock ]; then yarn build; \
    elif [ -f pnpm-lock.yaml ]; then pnpm run build; \
    else npm run build; fi

# --- runtime stage ---------------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS production
WORKDIR /app

# `serve` is a tiny static server; -s gives SPA history-fallback to index.html.
RUN npm install -g serve

ARG BUILD_OUTPUT_DIR
COPY --from=build /app/${BUILD_OUTPUT_DIR} ./dist

ARG APP_PORT
ENV APP_PORT=${APP_PORT}
EXPOSE ${APP_PORT}

CMD ["sh", "-c", "exec serve -n -s dist -l ${APP_PORT}"]
