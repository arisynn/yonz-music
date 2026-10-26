# API29 software-emulator audit

Final Home screenshots cover light/dark at320dp and font scale1.5. The selected library contains original QA audio and cover fixtures, not bundled demo songs in a fresh installation. Several existing QA covers are identical, so two equal Home frames alone do not prove rotation. HomeCarouselTest separately checks actual rendered pixels with two distinct original colour covers, no click target, and reduced motion.

`before-priority-*` records the first artwork audit, before prioritising known covers over songs without art. `final-queue-shuffle.png` shows native playback order1→0→3 with the current and upcoming labels. Images were visually inspected; no physical HyperOS test is implied.
