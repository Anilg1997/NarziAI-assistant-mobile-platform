#!/bin/bash
# ============================================================================
# download_models.sh - Download AI models and set up native dependencies
# for NarzoAI Assistant.
#
# This script:
# 1. Downloads Gemma 2B GGUF model from HuggingFace
# 2. Downloads Whisper Tiny model from HuggingFace
# 3. Optionally clones llama.cpp and whisper.cpp for local builds
#
# Usage:
#   ./download_models.sh              # Download models only
#   ./download_models.sh --with-src   # Download models + clone source repos
#   ./download_models.sh --help       # Show help
# ============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================================================
# Configuration
# ============================================================================

# Model storage directory (in app's assets for development)
MODELS_DIR="app/src/main/assets/models"

# HuggingFace model URLs
GEMMA_MODEL_URL="https://huggingface.co/google/gemma-2b-GGUF/resolve/main/gemma-2b-it-q4_k_m.gguf"
WHISPER_MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"

# Repository URLs for native source code
LLAMA_CPP_REPO="https://github.com/ggml-org/llama.cpp"
WHISPER_CPP_REPO="https://github.com/ggerganov/whisper.cpp"

# ============================================================================
# Helper Functions
# ============================================================================

print_banner() {
    echo ""
    echo "============================================"
    echo "  NarzoAI Assistant - Model Downloader"
    echo "============================================"
    echo ""
}

print_step() {
    echo -e "${BLUE}[${1}/${2}]${NC} ${3}"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} ${1}"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} ${1}"
}

print_error() {
    echo -e "${RED}[✗]${NC} ${1}"
}

show_help() {
    echo "Usage: ./download_models.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --with-src    Also clone llama.cpp and whisper.cpp source repos"
    echo "  --help        Show this help message"
    echo ""
    echo "This script downloads the required AI models for NarzoAI Assistant."
    echo ""
    echo "Models downloaded:"
    echo "  - Gemma 2B GGUF (google/gemma-2b-GGUF)"
    echo "  - Whisper Tiny (ggerganov/whisper.cpp)"
    echo ""
    exit 0
}

# Check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Calculate file size in MB
get_file_size_mb() {
    local file="$1"
    if [ -f "$file" ]; then
        local bytes=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file" 2>/dev/null)
        echo $((bytes / 1024 / 1024))
    else
        echo "0"
    fi
}

# Download with progress bar
download_file() {
    local url="$1"
    local output="$2"
    local description="$3"

    echo ""
    echo "  Downloading: $description"
    echo "  From: $url"
    echo "  To: $output"
    echo ""

    if command_exists curl; then
        curl -L --progress-bar -o "$output" "$url"
    elif command_exists wget; then
        wget --progress=bar:force -O "$output" "$url"
    else
        print_error "Neither curl nor wget found. Please install one of them."
        return 1
    fi

    # Verify download was successful
    if [ -f "$output" ] && [ $(stat -c%s "$output" 2>/dev/null || stat -f%z "$output" 2>/dev/null) -gt 1000000 ]; then
        return 0
    else
        return 1
    fi
}

# ============================================================================
# Main Script
# ============================================================================

TOTAL_STEPS=5
CURRENT_STEP=0

# Parse arguments
WITH_SRC=false
for arg in "$@"; do
    case $arg in
        --help|-h)
            show_help
            ;;
        --with-src)
            WITH_SRC=true
            shift
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Use --help for usage information."
            exit 1
            ;;
    esac
done

print_banner

# ============================================================================
# Step 1: Create models directory
# ============================================================================
CURRENT_STEP=$((CURRENT_STEP + 1))
print_step $CURRENT_STEP $TOTAL_STEPS "Creating models directory..."

mkdir -p "$MODELS_DIR"
print_success "Models directory: $(realpath "$MODELS_DIR")"

# ============================================================================
# Step 2: Check available disk space
# ============================================================================
CURRENT_STEP=$((CURRENT_STEP + 1))
print_step $CURRENT_STEP $TOTAL_STEPS "Checking available disk space..."

if command_exists df; then
    local_models_dir=$(realpath "$MODELS_DIR")
    available_space=$(df -m "$local_models_dir" 2>/dev/null | awk 'NR==2 {print $4}')
    if [ -n "$available_space" ] && [ "$available_space" -lt 2500 ]; then
        print_warning "Low disk space: ${available_space}MB available"
        print_warning "Models require ~1.5GB of free space"
        echo ""
        read -p "Continue anyway? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            print_error "Download cancelled"
            exit 1
        fi
    else
        print_success "Disk space: ${available_space:-unknown}MB available"
    fi
else
    print_warning "Cannot check disk space (df not available)"
fi

# ============================================================================
# Step 3: Download Whisper Tiny model
# ============================================================================
CURRENT_STEP=$((CURRENT_STEP + 1))
print_step $CURRENT_STEP $TOTAL_STEPS "Downloading Whisper Tiny model..."

WHISPER_OUTPUT="$MODELS_DIR/ggml-tiny.bin"

if [ -f "$WHISPER_OUTPUT" ]; then
    existing_size=$(get_file_size_mb "$WHISPER_OUTPUT")
    print_success "Whisper Tiny already exists (${existing_size}MB)"
    
    if [ "$existing_size" -lt 70 ]; then
        print_warning "Existing file seems too small (${existing_size}MB). Re-downloading?"
        read -p "Re-download? [y/N] " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm "$WHISPER_OUTPUT"
            download_file "$WHISPER_MODEL_URL" "$WHISPER_OUTPUT" "Whisper Tiny (~75MB)"
        fi
    fi
