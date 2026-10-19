# Security Policy

## Supported Versions

Security patches and updates are provided for the latest stable release of **PeekESP** across all components (firmware, host agents, Android app, and Cloudflare Worker relay). Older versions are not actively patched.

| Version | Supported          |
| ------- | ------------------ |
| Latest  | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

We take the security and privacy of your systems seriously. If you discover a security vulnerability within PeekESP, please report it responsibly:

* **Private Reporting:** Please report security issues privately rather than opening a public GitHub issue. You can do this via GitHub's private vulnerability reporting feature on the repository.
* **What to Include:**
  * A clear description of the vulnerability and its potential impact.
  * Step-by-step instructions or a proof-of-concept to reproduce the issue.
  * Affected components (e.g., ESP32 firmware, Linux/Windows agent, Android app, or Cloudflare Worker relay).
* **Timeline:** You can expect an initial acknowledgment within 48 to 72 hours, followed by updates as we work on a fix and coordinated disclosure plan.

## Security Design & Architecture

* **Zero-Knowledge Relay:** The Cloudflare Worker relay never handles or stores your raw pairing codes. All streams and auth tokens are derived locally on your devices.
* **Limited Telemetry Scope:** Agents only transmit system performance metrics (CPU, RAM, storage, temperature, uptime, battery, and network throughput). No personal files or credentials are ever accessed or transmitted.
* **Local Network Setup:** The ESP32 captive portal runs over plain HTTP while in AP mode for easy initial configuration, and optional HTTP Basic Auth can protect local LAN access. Always operate your devices behind secure, trusted local networks.
