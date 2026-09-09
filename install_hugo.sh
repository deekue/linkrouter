#!/bin/bash

#HUGO_YAML="$(dirname -- "$(readlink -e -- "${BASH_SOURCE[0]}")")/.github/workflows/hugo.yaml"
#HUGO_VERSION="$(yq -r '.jobs.build.env.HUGO_VERSION' "$HUGO_YAML")"
HUGO_VERSION="0.165.0"

echo "Installing Hugo ${HUGO_VERSION}..."
curl -sfL --output-dir /tmp -O "https://github.com/gohugoio/hugo/releases/download/v${HUGO_VERSION}/hugo_${HUGO_VERSION}_linux-amd64.tar.gz"
curl -sfL --output-dir /tmp -O "https://github.com/gohugoio/hugo/releases/download/v${HUGO_VERSION}/hugo_extended_${HUGO_VERSION}_linux-amd64.tar.gz"
mkdir "${HOME}/.local/hugo"
tar -C "${HOME}/.local/hugo" -xf "/tmp/hugo_${HUGO_VERSION}_linux-amd64.tar.gz"
tar -C "${HOME}/.local/hugo" -xf "/tmp/hugo_extended_${HUGO_VERSION}_linux-amd64.tar.gz"
grep -q '.local/hugo' "$BASH_ENV" || echo "export PATH=$PATH:${HOME}/.local/hugo" >> "$BASH_ENV"
source "$BASH_ENV"
