#!/usr/bin/env bash
set -euo pipefail
: "${RESOURCE_GROUP:?Set RESOURCE_GROUP}"
PARAMETERS_FILE="${FOUNDATION_PARAMETERS_FILE:-infrastructure/azure/foundation.parameters.dev.json}"
az deployment group create --resource-group "$RESOURCE_GROUP" --template-file infrastructure/azure/foundation.bicep --parameters @"$PARAMETERS_FILE"
