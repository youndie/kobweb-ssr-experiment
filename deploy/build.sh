#!/usr/bin/env bash
#
# Builds both images. The Gradle half runs on the host because the export needs a browser and the
# renderer needs a JDK 21 toolchain, and no single base image gives both without a fight.
#
#   ./deploy/build.sh [tag]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

TAG=${1:-dev}
: "${JAVA_HOME:=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME")}"
export JAVA_HOME

echo "== gradle: export the site and build the renderer"
( cd probe
  ./gradlew --console=plain :renderer:installDist
  # kobwebBuildTarget=RELEASE, and it is not optional. The export refuses to run with env=PROD —
  # it always spins up a dev server to snapshot against — and the build target then defaults to
  # DEBUG, which generates a client that opens an EventSource on /api/kobweb-status for live
  # reload. In production nothing serves that, so every page load ended in a console 404. It also
  # shrinks the shell from 1877 bytes to 515.
  ./gradlew --console=plain :site:kobwebExport \
    -PprobePlugin -PkobwebReuseServer=false -PkobwebEnv=DEV -PkobwebBuildTarget=RELEASE \
    -PkobwebExportLayout=FULLSTACK
  ./gradlew --console=plain :site:kobwebStop >/dev/null 2>&1 || true
  # The export leaves the pre-rendered pages behind; the image must not carry them. Removed here
  # rather than in the Dockerfile so that a `docker build` by hand cannot forget.
  rm -rf site/.kobweb/site/pages
)

echo "== docker: site"
docker build -f deploy/Dockerfile.site  -t "kobweb-ssr-site:$TAG"     .
echo "== docker: renderer"
docker build -f deploy/Dockerfile.renderer -t "kobweb-ssr-renderer:$TAG" .

echo
docker image ls --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | grep kobweb-ssr
