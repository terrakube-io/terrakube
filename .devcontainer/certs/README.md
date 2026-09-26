# Corporate CA certificates

Place company-approved root or intermediate CA certificates in this directory
when your network performs HTTPS inspection. Files ending in `.crt` or `.pem`
are trusted by both the Dev Container operating system and its Java keystore at
startup. They are also trusted while Dev Container Features are installed,
which allows feature installers to work behind an HTTPS-inspection proxy.
Node.js tooling, including Corepack and Yarn, receives the same CA bundle via
`NODE_EXTRA_CA_CERTS`.

Use one PEM certificate per file. Do not add a server/leaf certificate or a
general public CA bundle: trust only the corporate CA that signs your proxy's
certificate chain. These certificate files are intentionally ignored by Git.

After adding, replacing, or removing a certificate, run **Dev Containers:
Rebuild Container** so the Java processes start with the refreshed trust store.
