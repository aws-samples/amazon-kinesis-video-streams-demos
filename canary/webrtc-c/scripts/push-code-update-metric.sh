#!/bin/bash
# push-code-update-metric.sh
#
# Emits a CloudWatch "code update detected" marker metric for one of the three
# repos the canary tracks: the canary/demos repo, the WebRTC C SDK, and the
# WebRTC JS SDK. It is meant to be called from the existing commit-comparison
# points in build-storage-master.sh and prepare-storage-viewer.sh, at the moment
# a new upstream commit is observed.
#
# Design notes:
#   - Metric is a simple count spike (Value=1) so it renders as a vertical
#     annotation on dashboards. Overlay it on your latency/health widgets to
#     correlate regressions with code changes.
#   - The commit SHA is intentionally NOT a dimension. SHAs are high-cardinality
#     and would spawn a new metric stream per commit (cost + dashboard clutter).
#     The old->new SHAs are logged instead.
#   - This never fails its caller. Metric emission is best-effort; a failure to
#     publish must not break a build.
#
# Usage:
#   ./push-code-update-metric.sh <repo> <old_sha> <new_sha>
#     <repo>     one of: Canary | C | JS  (free-form, kept low-cardinality)
#     <old_sha>  previously cached commit (may be empty on first run)
#     <new_sha>  newly detected commit
#
# Environment:
#   AWS_DEFAULT_REGION / AWS_REGION  region to publish into (default: us-east-1)
#   METRIC_SUFFIX                    appended to the metric name (e.g. -test for gamma)
#   CODE_UPDATE_METRIC_NAMESPACE     override namespace (default: KinesisVideoSDKCanary)

REPO="${1:-}"
OLD_SHA="${2:-}"
NEW_SHA="${3:-}"

if [ -z "$REPO" ] || [ -z "$NEW_SHA" ]; then
    echo "push-code-update-metric: missing repo or new_sha, skipping" >&2
    exit 0
fi

REGION="${AWS_DEFAULT_REGION:-${AWS_REGION:-us-east-1}}"
NAMESPACE="${CODE_UPDATE_METRIC_NAMESPACE:-KinesisVideoSDKCanary}"
METRIC_NAME="CodeUpdateDetected${METRIC_SUFFIX:-}"

OLD_SHORT="${OLD_SHA:0:12}"
echo "${REPO} code update detected: ${OLD_SHORT:-none} -> ${NEW_SHA:0:12}"

# Best-effort publish. Never let a metric failure break the build.
if command -v aws >/dev/null 2>&1; then
    aws cloudwatch put-metric-data \
        --namespace "$NAMESPACE" \
        --region "$REGION" \
        --metric-data "MetricName=${METRIC_NAME},Value=1,Unit=Count,Dimensions=[{Name=Repo,Value=${REPO}}]" \
        && echo "Pushed ${METRIC_NAME} (Repo=${REPO}) to ${NAMESPACE} in ${REGION}" \
        || echo "push-code-update-metric: put-metric-data failed for Repo=${REPO} (non-fatal)" >&2
else
    echo "push-code-update-metric: aws CLI not found, skipping (non-fatal)" >&2
fi

exit 0
