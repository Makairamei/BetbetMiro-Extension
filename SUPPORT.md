# Support

This document explains how to get help with **BetbetMiro Extension**.

BetbetMiro Extension is a community-maintained CloudStream extension repository. Support is focused on repository usage, provider issues, build issues, and contribution workflow.

---

## Official Support Channel

Please use **GitHub Issues** for support requests related to this repository.

Use the correct issue template whenever possible:

- Broken provider: report providers that no longer load, search, show episodes, or resolve playback links.
- Provider request: request a new source/provider.
- Bug report: report repository, workflow, build, or documentation problems.

Please do not send private messages for normal provider issues. Public issues are easier to track, reproduce, and fix.

---

## Before Asking for Help

Before opening an issue, please check:

1. You are using the latest available plugin/repository build.
2. The source website is reachable in your browser.
3. The issue is reproducible.
4. CloudStream itself is working with other providers.
5. Your network, VPN, DNS, or regional blocking is not the cause.

Some sources may block certain regions, DNS providers, VPNs, or user agents.

---

## Information Needed for Provider Issues

For broken provider reports, include:

- Provider name.
- Problematic URL, if available.
- CloudStream version.
- CloudStream channel: stable, prerelease, or custom build.
- Device type: phone, tablet, TV, emulator, etc.
- What works and what does not work.
- Steps to reproduce.
- Screenshot or screen recording when useful.
- Logcat or error message, if available.

For playback issues, also include whether the problem happens at:

- Detail page.
- Episode list.
- Player page.
- Iframe/API step.
- Direct video link resolving.
- Subtitle loading.

A report saying only "not working" is usually not enough to investigate.

---

## Information Needed for Build Issues

For build or compile issues, include:

- Command used.
- Operating system.
- Java version.
- Gradle output.
- Provider/module that failed.
- Recent files changed, if any.

Useful commands:

```bash
./gradlew make
```

For Windows:

```bat
.\gradlew.bat make
```

Do not claim that the build is successful unless Gradle actually finishes successfully.

---

## Contribution Support

If you want to contribute, read:

- `README.md`
- `README_EN.md`
- `CONTRIBUTING.md`
- `.github/pull_request_template.md`

Provider contributions should be evidence-based and focused. Avoid broad formatting changes or unrelated provider edits.

---

## Adult Content Notice

Some providers may access adult or NSFW content.

Users and contributors are responsible for:

- Following the laws in their own country or region.
- Respecting age restrictions.
- Avoiding illegal, abusive, or prohibited content.
- Following GitHub rules when posting screenshots, logs, or examples.

Do not upload explicit media or illegal content to issues or pull requests.

---

## What This Repository Does Not Provide

This repository does not provide:

- Official CloudStream app support.
- Support for illegal redistribution of copyrighted content.
- Guaranteed uptime for third-party websites.
- Private troubleshooting for every source outage.
- Help bypassing law enforcement, paywalls, or access controls.

Provider availability depends on third-party sources and may change without notice.

---

## Maintainer Response

The maintainer may ask for more logs, source evidence, screenshots, HAR notes, or reproduction steps before fixing a provider.

Incomplete reports may be closed or marked as needing more information.

The goal is to keep the repository maintainable, buildable, and useful for CloudStream users.
