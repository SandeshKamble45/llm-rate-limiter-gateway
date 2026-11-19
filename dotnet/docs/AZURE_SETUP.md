# Azure Portal setup and cost controls

## Current state

The repository owner has indicated that there is no active Azure subscription yet. Therefore the project is being prepared for local testing and repeatable Azure deployment without assuming cloud credentials or creating billable resources.

## Services in the first deployment

| Resource | Purpose | Cost considerations |
|---|---|---|
| Azure Container Registry Standard | Store versioned application images | Eligible Free Accounts currently list one Standard registry for 12 months; verify account and usage limits |
| Azure Container Apps Consumption | Run ASP.NET Core container | Configure min replicas 0; usage and free grants vary |
| User-assigned Managed Identity | ACR pull and Redis authentication | No password rotation; RBAC assignments still required |
| Azure Managed Redis | Shared, atomic rate-limit and budget state | Paid, provisioned service; select SKU carefully |
| Log Analytics | Container Apps logs | Ingestion/retention can be billable |
| Application Insights | Requests, dependencies, traces and exceptions | Usage-based ingestion; monitor volume |

Azure SQL, Service Bus, Azure Functions and Key Vault are extended production architecture items, not created in the initial deployment. Add them only when their durability, async processing or secret-reference requirements are justified.

## Azure Portal sequence

### A. Subscription and budget
1. Create an Azure Free Account or activate a subscription.
2. Open Cost Management + Billing and create a budget with an alert well below the amount you are willing to spend.
3. Create a dedicated resource group for the gateway.
4. Check region availability for Azure Managed Redis and Container Apps before choosing the region.

### B. Foundation deployment
Use the Bicep template at infrastructure/azure/foundation.bicep. It creates ACR Standard, user-assigned identity, AcrPull, Log Analytics and Application Insights. The registry is created with admin authentication disabled and ARM audience token authentication enabled.

### C. Redis data-plane access
The ACR role assignment only permits image pulls. It does not grant Redis data access.

1. Create Azure Managed Redis in the Portal.
2. Open Authentication on the Redis resource.
3. Under Microsoft Entra Authentication, choose User or service principal.
4. Select the user-assigned managed identity created by the foundation Bicep deployment.
5. Assign Data Owner for the first working demo. This grants broad data-plane command access; a narrower custom ACL can be designed after integration tests.
6. Add the developer's own Entra identity if local code will connect to the Azure Redis instance.
7. Verify Entra authentication from the app before disabling access-key authentication.

### D. Image and app deployment
1. Assign AcrPush to the developer or CI service principal.
2. Build and push the image to ACR.
3. Copy infrastructure/azure/parameters.example.json to a local parameters.dev.json and supply the actual registry, image tag and Redis hostname.
4. Deploy infrastructure/azure/main.bicep.
5. Verify Container Apps ingress, readiness/liveness probes, scaling and Application Insights telemetry.

### E. Security after the demo
- Keep ACR admin credentials disabled.
- Use a user-assigned identity for ACR pull and Redis access.
- Give the Container App identity only the required resource permissions.
- Keep OpenAI and demo access codes in Container Apps secrets or Key Vault; never source-controlled.
- JWT authentication is optional in the API and defaults off for dashboard compatibility. Do not enable it until the frontend obtains and sends a valid OIDC access token and the expected tenant claim is configured.
- Use a private network and private endpoints for production where required. ACR private endpoints require Premium tier and a self-hosted Azure DevOps agent with network line-of-sight.

## Cost warning

Scale-to-zero reduces idle Container Apps compute but does not make the entire architecture free. Azure Managed Redis remains provisioned; Log Analytics and Application Insights can incur ingestion/retention charges. Standard ACR is part of a time-limited free offer for eligible new accounts, not a lifetime free service. Always check the Azure Pricing Calculator for the chosen region and SKU before creating a resource.
