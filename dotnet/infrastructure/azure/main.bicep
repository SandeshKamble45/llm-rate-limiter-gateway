targetScope = 'resourceGroup'

param location string = resourceGroup().location
param appName string = 'llm-gateway'
param registryName string
param identityName string = '${appName}-identity'
param logAnalyticsName string = '${appName}-logs'
param appInsightsName string = '${appName}-appi'
param imageRepository string = 'llm-gateway'
param imageTag string
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

resource gatewayIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = {
  name: identityName
}

resource acr 'Microsoft.ContainerRegistry/registries@2025-04-01' existing = {
  name: registryName
}

resource logWorkspace 'Microsoft.OperationalInsights/workspaces@2022-10-01' existing = {
  name: logAnalyticsName
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: appInsightsName
}

resource containerAppsEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${appName}-env'
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
            cpu: json('0.5')
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
}

output containerAppUrl string = 'https://${gatewayApp.properties.configuration.ingress.fqdn}'
output acrLoginServer string = acr.properties.loginServer
output userAssignedIdentityResourceId string = gatewayIdentity.id
output userAssignedIdentityClientId string = gatewayIdentity.properties.clientId
output userAssignedIdentityPrincipalId string = gatewayIdentity.properties.principalId
output applicationInsightsName string = appInsights.name
output logAnalyticsWorkspaceName string = logWorkspace.name
