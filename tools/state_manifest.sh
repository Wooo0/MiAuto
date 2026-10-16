#!/usr/bin/env bash
# ============================================================================
#  状态清单生成器   state_manifest.sh
# ----------------------------------------------------------------------------
#  为什么需要它：
#    这个项目的成败取决于三类产物，而它们全都落在 git 之外、或者被 .gitignore
#    排除掉了 ——
#      · sdk_smali/      在 miauto_src 仓库之外（4740 个 smali，我们手改的 SDK 源码）
#      · *.dex           .gitignore 里有 *.dex；SDK dex 与"打进 APK 的那份"都在此列
#      · output/*.apk    .gitignore 里有 *.apk；12 个历史包
#
#    后果：git 能告诉你源码改了什么，却完全不知道"当时用的是哪个 SDK dex"。
#    我们之前就吃过亏 —— 修 bug 前后 APK 内 classes5.dex 大小都恰好 7084160 B，
#    只看大小会得出错误结论，只有 md5 能分辨。
#
#    本脚本把这三类产物的哈希写进 docs/BUILD_STATE.md，
#    于是"哈希清单进 git、大文件留在本地"，git 就能连它们一起记录。
#
#  用法：
#    bash tools/state_manifest.sh                # 从 miauto_src/ 目录运行
#    MIAUTO_ROOT=/d/other bash tools/state_manifest.sh   # 手动指定项目根
# ============================================================================
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
ROOT="${MIAUTO_ROOT:-$(cd "$REPO/.." && pwd)}"
OUT="$REPO/docs/BUILD_STATE.md"

h_md5()  { if [ -f "$1" ]; then md5sum "$1" | cut -d' ' -f1; else echo "MISSING"; fi; }
h_size() { if [ -f "$1" ]; then stat -c %s "$1"; else echo "-"; fi; }

# 目录指纹：进入目录 -> 路径排序 -> 逐文件 md5 -> 结果整体再 md5
# 指纹同时包含"相对路径"与"内容"，且与 find 的遍历顺序无关
h_tree() {
  if [ -d "$1" ]; then
    ( cd "$1" && find . -type f | LC_ALL=C sort | xargs -r md5sum ) \
      | md5sum | cut -d' ' -f1
  else
    echo "MISSING"
  fi
}

# ---------------------------------------------------------------- 采集数据
BRANCH="$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
HEAD_SHA="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo '?')"
HEAD_SUBJ="$(git -C "$REPO" log -1 --pretty=%s 2>/dev/null || echo '?')"
DIRTY="$(git -C "$REPO" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"

ORIG="$ROOT/icar-sdk-original-backup.dex"
PATCHED="$ROOT/icar-sdk-patched.dex"
WIRED="$REPO/app/libs/icar-sdk.dex"

ORIG_MD5="$(h_md5 "$ORIG")"
PATCH_MD5="$(h_md5 "$PATCHED")"
WIRED_MD5="$(h_md5 "$WIRED")"

SMALI_DIR="$ROOT/sdk_smali"
SMALI_TREE="$(h_tree "$SMALI_DIR")"
SMALI_N="$(find "$SMALI_DIR" -name '*.smali' -type f 2>/dev/null | wc -l | tr -d ' ')"

# ---------------------------------------------------------------- 判定结论
# 这是本清单最有用的一行：构建实际挂载的 SDK 到底是原版还是我们动过刀的
if [ "$WIRED_MD5" = "$ORIG_MD5" ]; then
  SDK_VERDICT="原版 PR#10（未动刀）"
elif [ "$WIRED_MD5" = "$PATCH_MD5" ]; then
  SDK_VERDICT="我们动过刀的那份（与 PR#10 原版不一致）"
else
  SDK_VERDICT="未知 —— 既不是原版也不是已记录的补丁版，需要人工确认"
fi

# ---------------------------------------------------------------- 写出清单
{
  echo "# 构建状态清单（自动生成，勿手改）"
  echo
  echo "> 由 \`tools/state_manifest.sh\` 生成。用途：记录 git 记不住的三类产物"
  echo "> （\`sdk_smali/\`、\`*.dex\`、\`output/*.apk\`）的哈希，使任一 commit 都能回溯到"
  echo "> \"当时用的是哪个 SDK、发的是哪个包\"。"
  echo
  echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo
  echo "## git 位置"
  echo
  echo "| 项 | 值 |"
  echo "|---|---|"
  echo "| 分支 | \`$BRANCH\` |"
  echo "| 提交 | \`$HEAD_SHA\` — $HEAD_SUBJ |"
  echo "| 工作区 | $DIRTY 个未提交文件 |"
  echo
  echo "## SDK dex 三方对照（本项目最容易出错的地方）"
  echo
  echo "| 文件 | 大小(B) | md5 |"
  echo "|---|---|---|"
  echo "| \`icar-sdk-original-backup.dex\`（PR#10 原版） | $(h_size "$ORIG") | \`$ORIG_MD5\` |"
  echo "| \`icar-sdk-patched.dex\`（smali 重新汇编产物） | $(h_size "$PATCHED") | \`$PATCH_MD5\` |"
  echo "| \`app/libs/icar-sdk.dex\`（构建实际挂载的那份） | $(h_size "$WIRED") | \`$WIRED_MD5\` |"
  echo
  echo "**判定：当前构建使用的 SDK = $SDK_VERDICT**"
  echo
  echo "> 注意：大小会骗人。修 bug 前后这两个 dex 都曾恰好是 7084160 B，"
  echo "> 只有 md5 能分辨，所以核对一律以 md5 为准。"
  echo
  echo "## SDK smali 源（在 miauto_src 仓库之外）"
  echo
  echo "| 项 | 值 |"
  echo "|---|---|"
  echo "| 路径 | \`$SMALI_DIR\` |"
  echo "| smali 文件数 | $SMALI_N |"
  echo "| 目录指纹（路径+内容，顺序无关） | \`$SMALI_TREE\` |"
  echo
  echo "## 新增的 jniLibs（PR#24 指出二者为 arm64 遗留物）"
  echo
  echo "| 文件 | 大小(B) | md5 |"
  echo "|---|---|---|"
  for so in libovmsink.so libusbio.so; do
    p="$REPO/app/src/main/jniLibs/armeabi-v7a/$so"
    echo "| \`armeabi-v7a/$so\` | $(h_size "$p") | \`$(h_md5 "$p")\` |"
  done
  echo
  echo "## 历史构建产物 output/*.apk"
  echo
  echo "| 文件 | 大小(B) | md5 | 时间 |"
  echo "|---|---|---|---|"
  if compgen -G "$ROOT/output/*.apk" >/dev/null 2>&1; then
    for apk in "$ROOT"/output/*.apk; do
      [ -f "$apk" ] || continue
      printf '| `%s` | %s | `%s` | %s |\n' \
        "$(basename "$apk")" "$(h_size "$apk")" "$(h_md5 "$apk")" \
        "$(date -r "$apk" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '-')"
    done
  else
    echo "| （无） | - | - | - |"
  fi
  echo
} > "$OUT"

echo "已写出：$OUT"
echo "  分支 $BRANCH @ $HEAD_SHA（工作区 $DIRTY 个未提交文件）"
echo "  构建使用的 SDK = $SDK_VERDICT"
