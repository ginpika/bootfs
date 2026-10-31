#!/bin/sh
# Exercise the exact image that will be published, using disposable storage.
set -eu

image=${1:-bootfs:smoke}
platform=${2:-linux/amd64}
name="bootfs-smoke-$(date +%s)-$$"
work_dir=$(mktemp -d)

cleanup() {
    result=$?
    trap - EXIT INT TERM
    if [ "$result" -ne 0 ]; then
        docker logs "$name" >&2 || true
    fi
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker volume rm "$name-data" >/dev/null 2>&1 || true
    rm -rf "$work_dir"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

actual_platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")
[ "$actual_platform" = "$platform" ]
docker volume create "$name-data" >/dev/null
docker run -d --platform "$platform" --name "$name" \
    -p 127.0.0.1::8181 \
    --mount "type=volume,source=$name-data,target=/data" \
    -e TFS_ROOT_PATH=/data -e SSO_ENABLED=false -e S3_ENABLED=false \
    "$image" >/dev/null
address=$(docker port "$name" 8181/tcp)
base_url="http://$address"

wait_ready() {
    attempt=0
    while [ "$attempt" -lt 120 ]; do
        if [ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]; then
            echo 'BootFS container exited before becoming ready.' >&2
            return 1
        fi
        if curl -fsS --connect-timeout 2 --max-time 5 "$base_url/api/cluster/ready" \
            -o "$work_dir/ready.json" 2>/dev/null && \
            jq -e '.phase == "ACTIVE" and .metadataMode == "local"' "$work_dir/ready.json" >/dev/null; then
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    echo 'Timed out waiting for BootFS.' >&2
    return 1
}

wait_ready
[ "$(docker exec "$name" id -u)" -ne 0 ]
docker exec "$name" ffmpeg -version >/dev/null
curl -fsS --max-time 15 "$base_url/" -o "$work_dir/index.html"
grep -qi '<html' "$work_dir/index.html"

printf 'BootFS container persistence check\n' > "$work_dir/ci-smoke.txt"
resource_url=$(curl -fsS --max-time 30 -X PUT \
    -F "file=@$work_dir/ci-smoke.txt;type=text/plain" "$base_url/f")
resource_id=${resource_url##*/}
[ -n "$resource_id" ]

check_resource() {
    curl -fsS --max-time 15 "$base_url/f/$resource_id" -o "$work_dir/download.txt"
    cmp "$work_dir/ci-smoke.txt" "$work_dir/download.txt"
    curl -fsS --max-time 15 --get --data-urlencode search=ci-smoke \
        "$base_url/queryPageOffset" -o "$work_dir/search.json"
    jq -e --arg id "$resource_id" \
        '.total == 1 and any(.rows[]; .uuid == $id)' "$work_dir/search.json" >/dev/null
}

check_resource
# Recreate the container so only the mounted volume can preserve state.
docker stop --time 30 "$name" >/dev/null
docker rm "$name" >/dev/null
docker run -d --platform "$platform" --name "$name" \
    -p 127.0.0.1::8181 \
    --mount "type=volume,source=$name-data,target=/data" \
    -e TFS_ROOT_PATH=/data -e SSO_ENABLED=false -e S3_ENABLED=false \
    "$image" >/dev/null
address=$(docker port "$name" 8181/tcp)
base_url="http://$address"
wait_ready
check_resource
echo "BootFS container checks passed for $platform."
