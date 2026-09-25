# Med Rx roadmap

Living document — update it when the plan changes; it is not a promise calendar.

## Identity: “Med RX” → “Med Rx” — SHIPPED in 2.2.0-fork.1

The brand is written **Med Rx** (lowercase x); no rebrand narrative accompanies it. Launcher name is localized consistently on phone and Wear. The package id remains `com.nukirk.medrx` so existing installations can update without a package/data migration.

## Translations: Weblate (planned)

Set up volunteer translation on Weblate when community demand appears or before the next string-heavy feature. Planned work: contributor instructions and tone/glossary rules, locale-key parity CI, and automatic translation pull requests. Current fully maintained locales: en, de, fr, es, pt, ru; other upstream locales fall back to English for fork-added strings.

## Medication identity, dose versions, and duplicate review

**Implemented:** medication records include stable treatment identity and dose amount/unit fields; Stats separates medication versions; the editor confirms an effective date before starting a version; editing a dose prompts to start a new version, with an explicit option to save the correction on the existing version. Old taken/skip history and ledger remain with the previous version; new versions get independent history and supply. Settings offers a review-only possible-duplicate queue with reviewed/dismissed state. Records are never merged or deleted by this screen.

## Supply units, sprays, and priming

Supply counts have an explicit inventory unit, separate from dose strength. Scheduled use can consume multiple stock units. Inventory units include doses, tablets, capsules, mL, sprays, and puffs.

Spray/puff counts are **estimates**, not guaranteed device readings. Priming/test sprays and other waste are separately logged as stock-only ledger changes: they reduce estimated stock, but never create taken history, skipped/missed doses, or change adherence. A manual correction/device-counter reading updates the count through the supply editor and remains visible in the ledger. Refill and low-stock values follow the selected inventory unit. Free-form dose strength is never inferred to be a stock unit.

**Later follow-up:** device-specific counters, after-opening expiry, and priming recommendations are not part of basic stock tracking; user-entered device readings remain the source of truth when available.

## As-needed (PRN) medications — SHIPPED

As-needed meds are tracked separately from scheduled adherence:

- **No schedule / missed / alarm** — PRN meds never generate missed counts or reminders. `isPrnActiveOn(date)` uses `creationDate`; archiving still leaves them visible.
- **Each tap timestamped** — `prnUsages` is a `List<PrnUse(date,time,quantity=1)>`, never collapsed to one per day. `logPrnUse` appends, decrements stock by `supplyUnitsPerDose*qty`, logs `TAKEN`, and re-evaluates low-stock.
- **Stats frequency** — scheduled Stats exclude PRN; a dedicated `PrnStats` block counts uses per treatment per month instead of adherence.
- **Always-visible tray** — Home shows a collapsed "As needed" tray above the scheduled list (active PRN first, archived last). It is hidden (collapsed) even when populated; expand on demand. Each row shows today count, last use, limit warnings, and a Log button.
- **Versioning, supply, CSV, localization, roadmap** — PRN fields (`isPrn`, `prnMaxPerDay`, `prnMinIntervalHours`, `prnUsages`) round-trip through JSON + CSV (`is_prn`, `prn_max_per_day`, `prn_min_hours`, `prn_usages` as `YYYY-MM-DDTHH:mm[:qty]` pipe list), travel with dose versions (split by `effectiveDate`), and are included in the supply widget low-stock pass. Duplicate review excludes PRN.
