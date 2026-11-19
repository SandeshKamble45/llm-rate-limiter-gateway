# Security and configuration

## Trust boundaries

- API requests contain a tenantId for compatibility with the existing Angular demo.
- Tenant identifiers are validated and URL-safe encoded before being used in Redis keys.
- In demo mode, the caller-provided tenantId is not proof of identity. Do not expose the public demo as a production multi-tenant API.
- Optional JWT bearer authentication can be enabled through the Authentication section. When enabled, the API requires a valid JWT, and the request tenantId must match the configured tenant claim (default: tenant_id).
- An external identity provider must issue the tenant_id claim and the frontend must send its access token. The existing Angular app does not yet implement MSAL/OIDC token acquisition; leave Authentication:Enabled false until that work is completed.
- The live-model demo access code is a feature gate, not a replacement for JWT authentication.

## Azure identities

- Container Apps uses one user-assigned managed identity.
- ACR: the identity receives AcrPull at registry scope (or Container Registry Repository Reader for ABAC repository-permission mode).
- Azure Managed Redis: add the identity as a Redis user under Authentication and assign a data-plane policy. Data Owner is the broad initial policy used by the runbook; reduce permissions after auditing the Lua script's command set.
- DefaultAzureCredential uses Azure CLI when running locally and the user-assigned identity in Azure when AZURE_CLIENT_ID is configured.

## Secrets

- Never store API keys, JWT signing keys, access codes, Redis passwords, connection strings or certificates in Git.
- Local values belong in ignored .env files or dotnet/infrastructure/azure/*.dev.json files.
- Cloud secrets belong in Container Apps secrets or Key Vault references.
- The Bicep templates accept secure parameters for optional provider and demo secrets. Secure parameters are not committed in the checked-in example parameter files.

## Redis invariants

- All admission and budget operations use Lua scripts; do not replace them with application-level read-modify-write logic.
- Tenant identifiers are validated and encoded before key construction.
- Budget and reservation keys use the same Redis Cluster hash tag, so the multi-key Lua operation remains colocated.
- Daily reservation IDs carry the UTC date so a call crossing midnight settles against the original day's key.
- Reservation settlement is replay-safe. A same-key in-flight or completed reservation is rejected with HTTP 409, preventing duplicate provider execution. This is not yet a complete HTTP response replay cache for Idempotency-Key.
- A cancelled or uncertain provider call leaves its reservation held because the request may have reached the provider. A reconciliation workflow is still required for abandoned reservations.

## Provider safeguards

- The OpenAI adapter enforces max_output_tokens from the reservation policy. The original Java adapter did not include this limit in its request payload.
- The resilience pipeline retries only an explicit HTTP 429 rejection. It does not blindly retry provider POST requests after an ambiguous timeout or 5xx.
- If the primary provider fails, the configured fallback model can return a response. Pricing is based on the model actually returned.
- Do not log prompts, tokens, API keys, demo access codes or raw tenant identifiers.

## Remaining production security work

- Integrate Angular with OIDC/MSAL before turning JWT enforcement on.
- Define the trusted tenant claim mapping and authorization model.
- Replace broad Redis Data Owner with a validated custom data access policy.
- Move secrets to Key Vault references and use managed identity for retrieval.
- Add rate limits and abuse controls at ingress/API gateway for public traffic.
- Define idempotency for the entire HTTP operation, not only budget settlement.
- Add audit and reconciliation for reservations left open by process termination or provider uncertainty.
