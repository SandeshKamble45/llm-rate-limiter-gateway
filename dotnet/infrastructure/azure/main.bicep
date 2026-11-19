targetScope = 'resourceGroup'

@description('Azure region for the resource group.')
param location string = resourceGroup().location

@description('Short, globally unique application name. Use lowercase letters and digits.')
param appName string = 'llm-gateway'

@description('Globally unique ACR name. The default is derived from the resource group ID.')
param registryName string = 'llmgateway${uniqueString(resourceGroup().id)}'

@description('Container image repository name.')
param imageRepository string = 'llm-gateway'

@description('Immutable image tag, preferably the Git commit SHA.')
param imageTag string = 'latest'

@description('Existing Azure Managed Redis hostname, without scheme or port.')
param managedRedisEndpoint string

@allowed([
  'mock'
  'openai'
])
param gatewayProvider string = 'mock'

@secure()
param openAiApiKey string = ''

@secure()
param demoAccessCode string = ''

@minValue(0)
param minReplicas int = 0

@minValue(1)
@maxValue(10)
param maxReplicas int = 3

@minValue(30)
@maxValue(90)
param logRetentionInDays int = 30

var acrPullRoleDefinitionId = '7f951dda-4ed3-4680-a7ca-43fe172d538d'
var appInsightsName = '${appName}-appi'
var logAnalyticsName = '${appName}-logs'
var environmentName = '${appName}-env'
var identityName = '${appName}-identity'

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
  name: guid(acr.id, gatewayIdentity.properties.principalId, acrPullRoleDefinitionId)
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

resource containerAppsEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: environmentName
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logWorkspace.properties.customerId
        sharedKey: listKeys(logWorkspace.id, '2022-10-01').primarySharedKey
      }
    }
  }
}

var containerSecrets = concat(
  empty(openAiApiKey) ? [] : [
    {
      name: 'openai-api-key'
      value: openAiApiKey
    }
  ],
  empty(demoAccessCode) ? [] : [
    {
      name: 'demo-access-code'
      value: demoAccessCode
    }
  ]
)

var containerEnvironment = concat(
  [
    {
      name: 'ASPNETCORE_ENVIRONMENT'
      value: 'Production'
    }
    {
      name: 'Gateway__Provider'
      value: gatewayProvider
    }
    {
      name: 'Redis__Endpoint'
      value: managedRedisEndpoint
    }
    {
      name: 'Redis__Port'
      value: '10000'
    }
    {
      name: 'AZURE_CLIENT_ID'
      value: gatewayIdentity.properties.clientId
    }
    {
      name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
      value: appInsights.properties.ConnectionString
    }
  ],
  empty(openAiApiKey) ? [] : [
    {
      name: 'Llm__OpenAiApiKey'
      secretRef: 'openai-api-key'
    }
  ],
  empty(demoAccessCode) ? [] : [
    {
      name: 'Gateway__DemoAccessCode'
      secretRef: 'demo-access-code'
    }
  ]
)

resource gatewayApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: appName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${gatewayIdentity.id}': {}
    }
  }
  properties: {
    managedEnvironmentId: containerAppsEnvironment.id
    configuration: {
      activeRevisionsMode: 'Single'
      secrets: containerSecrets
      registries: [
        {
          server: acr.properties.loginServer
          identity: gatewayIdentity.id
        }
      ]
      ingress: {
        external: true
        targetPort: 8080
        transport: 'auto'
        allowInsecure: false
        traffic: [
          {
            latestRevision: true
            weight: 100
          }
        ]
      }
    }
    template: {
      containers: [
        {
          name: 'gateway'
          image: '${acr.properties.loginServer}/${imageRepository}:${imageTag}'
          resources: {
            cpu: 0.5
            memory: '1Gi'
          }
          env: containerEnvironment
          probes: [
            {
              type: 'Readiness'
              httpGet: {
                path: '/actuator/health'
                port: 8080
              }
              initialDelaySeconds: 10
              periodSeconds: 10
              timeoutSeconds: 5
              failureThreshold: 3
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/healthz'
                port: 8080
              }
              initialDelaySeconds: 20
              periodSeconds: 30
              timeoutSeconds: 5
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                concurrentRequests: '20'
              }
            }
          }
        ]
      }
    }
  }
  dependsOn: [
    acrPullAssignment
  ]
}

output containerAppUrl string = 'https://${gatewayApp.properties.configuration.ingress.fqdn}'
output acrLoginServer string = acr.properties.loginServer
output userAssignedIdentityResourceId string = gatewayIdentity.id
output userAssignedIdentityClientId string = gatewayIdentity.properties.clientId
output userAssignedIdentityPrincipalId string = gatewayIdentity.properties.principalId
output applicationInsightsName string = appInsights.name
output logAnalyticsWorkspaceName string = logWorkspace.name
