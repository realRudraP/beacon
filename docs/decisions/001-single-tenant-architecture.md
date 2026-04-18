# Single Tenant Architecture for V1
## Status: Accepted

## Context
Since we need to support remote sites with no reliable network connectivity, having the ability to run as a single tenant let's the administrator set up a local instance of the beacon server, and it can run without needing to connect to the internet. This also reduces the complexity associated with running a multi-tenant architecture, which is not needed for the initial release of the product.

## Decision
I am dropping the organizations concept for the initial version of the product. Beacon will be a single-tenant self-hostable application easily deployable via Docker Compose.

## Consequences
Easier offline sync, reduced complexity and faster development. Tradeoff: We won't be able to support multiple organizations on the same instance, but this is not a requirement for the initial release and can be added in the future if needed.
