# tracker-patches

ReVanced patch bundle (`.rvp`, ReVanced Patcher 22) for Universal Patch Manager 2.x
(needs its "ReVanced v22" runtime plugin). Phase 0: one universal patch, **Tracker report**,
which changes nothing and logs `TPR|1|{...}` lines (package, components, startup initialisers,
permissions, tracker code, risky SDKs).

Add this repository's link as a patch bundle in the Manager. Every push to `main` builds
and publishes a release with the `.rvp` attached.
