# Med Rx

**Never miss your medicine ever again.**

> **Med Rx** (Record eXchange) is a community fork of [Med by FeDeveloper95](https://github.com/FeDeveloper95/Med) (Apache 2.0), maintained by **nukirk** and not affiliated with or endorsed by the original author. It adds dose skipping with clinical reasons, pre-skips for future dates, a supply ledger with full traceability, idempotent dose logging so stock always matches the bottle, a doctor-friendly skipped-dose report, and the fix for upstream's crash when reducing doses on Android 14 and below ([upstream issue #19](https://github.com/FeDeveloper95/Med/issues/19)).
>
> Install it **alongside** the original Med (different app id, `com.nukirk.medrx`). Moving data is a two-step: export a backup/CSV from Med, import in Med Rx. Releases and the in-app updater point at this repository; bug reports go to [this repo's issues](https://github.com/nufuturepro/MedRX/issues).

## Highlights vs upstream

- **Skip doses with reasons** — eight clinical reason chips + optional note, from the alarm, notification, or the med card
- **Pre-skip future doses** — travel, fasting, procedures: plan the gap before it happens
- **Supply ledger** — every stock change (take, refund, refill, correction) recorded with its balance
- **Idempotent dose logging** — a dose confirmed twice (card + reminder/watch) counts once, so stock always matches the bottle
- **Doctor report** — CSV of every skipped dose with date, medication, reason, note
- **Stats** — skipped days are visually distinct from missed ones, per-reason breakdown

## Acknowledgements

Med Rx began as a community fork of Med by FeDeveloper95 (Apache 2.0). All credit for the original concept, design language, and core code belongs to FeDeveloper95 — please consider supporting the original project:

**➡ [github.com/FeDeveloper95/Med](https://github.com/FeDeveloper95/Med)**
