# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Generic multi-stage build for Go services driven by goPipeline.
# Written into app repos that do NOT ship their own Dockerfile (goPipeline
# falls back to this library resource — see vars/goPipeline.groovy).
#
# Statically-linked (CGO_ENABLED=0) binary on a distroless static base, so the
# runtime image carries no shell/libc and stays a few MB. MAIN_PKG / BIN_NAME
# are forwarded by goPipeline so the same template builds any single-main repo.
# ---------------------------------------------------------------------------

ARG GO_VERSION=1.22

# --- build stage -----------------------------------------------------------
FROM golang:${GO_VERSION}-bookworm AS build
WORKDIR /src

# Dependency layer caches independently of source. go.sum is optional (a repo
# with no external deps may not have one), so the wildcard never errors.
COPY go.mod go.sum* ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download

COPY . .

ARG MAIN_PKG=.
ARG BIN_NAME=app
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    set -eux; \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
        -o /out/${BIN_NAME} ${MAIN_PKG}

# --- runtime stage ---------------------------------------------------------
FROM gcr.io/distroless/static-debian12:nonroot AS production
WORKDIR /app

ARG BIN_NAME=app
COPY --from=build /out/${BIN_NAME} /app/server

USER nonroot:nonroot
ENTRYPOINT ["/app/server"]
