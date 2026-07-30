#!/bin/bash
# preserve-agent-worktrees.sh
# 
# CRITICAL: Run this BEFORE any git reset, rebase, or cleanup
# Extracts all uncommitted changes from agent worktrees to safe patches

set -e

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_DIR="$HOME/agent-worktree-backups/$TIMESTAMP"
PATCH_DIR="$BACKUP_DIR/patches"
INFO_DIR="$BACKUP_DIR/info"

mkdir -p "$PATCH_DIR" "$INFO_DIR"

echo "=================================="
echo "Preserving Agent Worktree Changes"
echo "=================================="
echo "Backup location: $BACKUP_DIR"
echo ""

# Agent 1: Console/Environment Examples
echo "[1/4] Extracting agent-a29ae12796ed07dd6..."
cd ".claude/worktrees/agent-a29ae12796ed07dd6"

# Save uncommitted changes
git diff HEAD > "$PATCH_DIR/agent-a29ae12796ed07dd6-uncommitted.patch"
git diff --cached > "$PATCH_DIR/agent-a29ae12796ed07dd6-staged.patch"

# Save metadata
{
  echo "Agent Worktree: agent-a29ae12796ed07dd6"
  echo "Branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "HEAD: $(git rev-parse HEAD)"
  echo "HEAD Message: $(git log -1 --format=%s)"
  echo "Distance from main: $(git rev-list --count main..HEAD 2>/dev/null || echo 'N/A')"
  echo ""
  echo "=== Modified Files ==="
  git status --short
  echo ""
  echo "=== Patch Stats ==="
  echo "Uncommitted changes: $(wc -l < "$PATCH_DIR/agent-a29ae12796ed07dd6-uncommitted.patch") lines"
  echo "Staged changes: $(wc -l < "$PATCH_DIR/agent-a29ae12796ed07dd6-staged.patch") lines"
} > "$INFO_DIR/agent-a29ae12796ed07dd6-info.txt"

cd - > /dev/null

# Agent 2: Tic-Tac-Toe & Package Examples
echo "[2/4] Extracting agent-aca437c53cae36987..."
cd ".claude/worktrees/agent-aca437c53cae36987"

# Save uncommitted changes
git diff HEAD > "$PATCH_DIR/agent-aca437c53cae36987-uncommitted.patch"
git diff --cached > "$PATCH_DIR/agent-aca437c53cae36987-staged.patch"

# Save metadata
{
  echo "Agent Worktree: agent-aca437c53cae36987"
  echo "Branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "HEAD: $(git rev-parse HEAD)"
  echo "HEAD Message: $(git log -1 --format=%s)"
  echo "Distance from main: $(git rev-list --count main..HEAD 2>/dev/null || echo 'N/A')"
  echo ""
  echo "=== Modified Files ==="
  git status --short
  echo ""
  echo "=== Patch Stats ==="
  echo "Uncommitted changes: $(wc -l < "$PATCH_DIR/agent-aca437c53cae36987-uncommitted.patch") lines"
  echo "Staged changes: $(wc -l < "$PATCH_DIR/agent-aca437c53cae36987-staged.patch") lines"
} > "$INFO_DIR/agent-aca437c53cae36987-info.txt"

cd - > /dev/null

# Markdown Documentor (should be clean)
echo "[3/4] Checking feat-markdown-documentor..."
cd ".claude/worktrees/feat-markdown-documentor"

{
  echo "Worktree: feat-markdown-documentor"
  echo "Branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "HEAD: $(git rev-parse HEAD)"
  echo "HEAD Message: $(git log -1 --format=%s)"
  echo "Status: $(git status --short || echo 'CLEAN')"
  echo "Distance from main: $(git rev-list --count main..HEAD 2>/dev/null || echo 'N/A')"
} > "$INFO_DIR/feat-markdown-documentor-info.txt"

cd - > /dev/null

# Main workspace status
echo "[4/4] Capturing main workspace state..."
{
  echo "Main Workspace"
  echo "=============="
  echo "Branch: $(git rev-parse --abbrev-ref HEAD)"
  echo "HEAD: $(git rev-parse HEAD)"
  echo "Status: $(git status --short)"
  echo ""
  echo "=== Uncommitted Changes ==="
  git diff --stat HEAD || echo "None"
  echo ""
  echo "=== Staged Changes ==="
  git diff --cached --stat || echo "None"
} > "$INFO_DIR/main-workspace-info.txt"

echo ""
echo "=================================="
echo "Backup Complete"
echo "=================================="
echo ""
echo "Saved to: $BACKUP_DIR"
echo ""
echo "=== Verification ==="
ls -lh "$PATCH_DIR"/
echo ""
echo "=== Info Files ==="
ls -lh "$INFO_DIR"/
echo ""
echo "=== Next Steps ==="
echo "1. Review patches:"
echo "   head -30 $PATCH_DIR/*.patch"
echo ""
echo "2. Validate patches:"
echo "   cd /Users/werner/github.com/wstein/flix-fork"
echo "   git apply --check $PATCH_DIR/agent-a29ae12796ed07dd6-uncommitted.patch"
echo "   git apply --check $PATCH_DIR/agent-aca437c53cae36987-uncommitted.patch"
echo ""
echo "3. If validation passes, integrate:"
echo "   git apply --3way $PATCH_DIR/agent-a29ae12796ed07dd6-uncommitted.patch"
echo "   git add examples/effects-and-handlers/"
echo "   git commit -m 'fix(examples): update console/env examples'"
echo ""
echo "   git apply --3way $PATCH_DIR/agent-aca437c53cae36987-uncommitted.patch"
echo "   git add examples/apps/ examples/package-manager/"
echo "   git commit -m 'fix(examples): update tic-tac-toe and package-manager examples'"
echo ""
echo "4. After integration, remove worktrees:"
echo "   git worktree remove .claude/worktrees/agent-a29ae12796ed07dd6"
echo "   git worktree remove .claude/worktrees/agent-aca437c53cae36987"
echo ""
