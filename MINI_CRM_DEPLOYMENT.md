# OAKsphere CallBridge Mini CRM - deployment notes

This Android project now contains a lightweight CRM client while preserving the original CallBridge polling/calling flow.

## What is included
- CRM login using the existing OAKsphere CRM session/authentication.
- Lead list and search.
- Add and edit lead: name, phone, company (stored in the CRM `client` field), notes.
- SIM Call button per lead using Android `ACTION_CALL`.
- Automatic post-call disposition screen after returning from the phone call.
- Connected / Not Connected dispositions fetched from the CRM API for the selected lead. No disposition list is hardcoded in Android.
- CRM-driven requirements for follow-up, interview details, closure reason and expected joining date.
- Call duration measured on the phone and saved through the CRM's existing disposition transaction.
- Call date/time stored by the CRM as the call record `created_at` timestamp.
- Per-lead call history read from the CRM.
- Original bridge polling endpoint remains `http://10.54.233.135:5000/api/pending-call` and completion endpoint remains `/api/call-complete`.

## Deploy in this order
1. Deploy the updated CRM backend first. No database migration is required.
2. Verify the backend origin responds at `/api/health`.
3. Open this `CallBridge` folder in Android Studio.
4. Let Gradle sync, then run on a physical Android phone or build an APK.
5. Install/update the app. The application id is unchanged; version is now 1.1 (versionCode 2).
6. In the CRM tab, enter the CRM backend/server origin (do not add `/api`), then sign in with a normal CRM user account.

## Required CRM permissions for a recruiter
The existing CRM role should allow the actions the recruiter needs, normally:
- `leads.view_own` (or `leads.view_all`)
- `leads.create`
- `leads.edit`
- `calls.log`

The app does not bypass CRM scope or permission checks.

## Today test checklist
1. Sign in on the Android app.
2. Confirm the recruiter's CRM leads appear.
3. Add one test lead and confirm it appears in the web CRM.
4. Edit Company/Notes and confirm the web CRM updates.
5. Tap Call, grant Phone permission, complete/cancel a short test call.
6. Confirm the Call Disposition screen opens automatically.
7. Confirm Connected and Not Connected choices match the web CRM.
8. Save a disposition and verify outcome, duration, timestamp, notes and follow-up/interview data in the web CRM.
9. Open History in Android and verify the saved call appears.
10. Open the Bridge tab and verify the existing `Check Call Request` behavior still works.

## Build command
From the project folder:

`./gradlew :app:assembleDebug`

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`
