# Store metadata

`metadata/android/en-US/` is the [fastlane metadata layout](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
that F-Droid and IzzyOnDroid read for the store listing. Nothing in this directory is
compiled into the app, and there is no fastlane installation — the layout is a convention,
not a tool dependency.

- `short_description.txt` — one line, **80 characters maximum**.
- `full_description.txt` — the listing body. Keep it accurate about what leaves the device;
  it is the first thing an F-Droid reviewer reads against the code.
- `changelogs/<versionCode>.txt` — shown for that release. The name must be the **versionCode**
  (`version.properties`), not the versionName: `2026.08.4` → `20260804`. Nothing writes these
  automatically; add one before dispatching a release, so it lands in the tagged commit
  (see `docs/RELEASING.md`). Note an `-rcN` and the stable it is promoted to share a
  versionCode, so they also share this file.
- `images/phoneScreenshots/` and `images/tenInchScreenshots/` — sorted by filename, hence the
  numeric prefixes. PNG only.

These directories are the **only** copy of the screenshots they hold: the README links
straight into them rather than keeping its own duplicates, which is why the numeric prefixes
show up in `README.md`. `docs/screenshots/` keeps what the store listing does not carry — the
animated GIFs, and shots that are useful in the README but not worth a store slot.

So when the UI changes: replace the file in whichever directory already holds it, and do not
add a second copy elsewhere. Adding a screenshot to the store listing means moving it here and
repointing the README at the new path, not copying it. Every file under `images/` was once
duplicated in `docs/screenshots/`, which cost 3.2 MB and drifted silently the moment one copy
was refreshed and the other was not.
