# Yono Music 1.4.1 — updater progress regression

The reported exception is `IllegalArgumentException: current must not be NaN` from Compose progress semantics. The updater used an observable delegated state inside a deferred progress lambda. Its branch could be selected while total>0, then the lambda read Verifying/Ready state with counters reset to zero. 0/0 produces NaN; coerceIn does not repair it.

Fix: read one immutable UpdateUi snapshot, compute progress before creating the deferred callback, and use indeterminate progress for unknown/nonpositive totals. Shared progress calculation clamps counters safely without overflow. APK validation/signature/installer logic is unchanged.

Current checks: JVM boundary and captured-completion regressions plus Android tests reproducing the exact old exception with the actual ProgressBarRangeInfo constructor, and repeatedly rendering completed→unknown/verifying states through the actual application updater dialog with accessibility reads. Detailed results accompany the final build. These are state-transition fixtures, not a successful live download/install claim and not physical HyperOS testing.

Baseline 1.4.0 checks (156 Python, 106 JVM, 16 Android, collection upgrade and actual gestures) remain recorded in docs/HISTORICAL_1_4_0_TEST_REPORT.md. Python/audio/data engine code is unchanged by this hotfix and its suite was not rerun. No user data was cleared or application uninstalled.

Install the hotfix APK from the browser over the official application once: an old installed updater cannot acquire the code fix until installation completes. Keep the existing data and signing identity. Play Protect/physical Android16-HyperOS classification remains unverified.

Executed: **108 JVM tests passed**, **2 Android tests passed** on the API29 emulator. The UI test exercised 40 completed→unknown/verifying transitions with actual accessibility/semantics reads. Debug build and lint passed (0 errors). The fixture explicitly reproduces the old constructor exception; the corrected captured value remains finite. Evidence: docs/verification/v1.4.1.
