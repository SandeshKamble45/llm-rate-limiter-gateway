targetScope = 'resourceGroup'

param location string = resourceGroup().location
param appName string = 'llm-gateway'
param registryName string = 'llmgateway${uniqueString(resourceGroup().id)}'
param logRetentionInDays int = 30

var identityName = '${appName}-identity'
var appInsightsName = '${appName}-appi'
var logAnalyticsName = '${appName}-logs'
var acrPullRoleDefinitionId = '7f951dda-4ed3-4680-a7ca-43fe172d538d'

resource gatewayIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: identityName
  location: location
}

resource acr 'Microsoft.ContainerRegistry/registries@2025-04-01' = {
  name: registryName
  location: location
  sku: {
    name: 'Standard'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
    policies: {
      azureADAuthenticationAsArmPolicy: {
        status: 'enabled'
      }
    }
  }
}

resource acrPullAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  // Role-assignment resource names must be computable at the start of deployment.
  // The identity resource ID is deterministic; principalId is a runtime property.
  name: guid(acr.id, gatewayIdentity.id, acrPullRoleDefinitionId)
  scope: acr
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPullRoleDefinitionId)
    principalId: gatewayIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource logWorkspace 'Microsoft.OperationalInsights/workspaces@2022-10-01' = {
  name: logAnalyticsName
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: logRetentionInDays
    features: {
      searchVersion: 1
    }
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: appInsightsName
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    Flow_Type: 'Bluefield'
    Request_Source: 'rest'
    WorkspaceResourceId: logWorkspace.id
  }
}

output registryName string = acr.name
output acrLoginServer string = acr.properties.loginServer
output identityName string = gatewayIdentity.name
output identityResourceId string = gatewayIdentity.id
output identityClientId string = gatewayIdentity.properties.clientId
output identityPrincipalId string = gatewayIdentity.properties.principalId
output appInsightsName string = appInsights.name
output logAnalyticsName string = logWorkspace.name
