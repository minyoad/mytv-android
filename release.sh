#!/bin/bash

# 遇到错误立即停止
set -e

# 配置文件路径
GRADLE_FILE="tv/build.gradle.kts"

# 1. 检查文件是否存在
if [ ! -f "$GRADLE_FILE" ]; then
    echo "❌ 错误: 找不到文件 $GRADLE_FILE"
    exit 1
fi

# 2. 提取当前版本信息
# 使用 sed 提取 versionCode = 后的数字
CURRENT_CODE=$(grep "versionCode =" "$GRADLE_FILE" | sed -E 's/.*versionCode = ([0-9]+).*/\1/')
# 使用 sed 提取 versionName = 后的引号内容
CURRENT_NAME=$(grep "versionName =" "$GRADLE_FILE" | sed -E 's/.*versionName = "([^"]+)".*/\1/')

if [ -z "$CURRENT_CODE" ] || [ -z "$CURRENT_NAME" ]; then
    echo "❌ 错误: 无法从 $GRADLE_FILE 中解析版本信息，请检查文件格式。"
    exit 1
fi

# 3. 计算新版本 Code (自动 +1)
NEW_CODE=$((CURRENT_CODE + 1))

# 4. 交互输入
echo "========================================"
echo "📦 当前版本: $CURRENT_NAME (Code: $CURRENT_CODE)"
echo "🚀 下个版本 Code 将自动升级为: $NEW_CODE"
echo "========================================"

# 如果脚本带参数运行 (./release.sh 2.3.5)，则直接使用参数
if [ -n "$1" ]; then
    NEW_NAME="$1"
else
    read -p "请输入新版本名称 (直接回车保持 $CURRENT_NAME): " INPUT_NAME
    # 如果输入为空，则使用当前名称
    NEW_NAME=${INPUT_NAME:-$CURRENT_NAME}
fi

echo ""
echo "即将执行更新:"
echo "   Version Name: $CURRENT_NAME -> $NEW_NAME"
echo "   Version Code: $CURRENT_CODE -> $NEW_CODE"
echo ""

read -p "确认继续吗? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "已取消。"
    exit 1
fi

# 5. 修改 Gradle 文件
echo "正在更新 $GRADLE_FILE ..."

# 替换 versionCode (匹配 versionCode = 数字)
sed -i.bak "s/versionCode = [0-9]*/versionCode = $NEW_CODE/" "$GRADLE_FILE"

# 替换 versionName (匹配 versionName = "任意字符")
sed -i.bak "s/versionName = \".*\"/versionName = \"$NEW_NAME\"/" "$GRADLE_FILE"

# 删除备份文件
rm "${GRADLE_FILE}.bak"

# 6. Git 操作
TAG_NAME="v$NEW_NAME"

echo "正在执行 Git 提交..."
git add "$GRADLE_FILE"
git commit -m "chore(release): bump version to $NEW_NAME (code $NEW_CODE)"

# 处理 Tag 重复的情况 (如果是同一个版本号重新发布)
if git rev-parse "$TAG_NAME" >/dev/null 2>&1; then
    read -p "⚠️ Tag $TAG_NAME 已存在。是否覆盖? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        git tag -d "$TAG_NAME"
        git push origin :refs/tags/"$TAG_NAME"
    else
        echo "已取消 Tag 创建，仅提交了代码。"
        exit 0
    fi
fi

git tag -a "$TAG_NAME" -m "Release $TAG_NAME"

echo "✅ 本地操作完成。"

# 7. 推送
read -p "是否立即推送到 GitHub 触发编译? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "正在推送到 GitHub..."
    git push origin main
    git push origin "$TAG_NAME"
    echo "🚀 推送成功！GitHub Action 应该已经开始构建。"
else
    echo "请稍后手动执行: git push origin main && git push origin $TAG_NAME"
fi