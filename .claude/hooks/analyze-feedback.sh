#!/bin/bash
# Analyze CI failures and PR review comments to surface recurring patterns.
# Usage: .claude/hooks/analyze-feedback.sh [--ci] [--reviews] [--all]
#
# Outputs a summary of recent failure patterns and review themes that
# could be added to docs/dev/known-issues.md.
#
# Requires: gh CLI authenticated with repo access.

set -euo pipefail

MODE="${1:---all}"
REPO="wvlet/uni"
LIMIT=20

analyze_ci_failures() {
  echo "## Recent CI Failures (last ${LIMIT} runs)"
  echo ""

  # Get recent failed workflow runs
  FAILED_RUNS=$(gh run list --repo "${REPO}" --status failure --limit "${LIMIT}" --json databaseId,name,conclusion,createdAt,headBranch 2>/dev/null || echo "[]")

  if [ "${FAILED_RUNS}" = "[]" ] || [ -z "${FAILED_RUNS}" ]; then
    echo "No recent CI failures found."
    echo ""
    return
  fi

  echo "${FAILED_RUNS}" | jq -r '.[] | "- [\(.createdAt)] \(.name) on \(.headBranch) — \(.conclusion)"'
  echo ""

  # Extract failure annotations from the most recent failed run
  LATEST_FAILED_ID=$(echo "${FAILED_RUNS}" | jq -r '.[0].databaseId // empty')
  if [ -n "${LATEST_FAILED_ID}" ]; then
    echo "### Latest failure details (run ${LATEST_FAILED_ID}):"
    gh run view "${LATEST_FAILED_ID}" --repo "${REPO}" --log-failed 2>/dev/null | tail -50 || echo "(Could not fetch logs)"
    echo ""
  fi
}

analyze_pr_reviews() {
  echo "## Recent PR Review Comments (last ${LIMIT} PRs)"
  echo ""

  # Get recent PRs with review comments
  PRS=$(gh pr list --repo "${REPO}" --state all --limit "${LIMIT}" --json number,title,reviewDecision 2>/dev/null || echo "[]")

  if [ "${PRS}" = "[]" ] || [ -z "${PRS}" ]; then
    echo "No recent PRs found."
    echo ""
    return
  fi

  # For each PR with reviews, extract review comments
  echo "${PRS}" | jq -r '.[] | select(.reviewDecision != null and .reviewDecision != "") | "- PR #\(.number): \(.title) [\(.reviewDecision)]"'
  echo ""

  # Get review comments from the most recent reviewed PR
  LATEST_REVIEWED=$(echo "${PRS}" | jq -r '[.[] | select(.reviewDecision != null and .reviewDecision != "")][0].number // empty')
  if [ -n "${LATEST_REVIEWED}" ]; then
    echo "### Review comments on PR #${LATEST_REVIEWED}:"
    gh api "repos/${REPO}/pulls/${LATEST_REVIEWED}/comments" --jq '.[].body' 2>/dev/null | head -30 || echo "(Could not fetch comments)"
    echo ""
  fi
}

echo "# Feedback Analysis for uni"
echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo ""

case "${MODE}" in
  --ci)      analyze_ci_failures ;;
  --reviews) analyze_pr_reviews ;;
  --all)     analyze_ci_failures; analyze_pr_reviews ;;
  *)         echo "Usage: $0 [--ci] [--reviews] [--all]"; exit 1 ;;
esac

echo "---"
echo "Add recurring patterns to docs/dev/known-issues.md"
echo "Promote well-understood patterns to module CLAUDE.md files"
