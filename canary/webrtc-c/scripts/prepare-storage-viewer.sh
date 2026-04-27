#!/bin/bash
# prepare-storage-viewer.sh
#
# Installs all dependencies needed by the storage viewer test. This script is
# designed to run while the storage master is still building, so that the
# viewer node is ready to go the moment the master signals MASTER_READY.
#
# Skip logic: each section checks whether work is actually needed before doing
# anything expensive (apt-get, npm install, git clone). A stamp file records
# the state of the last successful setup so repeated runs are near-instant.
#
# Expected env vars (optional):
#   JS_PAGE_URL  — branch name or full URL for the JS SDK viewer page

set -euo pipefail

# ---------------------------------------------------------------------------
# Persistent base directory (survives across Jenkins builds)
# ---------------------------------------------------------------------------
VIEWER_BUILD_HOME="${HOME}/JS-viewer-build"
DEPS_STAMP="${VIEWER_BUILD_HOME}/.deps-installed"

# ---------------------------------------------------------------------------
# 1. System dependencies (Node.js 20+, ffmpeg, tesseract)
#    Only touch apt if something is actually missing.
# ---------------------------------------------------------------------------
install_system_deps() {
    local need_apt=false

    # Check Node.js
    local node_ver
    node_ver=$(node --version 2>/dev/null | sed 's/v//' | cut -d. -f1)
    if ! command -v npm &> /dev/null || [ "${node_ver:-0}" -lt 20 ]; then
        need_apt=true
    fi

    # Check ffmpeg & tesseract
    if ! command -v ffmpeg &> /dev/null || ! command -v tesseract &> /dev/null; then
        need_apt=true
    fi

    # If stamp exists and all binaries are present, skip entirely
    if [ "$need_apt" = false ] && [ -f "$DEPS_STAMP" ]; then
        echo "System dependencies already installed (stamp: $(cat "$DEPS_STAMP"))"
        return 0
    fi

    echo "Installing missing system dependencies..."

    # Node.js 20+
    if ! command -v npm &> /dev/null || [ "${node_ver:-0}" -lt 20 ]; then
        echo "Node.js ${node_ver:-not found}, installing v20..."
        sudo apt-get remove -y libnode72 nodejs 2>/dev/null || true
        sudo apt-get autoremove -y 2>/dev/null || true

        for i in {1..3}; do
            if curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - \
                && sudo apt-get install -y nodejs; then
                break
            fi
            echo "Node.js install attempt $i failed, retrying in 10s..."
            sleep 10
        done
    fi

    if ! command -v npm &> /dev/null; then
        echo "ERROR: npm not found after installation"
        exit 1
    fi
    echo "Node.js: $(node --version), npm: $(npm --version)"

    # ffmpeg & tesseract
    if ! command -v ffmpeg &> /dev/null || ! command -v tesseract &> /dev/null; then
        echo "Installing ffmpeg and tesseract-ocr..."
        sudo apt-get update -y
        sudo apt-get install -y ffmpeg tesseract-ocr \
            || { echo "ERROR: Failed to install ffmpeg/tesseract"; exit 1; }
    fi

    # Write stamp
    mkdir -p "$VIEWER_BUILD_HOME"
    date -u '+%Y-%m-%dT%H:%M:%SZ' > "$DEPS_STAMP"
    echo "System dependencies ready"
}

# ---------------------------------------------------------------------------
# 2. npm install for Puppeteer / AWS SDK (in the workspace scripts dir)
# ---------------------------------------------------------------------------
install_npm_deps() {
    cd ./canary/webrtc-c/scripts || { echo "ERROR: cd to scripts failed"; exit 1; }

    # Only run npm install if node_modules is missing or package.json changed
    if [ -d "node_modules" ] && [ -f ".package-json-hash" ]; then
        local current_hash
        current_hash=$(md5sum package.json 2>/dev/null | cut -d' ' -f1)
        local cached_hash
        cached_hash=$(cat .package-json-hash 2>/dev/null)
        if [ "$current_hash" = "$cached_hash" ]; then
            echo "npm dependencies up to date (package.json unchanged)"
            cd - > /dev/null
            return 0
        fi
    fi

    echo "Running npm install..."
    npm install || { echo "ERROR: npm install failed"; exit 1; }
    md5sum package.json | cut -d' ' -f1 > .package-json-hash

    cd - > /dev/null
}

