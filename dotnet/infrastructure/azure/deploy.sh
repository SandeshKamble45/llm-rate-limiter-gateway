#!/usr/bin/env bash
set -euo pipefail

: "${RESOURCE_GROUP:?Set RESOURCE_GROUP}"
PARAMETERS_FILE="${PARAMETERS_FILE:-infrastructure/azure/parameters.dev.json}"

az deployment group create \
  --resource-group "$RESOURCE_GROUP" \
  --template-file infrastructure/azure/main.bicep \
  --parameters @"$PARAMETERS_FILE" \
  --parameters openAiApiKey="${OPENAI_API_KEY:-}" \
  --parameters demoAccessCode="${DEMO_ACCESS_CODE:-}"
