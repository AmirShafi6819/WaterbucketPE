# Security Policy

## Reporting a vulnerability

Do **not** open a public issue for security problems. Use GitHub's private security advisory
("Report a vulnerability" on the Security tab) or contact the maintainer directly. Include a
reproduction, affected version and, if possible, a suggested fix.

We aim to acknowledge reports within 72 hours and ship fixes for exploitable issues within 14
days.

## Security model

- **Web panel**: binds to `127.0.0.1` by default and requires a 192-bit token for every API
  call; the token is stored owner-only in `panel_token.txt` and never logged. Exposing the
  panel beyond loopback is only supported behind a TLS-terminating reverse proxy.
- **Proxies**: `network_settings.proxy_protocol_whitelisted_ips` gates PROXY protocol headers;
  keep it to your own edge proxies.
- **Plugins** run inside the proxy process with the same trust level as the JVM itself. Only
  install plugins you trust, in either language.

## Supported versions

| Version | Supported |
|---|---|
| 1.1.x | yes |
| 1.0.x | security fixes only |
| older | not supported |
