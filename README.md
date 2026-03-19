# Voice Note App (Driving-Oriented Wizard)

Android app for fast spoken note capture while driving, with local Room storage and no cloud API dependency.

## Implemented behavior
- **Voice-first home screen** (`Drive Capture`) with one large `Leave a Note` button.
- **Guided wizard flow** (TextToSpeech prompts + SpeechRecognizer listen):
  1. `What is the job name?`
  2. `What is the subject?`
  3. `What is the note?`
  4. `Would you like to add any tags?` with suggestions
  5. Summary + `Any edits?`
  6. If yes: `Edit Job Name, Edit Title, Edit Note, or No Edits?` (multi-edit loop)
- **Auto job resolution/creation**:
  - Matches existing jobs conservatively from spoken text
  - Creates new job if no good match
- **Auto title capture**:
  - Subject step fills title
  - Parser also supports markers like `title`, `heading`, `regarding`, `in reference to`, `subject`
- **Tag suggestions**:
  - Hybrid of construction/worksite dictionary + extracted note keywords
  - Tag reply supports natural language, numbered picks, or skip
- **Interruption safety**:
  - Saves wizard progress as unsaved voice drafts (multiple drafts supported)
  - Unsaved Drafts screen allows resume/delete
- **Reminder notifications** for unsaved drafts:
  - Every 30 minutes
  - Quiet hours: 10:00 PM to 6:00 AM
  - Tap notification to deep-link into Unsaved Drafts
- Existing manual screens still available:
  - Job list/detail, note editor
  - `Edit by Voice` button on existing note editor

## Tech stack
- Kotlin
- Jetpack Compose + Material 3
- Android SpeechRecognizer + RecognizerIntent
- Android TextToSpeech
- Room (SQLite) with migrations
- WorkManager (periodic unsaved-draft reminders)

## Data model notes
- `jobs`, `notes`, `drafts` (legacy editor draft per job) remain
- New table: `voice_drafts` for wizard session drafts (multiple, resumable)
- DB version: `3`
- Migrations:
  - `MIGRATION_1_2`: note tags/pin + legacy drafts
  - `MIGRATION_2_3`: adds `voice_drafts`

## Build and run
1. Open project in Android Studio.
2. Use **JDK 17** for Gradle (`Settings > Build Tools > Gradle > Gradle JDK`).
3. Ensure Android SDK path is valid in `local.properties`.
4. Run app on Android 8.0+ device/emulator.

CLI build example:
```powershell
$env:JAVA_HOME='path-to-jdk17'
.\gradlew.bat assembleDebug
```

## Voice testing checklist
1. Open app -> confirm `Drive Capture` home.
2. Tap `Leave a Note`.
3. Speak job, subject, note, and tags.
4. At `Any edits?`, say `yes` -> verify edit options prompt.
5. Let session time out/silence to verify draft-safe behavior.
6. Open `Unsaved Notes` and resume draft.
7. Confirm reminder notification appears when drafts remain.
8. Tap notification and verify deep-link to Unsaved Drafts screen.

## Offline and recognizer notes
- App prefers offline recognition (`EXTRA_PREFER_OFFLINE=true`).
- Actual offline quality depends on device speech services and installed offline language packs.
- If recognizer service is missing, app shows fallback errors and manual screens remain available.

## Known constraints
- Android Auto integration is not included in this phase; this release is optimized for foreground phone use while driving.

## ?? Local-First Repo Policy
This repository treats the local machine as the single source of truth. All changes are made and validated locally, then synced to GitHub using the provided sync script. GitHub is used only as a mirror/backup   never as the authoritative source.

## Test edit
test edit
