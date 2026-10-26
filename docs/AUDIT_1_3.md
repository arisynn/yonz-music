# Audit source 1.3

Baseline 528fa935 (1.2.0 Preview 3, Dev code 5), clean working tree before implementation. Original-format audio, branding, native navigation/player, selected SAF sources and signing must be retained.

Findings:
- Repository keys new file identities by document URI. Relocation matching hashes only old `download_jobs` rows; newer engine jobs live in private JSON and never seed that identity index. Renamed/moved ordinary imports have no persistent hash evidence.
- Scanner commits each source independently, reports many failures as one generic unavailable state, and old player errors label possible damage without classifying URI/permission failures.
- WebM cannot embed the existing Mutagen tag set. Database art/content URIs survive a same-install rescan but are not a portable metadata sidecar. Earlier core plan metadata is not reconciled into existing rows.
- Current artwork cache always decodes for 640px, keys only URIs, and caches failures indefinitely. Same-URI repair can remain blank.
- New engine work is persistent, but the UI exposes mostly only the last plan. Item cancellation and aggregate persistent download history need one authoritative facade, retaining legacy jobs for recovery.
- Home contains a large hero, Continue listening, duplicate shortcuts and unknown-album highlights. Recent history stores only a last event, not reliable play counts. It cannot support invented full-play statistics.

Implementation priority: additive migration/reconciliation; portable metadata; persisted download plans and item controls; qualified local listening and compact Home; reduced-motion-aware transitions. No new screenshot was visible in this message's attachments, so source and actual emulator captures are used for the Home audit.
