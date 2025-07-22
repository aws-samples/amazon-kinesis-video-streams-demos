#!/bin/bash

set -e -u -o pipefail

# Check if required commands are available
function check_dependencies() {
    local missing_deps=()

    # Should come pre-installed but check just in case
    if ! command -v mktemp >/dev/null 2>&1; then
        missing_deps+=("mktemp")
        echo "❌ Cannot find mktemp. Please install it and add to PATH."
    else
        echo "✅ Found mktemp"
    fi

    # A bit odd if cargo isn't already installed
    if ! command -v cargo >/dev/null 2>&1; then
        missing_deps+=("cargo")
        echo "❌ Cannot find cargo. Please install it and add to PATH."
    else
        echo "✅ Found cargo"
    fi

    # Check for websocat
    if ! command -v websocat >/dev/null 2>&1; then
        missing_deps+=("websocat")
        echo "❌ Cannot find websocat. Please install it and add to PATH."
    else
        echo "✅ Found websocat"
    fi

    # Exit if any dependencies are missing
    if [[ ${#missing_deps[@]} -gt 0 ]]; then
        echo "❌ Missing required dependencies: ${missing_deps[*]}"
        exit 1
    fi
}

# Connect and disconnect from the WebSocket
function connect_disconnect_websocket() {
    local url="$1"
    local domain
    domain=$(echo "$url" | sed 's|wss://\([^/]*\).*|\1|')

    # Not local, for the trap
    logfile=$(mktemp)

    trap 'rm -f "${logfile}"' EXIT

    if ! echo "Hi" | websocat -v - --ws-c-uri="$url" \
        "ws-c:log:ssl:tcp:${domain}:443" -1 &> "${logfile}"; then
        cat "${logfile}"
        exit 1
    fi

    if grep -q "Connected to ws" "${logfile}"; then
        echo "Connection succeeded"
    else
        echo "Connection failed. Error log:"
        cat "${logfile}"
        exit 1
    fi
}

# Mask sensitive information in GitHub actions
function mask() {
    local url="$1"

    echo "::add-mask::${url}"

    # Mask the signature
    local signature
    signature=$(echo "$url" | grep -o 'X-Amz-Signature=[^&]*' | cut -d'=' -f2)
    if [[ -n "${signature}" ]]; then
        echo "::add-mask::${signature}"
    fi

    # If temporary (STS) credentials are used
    # Use '|| true' to ignore the failing output if this parameter isn't found
    local security_token
    security_token=$(echo "$url" | grep -o 'X-Amz-Security-Token=[^&]*' | cut -d'=' -f2 || true)
    if [[ -n "${security_token}" ]]; then
        echo "::add-mask::${security_token}"
    fi
}

# Get WebSocket URL for a given role
function get_ws_url() {
    local channel_name="$1"
    local role="$2"
    local url

    url=$(cargo run --package kvs_signaling_sigv4_wss_signer \
        --example 3_signer_using_aws_sdk "${channel_name}" "${role}" | \
        grep "wss://" | \
        sed 's/The signed URL is: //')

    if [[ -z "${url}" ]]; then
        echo "Failed to get ${role} URL" >&2
        exit 1
    fi

    echo "${url}"
}

function main() {
    if [[ $# -lt 1 ]]; then
        echo "Usage: $0 <channel-name>" >&2
        exit 1
    fi

    check_dependencies

    local channel_name="$1"
    echo "Testing channel: ${channel_name}"

    # Test MASTER connection
    local master_url
    master_url=$(get_ws_url "${channel_name}" "MASTER")
    mask "${master_url}"
    connect_disconnect_websocket "${master_url}"
    echo "✅ MASTER succeeded"

    # Test VIEWER connection
    local viewer_url
    viewer_url=$(get_ws_url "${channel_name}" "VIEWER")
    mask "${viewer_url}"
    connect_disconnect_websocket "${viewer_url}"
    echo "✅ VIEWER succeeded"
}

main "$@"
