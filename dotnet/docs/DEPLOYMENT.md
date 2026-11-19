# Azure deployment runbook

This runbook is for the .NET application under the dotnet directory. Azure deployment has not been executed from this repository because the project owner has not supplied an active Azure subscription. The steps below are reproducible Portal/CLI instructions, not a claim that resources have been created.

## Deployment order

The infrastructure is split to avoid creating a Container App revision before its image exists.

1. Create the Azure foundation: ACR, user-assigned identity, AcrPull, Log Analytics and Application Insights.
2. Create Azure Managed Redis and grant the app identity data access.
3. Build and push the .NET image to ACR.
4. Deploy the Container App using the existing image.
5. Validate health, API and telemetry.
6. Connect Azure DevOps CI/CD after the manual deployment is verified.

## 1. Create the Azure subscription and resource group

1. Create or activate an Azure subscription in the Azure Portal.
2. Open Resource groups and select Create.
3. Use a name such as rg-llm-gateway-dev.
4. Select one region for the resource group, Container Apps and Managed Redis where the selected services are available.
5. Confirm the subscription budget and spending alerts before creating paid services.

## 2. Create the ACR/identity/monitoring foundation

From a shell with Azure CLI and Bicep installed:

- Run az login.
- Set the intended subscription with az account set.
- Create the resource group if it does not exist.
- Copy infrastructure/azure/foundation.parameters.example.json to infrastructure/azure/foundation.parameters.dev.json.
- Set a globally unique ACR name. ACR names accept only letters and digits.
- Run infrastructure/azure/deploy-foundation.sh from the dotnet directory.

The foundation template creates:
- Azure Container Registry Standard with its admin user disabled.
- ARM audience token authentication enabled on ACR.
- A user-assigned managed identity.
- AcrPull at the registry scope for that identity.
- A Log Analytics workspace.
- A workspace-based Application Insights resource.

The Azure Free Account offer currently lists one Standard ACR with 100 GB storage and 10 webhooks for 12 months, subject to eligibility and offer limits. Check the subscription's actual pricing terms before deploying.

## 3. Grant the image publisher permission

The Container App identity has AcrPull only. Your local publisher or the Azure DevOps service principal needs AcrPush at the ACR scope.

In the Portal:
1. Open the ACR resource.
2. Select Access control (IAM).
3. Add a role assignment for AcrPush.
4. Select the developer user or the Azure DevOps workload identity service principal that will push images.
5. Do not enable the ACR admin account as a workaround.

For registries using ABAC repository permissions, use Container Registry Repository Writer for the publisher and Container Registry Repository Reader for the pull identity instead of the classic AcrPush/AcrPull roles.

## 4. Create Azure Managed Redis

Azure Managed Redis is created separately because it is a paid resource and its tier must be selected deliberately.

1. In the Portal, create an Azure Managed Redis resource in the same region as the Container Apps environment where possible.
2. Select the smallest tier that satisfies the demo's memory/throughput requirements. Check the current regional price before confirming.
3. Record the endpoint hostname from the resource overview. Store only the hostname in the app parameter file; the application adds TLS port 10000.
4. Open the Redis resource's Authentication page.
5. Under Microsoft Entra Authentication, choose User or service principal and add the user-assigned managed identity created by the foundation deployment.
6. Assign Data Owner initially so the application can execute its required Lua and key operations. A custom ACL can be evaluated later after the exact commands are validated.
7. For local development against the Azure instance, add the developer's own Entra identity as a Redis user as well.
8. Once Entra access has been verified, consider disabling access-key authentication to remove the static credential path.

The application uses RESP3, TLS and Microsoft.Azure.StackExchangeRedis. DefaultAzureCredential automatically chooses Azure CLI credentials locally and the configured user-assigned managed identity in Container Apps. Set AZURE_CLIENT_ID to the identity's client ID.

## 5. Build and push the container image

From the repository root:

