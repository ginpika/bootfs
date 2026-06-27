#!/usr/bin/env bash
#
# purge-user-data.sh
# 删除 BootFS 的全部用户数据：etcd 中的文件元数据、MeiliSearch 索引、
# 本地数据目录中的用户文件以及 db.json 元数据文件。
#
# 保留的系统数据：
#   - etcd 中 /cluster/node/ 前缀的节点注册信息
#   - {root-path}/uuid 节点身份文件
#
# 用法:
#   ./purge-user-data.sh                 # 交互模式，带确认提示
#   ./purge-user-data.sh --dry-run       # 预览将要删除的内容，不实际执行
#   ./purge-user-data.sh --yes           # 跳过确认，直接执行
#
# 可通过环境变量或命令行参数覆盖默认配置:
#   --etcd-endpoint   etcd 地址          (默认: http://localhost:2379)
#   --etcd-container  etcd 容器名         (设置后通过 docker exec 调用 etcdctl)
#   --meili-url       MeiliSearch 地址    (默认: http://localhost:7700)
#   --meili-key       MeiliSearch 主密钥  (默认: default_key)
#   --data-dir        用户数据目录        (默认: ./bootfs/data)
#   --db-json         db.json 路径        (默认: ./bootfs/db.json)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ===== 默认配置 =====
ETCD_ENDPOINT="${ETCD_ENDPOINT:-http://localhost:2379}"
ETCD_CONTAINER="${ETCD_CONTAINER:-}"
MEILI_URL="${MEILI_URL:-http://localhost:7700}"
MEILI_MASTER_KEY="${MEILI_MASTER_KEY:-default_key}"
DATA_DIR="${DATA_DIR:-$SCRIPT_DIR/bootfs/data}"
DB_JSON="${DB_JSON:-$SCRIPT_DIR/bootfs/db.json}"

# 需删除的 etcd 前缀（仅用户数据，保留 /cluster/node/ 节点注册信息）
ETCD_PREFIXES=("/files/" "/properties/main-resources-count/")

# 需删除的 MeiliSearch 索引
MEILI_INDEXES=("full-text" "image-host")

# ===== 运行参数 =====
DRY_RUN=false
ASSUME_YES=false

print_help() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)        DRY_RUN=true; shift ;;
    --yes|-y)         ASSUME_YES=true; shift ;;
    --etcd-endpoint)  ETCD_ENDPOINT="$2"; shift 2 ;;
    --etcd-container) ETCD_CONTAINER="$2"; shift 2 ;;
    --meili-url)      MEILI_URL="$2"; shift 2 ;;
    --meili-key)      MEILI_MASTER_KEY="$2"; shift 2 ;;
    --data-dir)       DATA_DIR="$2"; shift 2 ;;
    --db-json)        DB_JSON="$2"; shift 2 ;;
    -h|--help)        print_help; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

# ===== 工具函数 =====

# 统一的 etcdctl 调用：优先 docker exec，其次本地 etcdctl
run_etcdctl() {
  if [[ -n "$ETCD_CONTAINER" ]]; then
    docker exec "$ETCD_CONTAINER" etcdctl --endpoints=http://127.0.0.1:2379 "$@"
  elif command -v etcdctl &>/dev/null; then
    ETCDCTL_API=3 etcdctl --endpoints="$ETCD_ENDPOINT" "$@"
  else
    echo "错误: 未找到 etcdctl，且未设置 --etcd-container。" >&2
    echo "请安装 etcdctl，或通过 --etcd-container 指定 etcd 容器名" >&2
    echo "（例如: --etcd-container bootfs-etcd-1）。" >&2
    exit 1
  fi
}

# 仅在非 dry-run 模式下执行命令
maybe_run() {
  if $DRY_RUN; then
    echo "  [dry-run] $*"
  else
    eval "$@"
  fi
}

# ===== 清理逻辑 =====

