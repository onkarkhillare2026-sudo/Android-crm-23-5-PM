# CallBridge CRM (Android)

An Android application combining a lightweight recruiter CRM client with mobile SIM calling and call bridge integration.

## Key Features

1. **CRM Authentication**:
   - Secure login to OAKsphere CRM server.
   - Session preservation via cookie and bearer token management.

2. **Lead Management**:
   - Paginated and searchable lead directory.
   - Add new leads with company and notes.
   - Edit existing lead details.
   - Duplicate prevention acknowledgement option.

3. **Call & Disposition Workflow**:
   - Direct SIM calling via Android `ACTION_CALL`.
   - Automatic post-call transition to disposition modal with measured call duration.
   - Dynamic CRM-driven disposition options (Connected / Not Connected groups).
   - Validation for next follow-up date/time, interview schedules, closure reasons, and expected joining dates.

4. **Call History**:
   - Per-lead call logs with outcome tags, call duration, timestamps, and recruiter notes.

5. **Calling Bridge**:
   - Polling service to receive external call requests and initiate calls automatically.

## Tech Stack
- Kotlin 2.2
- Jetpack Compose with Material 3
- Android SDK 36, AGP 9.1.1, Gradle 9.3.1