else
    download_file "$WHISPER_MODEL_URL" "$WHISPER_OUTPUT" "Whisper Tiny (~75MB)"
fi

if [ -f "$WHISPER_OUTPUT" ]; then
    final_size=$(get_file_size_mb "$WHISPER_OUTPUT")
    print_success "Whisper Tiny: ${final_size}MB"
else
    print_error "Failed to download Whisper Tiny model"
    print_warning "You can download it manually from:"
    print_warning "  $WHISPER_MODEL_URL"
fi

# ============================================================================
# Step 4: Download Gemma 2B GGUF model
# ============================================================================
CURRENT_STEP=$((CURRENT_STEP + 1))
print_step $CURRENT_STEP $TOTAL_STEPS "Downloading Gemma 2B GGUF model..."

GEMMA_OUTPUT="$MODELS_DIR/gemma-2b-it-q4_k_m.gguf"

if [ -f "$GEMMA_OUTPUT" ]; then
    existing_size=$(get_file_size_mb "$GEMMA_OUTPUT")
    print_success "Gemma 2B already exists (${existing_size}MB)"
    
    if [ "$existing_size" -lt 1000 ]; then
        print_warning "Existing file seems too small (${existing_size}MB). Re-downloading?"
        read -p "Re-download? [y/N] " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm "$GEMMA_OUTPUT"
            download_file "$GEMMA_MODEL_URL" "$GEMMA_OUTPUT" "Gemma 2B GGUF (~1.4GB)"
        fi
    fi
else
    echo ""
    echo -e "${YELLOW}Note:${NC} Gemma 2B is ~1.4GB. This may take a while."
    echo ""
    download_file "$GEMMA_MODEL_URL" "$GEMMA_OUTPUT" "Gemma 2B GGUF (~1.4GB)"
fi

if [ -f "$GEMMA_OUTPUT" ]; then
    final_size=$(get_file_size_mb "$GEMMA_OUTPUT")
    print_success "Gemma 2B GGUF: ${final_size}MB"
else
    print_error "Failed to download Gemma 2B GGUF model"
    print_warning "You can download it manually from:"
    print_warning "  $GEMMA_MODEL_URL"
fi

# ============================================================================
# Step 5: (Optional) Clone source repositories
# ============================================================================
CURRENT_STEP=$((CURRENT_STEP + 1))

if [ "$WITH_SRC" = true ]; then
    print_step $CURRENT_STEP $TOTAL_STEPS "Cloning source repositories..."

    # Clone llama.cpp
    if [ -d "app/src/main/cpp/llama.cpp" ]; then
        print_success "llama.cpp repository already exists"
    else
        echo ""
        echo "  Cloning llama.cpp..."
        git clone --depth 1 --branch b3865 "$LLAMA_CPP_REPO" "app/src/main/cpp/llama.cpp"
        if [ $? -eq 0 ]; then
            print_success "llama.cpp cloned successfully"
        else
            print_error "Failed to clone llama.cpp"
        fi
    fi

    # Clone whisper.cpp
    if [ -d "app/src/main/cpp/whisper.cpp" ]; then
        print_success "whisper.cpp repository already exists"
    else
        echo ""
        echo "  Cloning whisper.cpp..."
        git clone --depth 1 --branch v1.7.4 "$WHISPER_CPP_REPO" "app/src/main/cpp/whisper.cpp"
        if [ $? -eq 0 ]; then
            print_success "whisper.cpp cloned successfully"
        else
            print_error "Failed to clone whisper.cpp"
        fi
    fi
else
    print_step $CURRENT_STEP $TOTAL_STEPS "Skipping source clone (use --with-src to include)"
    print_warning "To use local source repos instead of FetchContent:"
    print_warning "  ./download_models.sh --with-src"
    print_warning "  Then set USE_LOCAL_REPOS=ON in CMakeLists.txt"
fi

# ============================================================================
# Summary
# ============================================================================
echo ""
echo "============================================"
echo "  Download Complete"
echo "============================================"
echo ""

# Show model files
echo "Models in $(realpath "$MODELS_DIR"):"
echo ""

WHISPER_SIZE=$(get_file_size_mb "$WHISPER_OUTPUT")
GEMMA_SIZE=$(get_file_size_mb "$GEMMA_OUTPUT")

if [ -f "$WHISPER_OUTPUT" ]; then
    echo -e "  ${GREEN}[✓]${NC} ggml-tiny.bin    ${WHISPER_SIZE}MB"
else
    echo -e "  ${RED}[✗]${NC} ggml-tiny.bin    Not downloaded"
fi

if [ -f "$GEMMA_OUTPUT" ]; then
    echo -e "  ${GREEN}[✓]${NC} gemma-2b-it-q4_k_m.gguf  ${GEMMA_SIZE}MB"
else
    echo -e "  ${RED}[✗]${NC} gemma-2b-it-q4_k_m.gguf  Not downloaded"
fi

echo ""
echo "Next steps:"
echo "  1. Open the project in Android Studio"
echo "  2. Let Gradle sync complete"
echo "  3. The native libraries (llama.cpp, whisper.cpp) will be built"
echo "     automatically via CMake and FetchContent"
echo "  4. Connect your Android device and run:"
echo "     ./gradlew installDebug"
echo ""
echo "Or to build with local source repos:"
echo "  ./download_models.sh --with-src"
echo "  # Then set USE_LOCAL_REPOS=ON in CMakeLists.txt"
echo ""