- Authenticate to Azure CLI.
- Run az acr login against the registry created in the foundation step.
- Build the image with the dotnet directory as the Docker build context.
- Tag the image with the full ACR login server and an immutable Git commit SHA.
- Push the tag to ACR.

Example commands:

```bash
ACR_NAME="your-acr-name"
ACR_LOGIN_SERVER="$(az acr show --name "$ACR_NAME" --query loginServer -o tsv)"
IMAGE_TAG="$(git rev-parse HEAD)"

az acr login --name "$ACR_NAME"
docker build -t "$ACR_LOGIN_SERVER/llm-gateway:$IMAGE_TAG" dotnet
docker push "$ACR_LOGIN_SERVER/llm-gateway:$IMAGE_TAG"
```

## 6. Deploy the Container App

1. Copy infrastructure/azure/parameters.example.json to infrastructure/azure/parameters.dev.json.
2. Set location, appName, registryName, imageRepository, imageTag and managedRedisEndpoint.
3. Keep gatewayProvider as mock for the first deployment so the public demo works without an OpenAI key.
4. Run infrastructure/azure/deploy-app.sh from the dotnet directory.
5. The main Bicep template references the foundation resources and deploys the Container App with managed-identity registry authentication.

The app starts with Consumption scaling, min replicas zero, max replicas three and an HTTP concurrency target of 20. These are demonstration settings, not validated production capacity values.

## 7. Configure optional OpenAI access

The mock provider works without a provider key. To enable the GPT-5 mini path:
1. Store the OpenAI key as a Container Apps secret through the Portal or supply it using a local secure deployment parameter file that is excluded from Git.
2. Map the secret to Llm__OpenAiApiKey.
3. Set Gateway__DemoAccessCode to a separate random demo access secret.
4. Keep Gateway__Provider=mock to preserve the current Angular dashboard's primary-model default. The dashboard can select gpt-5-mini through its existing live-model flow.
5. Restart or deploy a new revision after secret changes.

Never put real keys in appsettings.json, parameters.example.json, shell history, screenshots, Git commits or pipeline YAML.

## 8. Validate the deployment

- Open the Container App FQDN from the Bicep output.
- Call /actuator/health; expect JSON with status Healthy.
- Confirm the Redis health check is green.
- Call GET /v1/gateway/status?tenantId=tenant-demo.
- Send a POST to /v1/gateway/chat using a test tenant and prompt.
- Verify the request count, token bucket, daily budget and settlement values update.
- Inspect Container Apps logs and Application Insights traces/requests.
- Confirm the image tag matches the commit SHA.
- Confirm ACR pulls use the user-assigned identity rather than registry passwords.

The Angular app's development proxy strips /api and forwards to /v1/gateway. For a deployed frontend, configure a same-origin reverse proxy or an explicit API base URL and CORS policy; the existing local proxy file is not an Azure frontend deployment.

## 9. Azure DevOps deployment

The pipeline template is dotnet/azure-pipelines.yml.

In Azure DevOps:
1. Create an Azure Resource Manager service connection using workload identity federation.
2. Grant its service principal AcrPush on the registry.
3. Grant only the permissions required to update the Container App.
4. Create variables azureServiceConnection, acrName, acrLoginServer, containerAppName and resourceGroup.
5. Import the YAML pipeline.
6. Run validation first, then merge to main to permit the deploy stage.

The pipeline builds, tests, pushes the commit-tagged image and runs az containerapp update. No Azure DevOps service connection exists yet, so deployment remains unverified.

## 10. Cleanup

If this is only a lab, delete the resource group after collecting evidence. This removes all resources in that group, including ACR images, Container Apps, Redis if it was created in the same group, Application Insights and Log Analytics. Check that no unrelated resource was placed in the group before deletion.

If retaining some components, delete the Container App and managed environment separately and review the continuing costs of Managed Redis, ACR, Log Analytics and Application Insights.
