# Azure Container Apps Applied Skills — project and lab mapping

**Assessment target:** Microsoft Applied Skills, Deploy cloud-native apps using Azure Container Apps.

This mapping separates project evidence from tasks that must be completed in an actual Azure subscription. A local build or a Bicep template does not, by itself, prove an assessed cloud configuration.

## Skill 1 — Secure Azure Container Registry connectivity

### Project implementation
- Bicep provisions Azure Container Registry Standard.
- The registry admin user is disabled.
- ARM audience token authentication is enabled.
- A user-assigned managed identity is attached to Container Apps.
- The identity receives the AcrPull RBAC role at registry scope.
- Container Apps uses that identity in its registry credentials.
- The application does not store ACR passwords.

For a registry configured for repository-level ABAC permissions, use the corresponding Container Registry Repository Reader role rather than AcrPull. The current Bicep uses the conventional RBAC model.

### Separate portal lab
1. Open the registry in Azure Portal.
2. Confirm the registry SKU and networking properties.
3. Under Properties, enable ARM audience token authentication.
4. Open Access control (IAM) and verify the Container App user-assigned identity has AcrPull.
5. On the Container App, verify registry authentication is set to the user-assigned identity and the image can be pulled.
6. Confirm the registry admin user is disabled.

### Private endpoint exercise
ACR Private Link is a Premium SKU feature. The initial Standard deployment deliberately leaves public network access enabled and does not create a private endpoint. For the assessment lab, create a separate Premium registry, configure a private endpoint and private DNS, and verify name resolution from a network-connected client. Azure DevOps Microsoft-hosted agents do not have network line-of-sight to a private endpoint; use a self-hosted agent inside the network for that exercise.

## Skill 2 — Configure Azure Container Apps

### Project implementation
- Bicep creates a Container Apps managed environment.
- The app exposes HTTP ingress on port 8080.
- The image is pulled from ACR using a user-assigned identity.
- Readiness probes use /actuator/health; liveness probes use /healthz.
- Managed Redis uses TLS on port 10000 and Entra token authentication.
- Application Insights connection string is injected as an app setting.
- API credentials are Container Apps secrets, not source-controlled settings.

### Separate portal lab
1. Inspect the managed environment and its Log Analytics destination.
2. Inspect Container App environment variables and secrets. Verify values are not printed in application logs.
3. Review ingress, target port, transport, TLS and external/internal exposure.
4. Verify health probes and restart behaviour.
5. Inspect revisions and replica state after a deployment.
6. Confirm the managed identity principal is provisioned as a Redis user with the required Azure Managed Redis data permissions. The Redis data-plane user assignment is a separate prerequisite from the ACR AcrPull role.

## Skill 3 — Configure Azure Pipelines continuous deployment

### Project implementation
The azure-pipelines.yml file contains:
- .NET 10 SDK setup, restore, build and test.
- Redis integration tests using Testcontainers.
- Image build and push to ACR.
- Container Apps image update using az containerapp update.
- Deployment stage gated to the main branch.

The deployment stage requires an Azure Resource Manager service connection configured with workload identity federation, plus pipeline variables azureServiceConnection, acrName, acrLoginServer, containerAppName and resourceGroup. The pipeline is a template; no Azure DevOps organization or service connection has been provisioned by this repository.

### Separate portal / Azure DevOps lab
1. Create an Azure DevOps project and connect the Git repository.
2. Create an Azure Resource Manager service connection with workload identity federation.
3. Grant the service principal only the required AcrPush permission at registry scope and Container Apps deployment permissions at resource-group/app scope.
4. Create the variables listed above.
5. Run the pipeline; inspect build, test, image push and revision deployment stages.
6. Verify the deployed image tag equals the commit SHA.

## Skill 4 — Configure application scaling

### Project implementation
Bicep uses Consumption workload profile scaling with:
- Minimum replicas = 0
- Maximum replicas = 3
- HTTP concurrency target = 20
- CPU 0.5 and memory 1 GiB per replica

These are starter values, not performance-tested production limits. Scale-to-zero reduces idle compute consumption but adds cold-start latency.

### Separate portal lab
1. Open Scale on the Container App.
2. Review minimum/maximum replicas and HTTP concurrency rules.
3. Generate controlled load.
4. Observe replica count and request latency.
5. Change the concurrency target and compare behaviour.
6. Confirm minimum replicas of zero results in scale-to-zero after inactivity.

## Skill 5 — Manage revisions and traffic splitting

### Project implementation
The starter Bicep deployment uses Single revision mode to keep the first deployment straightforward and minimise simultaneous replica cost.

### Separate assessment lab
1. Change revision mode to Multiple.
2. Deploy a new image tag to create a second revision.
3. Keep both revisions active.
4. Allocate a small percentage of traffic to the candidate revision, for example 90/10.
5. Inspect per-revision replicas, logs and metrics.
6. Shift traffic to 100% on the new revision.
7. Roll traffic back to the prior revision.
8. Deactivate obsolete revisions and verify their state.

## Cost and cleanup notes

- Azure Free Account currently lists one Standard ACR with 100 GB storage and 10 webhooks for 12 months, subject to account eligibility and offer limits. It is not a permanent free tier.
- Premium ACR is required for private endpoints and costs more; keep it confined to the separate lab.
- Azure Managed Redis is a paid resource and can be the largest always-on item in this stack. Choose the lowest suitable tier only after checking current regional pricing.
- Container Apps scale-to-zero can reduce compute consumption; Log Analytics and Application Insights ingestion can still incur charges.
- Delete the resource group after labs if it contains only this project. If retaining the registry or logs, delete the Container App/environment and other no-longer-needed resources separately.
