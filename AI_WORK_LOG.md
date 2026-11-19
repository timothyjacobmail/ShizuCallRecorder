# AI work log

## 2026-11-19

- GitHub `main` and the local branch matched at `c903476`. The untracked `audit/` directory contains local review notes and an unpacked Gradle distribution. Keep it out of Git.
- Phone package `com.kitsumed.shizucallrecorder` was installed with version code 20. Debug and release signing need to be checked before any update.
- Debug build and JVM unit tests passed using the local Gradle 9.8.2 distribution. The repo has a single JVM test source file covering recording expiry.
- Review document-provider failures and cancellation around per-contact recording expiry. The JVM tests cover expiry decisions. Call recording and Shizuku service behavior need target-device testing.
- Require a usable modification timestamp before matching an expiring recording to a document. Preserve coroutine cancellation when scanning the queue. A provider that cannot supply a timestamp cannot establish that a document is safe to delete.
- The debug build and existing JVM unit tests passed after the expiry change.
- Installed version code 20 in place after matching the signing certificate. The installed hash matched the build and the launcher stayed running.
