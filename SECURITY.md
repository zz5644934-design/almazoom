# Security Policy

## Project and version scope

AlmaZoom is a rooted Android module for Vector/Xposed that changes Samsung Camera and experimental Xiaomi Camera behavior. Please treat security issues affecting the camera process, image handling, or user privacy as sensitive.

The latest published releases at the time this policy was added are:

| Release | Channel | Intended target |
| --- | --- | --- |
| `v1.1.2` | Stable | Samsung Camera 17.0.00.43 / One UI 8.5+ |
| `v1.1.3-beta5` | Beta | Samsung Camera 16.0.00.66 / One UI 8.0 |
| `v1.1.3-xiaomi-beta1` | Experimental beta | Xiaomi Camera 6.3.007270.1 |

Security reports are especially useful for the latest release in each channel and the current `main` branch. This list describes published builds; **it is not a guarantee of security maintenance or device compatibility**. Older builds may not receive fixes.

## Reporting a vulnerability

**Do not post exploitable details, secrets, or sensitive logs in public GitHub Issues, Discussions, or Pull Requests.**

1. Open this repository's **Security** tab.
2. Select **Advisories**, then **Report a vulnerability**, if private vulnerability reporting is enabled.
3. Describe the vulnerability, potential impact, and a safe way to reproduce it.

If the private reporting option is unavailable, open a public issue **only to request a private contact method**. Do not include vulnerability details in that issue.

Useful information for a private report includes:

- The affected AlmaZoom release or commit.
- Device model, Android version, exact Camera app version, and Vector version.
- Expected versus observed behavior and potential security impact.
- Minimal reproduction steps and relevant, redacted `AlmaZoom` logs.
- A suggested fix, if known.

Please remove personal information, file paths containing private data, location metadata, and other sensitive information from logs and attachments. Do not upload private photographs unless necessary and explicitly agreed upon.

## Non-security bugs

Ordinary compatibility problems, preview glitches, and JPEG-saving failures that do not expose data or create a security risk can be reported through regular GitHub Issues. Include your device and Camera versions, as described in the README.

## Response and responsible disclosure

Reports will be handled by maintainers as time and resources allow. No fixed response or remediation deadlines are guaranteed.

Please coordinate public disclosure with the maintainers and allow reasonable time for investigation and a potential fix.