purge_etcd() {
  echo "==> 清理 etcd 中的用户数据 ($ETCD_ENDPOINT)"
  for prefix in "${ETCD_PREFIXES[@]}"; do
    if $DRY_RUN; then
      count=$(run_etcdctl get "$prefix" --prefix --keys-only 2>/dev/null | grep -c . || true)
      echo "  [dry-run] 将删除前缀 '$prefix' 下的 $count 个 key"
    else
      deleted=$(run_etcdctl del "$prefix" --prefix 2>/dev/null | awk '{print $NF}')
      echo "  已删除前缀 '$prefix' 下的 ${deleted:-0} 个 key"
    fi
  done
}

purge_meilisearch() {
  echo "==> 清理 MeiliSearch 索引 ($MEILI_URL)"
  for index in "${MEILI_INDEXES[@]}"; do
    if $DRY_RUN; then
      echo "  [dry-run] 将删除索引 '$index'"
    else
      http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "$MEILI_URL/indexes/$index" \
        -H "Authorization: Bearer $MEILI_MASTER_KEY" || true)
      if [[ "$http_code" == "202" || "$http_code" == "204" ]]; then
        echo "  已删除索引 '$index'"
      elif [[ "$http_code" == "404" ]]; then
        echo "  索引 '$index' 不存在，跳过"
      else
        echo "  警告: 删除索引 '$index' 返回 HTTP $http_code"
      fi
    fi
  done
}

purge_files() {
  echo "==> 清理本地用户文件"
  if [[ -d "$DATA_DIR" ]]; then
    local file_count
    file_count=$(find "$DATA_DIR" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')
    if $DRY_RUN; then
      echo "  [dry-run] 将删除 '$DATA_DIR' 下的 $file_count 个文件/目录"
    else
      find "$DATA_DIR" -mindepth 1 -delete
      echo "  已清空 '$DATA_DIR'"
    fi
  else
    echo "  数据目录 '$DATA_DIR' 不存在，跳过"
  fi
}

purge_db_json() {
  echo "==> 清理 db.json 元数据文件"
  for f in "$DB_JSON" "$DB_JSON.bak"; do
    if [[ -f "$f" ]]; then
      if $DRY_RUN; then
        echo "  [dry-run] 将删除 '$f'"
      else
        rm -f "$f"
        echo "  已删除 '$f'"
      fi
    else
      echo "  文件 '$f' 不存在，跳过"
    fi
  done
  # 应用重启时会自动重建空的 db.json
}

# ===== 主流程 =====

echo "========================================"
echo "  BootFS 用户数据清理"
echo "========================================"
$DRY_RUN && echo "  模式: 预览 (dry-run)" || echo "  模式: 实际执行"
echo ""
echo "目标:"
echo "  - etcd:        $ETCD_ENDPOINT"
[[ -n "$ETCD_CONTAINER" ]] && echo "  - etcd 容器:   $ETCD_CONTAINER"
echo "  - MeiliSearch: $MEILI_URL"
echo "  - 数据目录:    $DATA_DIR"
echo "  - db.json:     $DB_JSON"
echo ""

if ! $DRY_RUN && ! $ASSUME_YES; then
  echo "⚠️  此操作将永久删除上述用户数据，不可恢复！"
  echo "   应用最好已停止运行，避免数据冲突。"
  echo ""
  read -r -p "确认删除？输入 yes 继续: " confirm
  if [[ "$confirm" != "yes" ]]; then
    echo "已取消。"
    exit 0
  fi
  echo ""
fi

purge_etcd
echo ""
purge_meilisearch
echo ""
purge_files
echo ""
purge_db_json

echo ""
echo "========================================"
$DRY_RUN && echo "  预览完成（未实际删除）" || echo "  清理完成"
echo "========================================"
echo "保留的系统数据："
echo "  - etcd /cluster/node/ 节点注册信息"
echo "  - {root-path}/uuid 节点身份文件"
