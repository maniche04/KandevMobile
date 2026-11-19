# Security

Report vulnerabilities privately through this repository's **Security › Report a vulnerability**
(GitHub private vulnerability reporting). Do not open a public issue, and do not include real
server addresses, tokens, relay state or conversation content in a report.

Forge talks only to the Kandev server and optional relay/gateway its operator configures. Each
deployment owns its credentials; this project operates no shared service. Supported: the latest
release, against Kandev v0.98.0 or newer.

Release APKs list their SHA-256 and signing-certificate fingerprint and carry a GitHub build
attestation. Android refuses updates signed with a different key.
