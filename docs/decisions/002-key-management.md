# HMAC based hashing for Refresh Tokens

## Status
Accepted

## Context
Refresh tokens are long-lived bearer credentials used to issue new access tokens. Storing them in plaintext introduces obvious risk, as database compromise would immediately expose valid credentials.

A hashing mechanism is required to avoid storing raw tokens. Using a slow password hashing algorithm like bcrypt is unsuitable because refresh tokens must be looked up frequently and require deterministic hashing for comparison.

A fast hashing approach such as SHA-256 provides determinism but does not protect against offline validation if the database is compromised.

To mitigate this, a keyed hashing approach using HMAC with SHA-256 ensures that token hashes cannot be validated without access to a server-side secret.

## Decision

Refresh tokens will be hashed using HMAC-SHA256 with a server-side secret key before being stored in the database.

The secret key will:

- Be provided externally via environment variables
- Be required at application startup
- Not be auto-generated in production

For development environments, the system may generate a temporary key if none is provided, with a clear warning that tokens will become invalid on restart.


## Consequences

Positive
- Database compromise alone does not allow attackers to validate or correlate refresh tokens
- Deterministic hashing enables efficient token lookup
- Separation of concerns between password hashing and token hashing
- Aligns with standard practices for handling bearer credentials

Negative
- Introduces operational overhead for managing a secret key
- Key rotation requires additional design considerations (e.g., invalidating or rehashing existing tokens)
- Misconfiguration (missing or inconsistent key across instances) can break authentication flows
