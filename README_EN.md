# BetbetMiro Extension

<div align="center">

### CloudStream provider repository maintained by @sad25kag

Anime • Donghua • Drama • Movie • Multi-Source Providers

<img src="https://img.shields.io/github/stars/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=yellow" />
<img src="https://img.shields.io/github/forks/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=blue" />
<img src="https://img.shields.io/github/license/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=green" />
<img src="https://img.shields.io/github/last-commit/sad25kag/BetbetMiro-Extension?style=for-the-badge&color=red" />

<p>
  <strong>Language:</strong>
  <a href="README.md">Bahasa Indonesia</a> |
  English
</p>

<p>
  <strong>Repository Shortcode:</strong>
  <code>BME-REPO</code>
</p>

</div>

---

## English Documentation

This file is an additional English README for international users.

The original `README.md` is not replaced. It remains the main repository README, while this file provides an English explanation of the repository purpose, installation method, build process, maintenance rules, issue reporting, and contribution expectations.

---

## About This Repository

**BetbetMiro Extension** (`BME-REPO`) is a custom CloudStream extension repository containing multiple providers from different sources.

The repository focuses on provider maintenance, parser fixes, domain updates, category validation, playback stability, and compatibility with the CloudStream extension system.

Development priorities:

1. Keep providers alive and usable.
2. Keep builds clean and compatible with CloudStream.
3. Keep `repo.json` and `plugins.json` valid.
4. Keep GitHub Actions working when available.
5. Keep documentation clear and useful.
6. Apply cosmetic changes only when they do not break provider functionality.

---

## Repository Status

The provider list is dynamic and may change over time. Providers can be added, updated, disabled, or removed depending on source availability, website changes, playback stability, and maintenance requirements.

Providers in this repository depend on third-party websites. Domain changes, page structure changes, access protection, player endpoint changes, or video host changes may affect provider behavior without prior notice.

---

## Content Categories

This repository may include providers for categories such as:

- Anime
- Donghua
- Asian drama
- Indonesian movies
- Western and Asian movies
- Multi-source providers

The actual availability of each category depends on the active providers in the repository.

---

## Repository Installation

### One-Click Install

Open this link on an Android device with CloudStream installed:

```text
cloudstreamrepo://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

### Manual Installation

1. Open CloudStream.
2. Go to **Settings**.
3. Open **Extensions**.
4. Select **Add Repository**.
5. Enter this repository URL:

```text
https://raw.githubusercontent.com/sad25kag/BetbetMiro-Extension/master/repo.json
```

6. Save the repository.
7. Install the provider you want to use.

---

## Build From Source

Clone the repository:

```bash
git clone https://github.com/sad25kag/BetbetMiro-Extension.git
cd BetbetMiro-Extension
```

Build all providers:

```bash
./gradlew make
```

Build output is expected in:

```text
/builds
```

---

## Provider Maintenance Standard

Provider changes should preserve repository stability and minimize regression risk.

Basic maintenance rules:

- Provider version must be bumped in `build.gradle.kts` whenever provider code is changed.
- `search()` should return relevant results when the source supports search.
- `getMainPage()` should follow active categories from the source website.
- `load()` should load valid detail data, including episodes for series or a playable item for movies.
- `loadLinks()` should emit valid video callback links, not only return `true`.
- Parsers should follow the active source structure based on current evidence.
- Extractors should be limited enough to avoid excessive requests, hangs, timeouts, or OutOfMemory errors.
- Unrelated provider files should not be changed.
- Fallbacks, hosts, categories, selectors, and extractors should only be added when there is clear technical evidence.

---

## Provider Fix Workflow

When a provider breaks, investigation should follow the actual source flow:

1. Verify the active domain.
2. Check homepage and category structure.
3. Check search results if the source supports search.
4. Check detail pages and episode/movie play item data.
5. Check player pages, iframes, APIs, media-player scripts, or direct media links.
6. Check active video hosts and extractor behavior.
7. Identify the exact root cause.
8. Patch the root cause directly.
9. Bump the provider version.
10. Run build or validation when the environment is available.

Common root causes include:

- Source domain changed.
- HTML structure changed.
- Search or category endpoint changed.
- Player iframe changed.
- Video host changed.
- Token, referer, origin, or cookie requirement changed.
- Extractor no longer matches the active response.
- Resolver is too broad and causes timeout, hang, or OutOfMemory behavior.
- CloudStream runtime API changed.

---

## CloudStream Compatibility

CloudStream and its extension API may change over time. A provider that previously worked may later fail to build, load, or play content because of runtime changes.

Example compatibility error:

```text
No virtual method parseJson(...)
in class com.lagradost.cloudstream3.utils.AppUtils
```

Errors like this usually indicate a CloudStream runtime/API compatibility issue, not always a dead source website. The affected provider may need to be updated to match the CloudStream version being used.

---

## Reporting Issues

When opening an issue, include as much useful information as possible:

- Provider name.
- Problematic URL.
- Screenshot of the error.
- CloudStream log or provider test log.
- Steps to reproduce the problem.
- Whether the issue happens on CloudStream stable or prerelease.

A complete report helps identify whether the issue comes from the domain, parser, category page, detail page, extractor, video host, or CloudStream runtime.

---

## Pull Requests

Pull Requests are welcome when they are clear, focused, and technically justified.

Accepted PR examples:

- Provider fixes.
- Domain updates.
- Category updates.
- Search, load, or loadLinks fixes.
- Extractor fixes.
- Resolver performance improvements.
- New providers.

Before opening a PR, please check:

- [ ] Provider version has been bumped.
- [ ] Build was run if the environment is available.
- [ ] Search was checked if supported.
- [ ] Homepage/category pages were checked.
- [ ] Detail loading was checked.
- [ ] Playback/loadLinks behavior was checked.
- [ ] Unrelated files were not changed.

---

## Disclaimer

This repository does not store, host, or distribute any video content.

All content comes from third-party sources available on the internet. This repository only provides parsers and provider integrations for CloudStream.

The repository owner is not affiliated with CloudStream or the third-party sources used by the providers.

---

## Credits

Thanks to:

- CloudStream
- Provider and extractor developers
- The open-source community
- Testers and bug reporters
- BetbetMiro Extension contributors

---

<div align="center">

### BetbetMiro Extension

Maintained with parser fixes, extractor patches, source validation, and many Gradle rebuilds.

</div>