# ---------------------------------------------------------------------------
# 3. Puppeteer browsers (Chrome + chrome-headless-shell)
# ---------------------------------------------------------------------------
ensure_puppeteer_browsers() {
    cd ./canary/webrtc-c/scripts || { echo "ERROR: cd to scripts failed"; exit 1; }

    local chrome_path
    chrome_path=$(node -e "try { console.log(require('puppeteer').executablePath()) } catch(e) { console.log('') }" 2>/dev/null)

    local headless_ok=true
    if ! ls "$HOME/.cache/puppeteer/chrome-headless-shell"/linux-*/chrome-headless-shell-linux64/chrome-headless-shell 2>/dev/null; then
        headless_ok=false
    fi

    if [ -n "$chrome_path" ] && [ -f "$chrome_path" ] && [ "$headless_ok" = true ]; then
        echo "Puppeteer browsers verified"
        cd - > /dev/null
        return 0
    fi

    echo "Puppeteer browser(s) missing or corrupt, reinstalling..."
    rm -rf "$HOME/.cache/puppeteer/chrome" 2>/dev/null || true
    rm -rf "$HOME/.cache/puppeteer/chrome-headless-shell" 2>/dev/null || true
    npx puppeteer browsers install chrome \
        || { echo "ERROR: Failed to install Chrome"; exit 1; }
    npx puppeteer browsers install chrome-headless-shell \
        || { echo "ERROR: Failed to install chrome-headless-shell"; exit 1; }
    echo "Puppeteer browsers installed"

    cd - > /dev/null
}

# ---------------------------------------------------------------------------
# 4. Python venv for video verification
# ---------------------------------------------------------------------------
ensure_python_venv() {
    local venv_dir="${HOME}/.venv/video-verify"
    local venv_stamp="${venv_dir}/.pip-installed"

    if [ -d "$venv_dir" ] && [ -f "$venv_stamp" ]; then
        echo "Python venv already set up (stamp: $(cat "$venv_stamp"))"
        source "$venv_dir/bin/activate"
        return 0
    fi

    if [ ! -d "$venv_dir" ]; then
        echo "Creating Python venv at $venv_dir..."
        sudo apt-get install -y python3-venv 2>/dev/null || true
        python3 -m venv "$venv_dir" || { echo "ERROR: Failed to create venv"; exit 1; }
    fi

    source "$venv_dir/bin/activate"
    pip install pytesseract Pillow scikit-image numpy \
        || { echo "ERROR: pip install failed"; exit 1; }
    date -u '+%Y-%m-%dT%H:%M:%SZ' > "$venv_stamp"
    echo "Python venv ready: $(python3 --version)"
}

# ---------------------------------------------------------------------------
# 5. JS SDK branch clone/update
#    Persistent location: ~/JS-viewer-build/{branch}/repo/
# ---------------------------------------------------------------------------
ensure_js_sdk() {
    local branch="$1"

    # Skip if it's a full URL (not a branch name)
    if [[ "$branch" == *"://"* ]]; then
        echo "JS_PAGE_URL is a full URL, skipping clone"
        return 0
    fi

    local branch_dir="${VIEWER_BUILD_HOME}/${branch}"
    local repo_dir="${branch_dir}/repo"
    local commit_file="${branch_dir}/.last-commit"
    local lock_file="${branch_dir}/.build.lock"

    mkdir -p "$branch_dir"

    # Acquire exclusive lock — blocks if another viewer is cloning/updating
    exec 8>"$lock_file"
    echo "Acquiring JS SDK lock for branch '$branch'..."
    flock 8
    echo "JS SDK lock acquired"

    local remote_head
    remote_head=$(git ls-remote https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-js.git "$branch" | cut -f1)

    if [ -z "$remote_head" ]; then
        echo "ERROR: Could not resolve remote HEAD for branch '$branch'"
        flock -u 8
        exit 1
    fi

    # Check if we already have this commit
    if [ -d "$repo_dir/.git" ] && [ -f "$commit_file" ]; then
        local cached_commit
        cached_commit=$(cat "$commit_file")
        if [ "$cached_commit" = "$remote_head" ]; then
            echo "JS SDK branch '$branch' up to date at ${remote_head:0:8}"
            flock -u 8
            return 0
        fi
        echo "JS SDK has new commits ($cached_commit -> $remote_head), updating..."
        (cd "$repo_dir" && git fetch origin "$branch" && git reset --hard FETCH_HEAD) \
            || { echo "ERROR: git update failed"; flock -u 8; exit 1; }
        (cd "$repo_dir" && npm install) \
            || { echo "ERROR: npm install failed after update"; flock -u 8; exit 1; }
        echo "$remote_head" > "$commit_file"
        flock -u 8
        return 0
    fi

    # Fresh clone
    echo "Cloning JS SDK branch '$branch'..."
    rm -rf "$repo_dir"
    git clone -b "$branch" --depth 1 \
        https://github.com/awslabs/amazon-kinesis-video-streams-webrtc-sdk-js.git \
        "$repo_dir" || { echo "ERROR: git clone failed"; flock -u 8; exit 1; }
    (cd "$repo_dir" && npm install) \
        || { echo "ERROR: npm install failed"; flock -u 8; exit 1; }
    echo "$remote_head" > "$commit_file"
    echo "JS SDK cloned and built at $repo_dir"
    flock -u 8
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
mkdir -p "$VIEWER_BUILD_HOME"

install_system_deps
install_npm_deps
ensure_puppeteer_browsers
ensure_python_venv

if [ -n "${JS_PAGE_URL:-}" ]; then
    ensure_js_sdk "$JS_PAGE_URL"
fi

echo "Viewer preparation complete"
