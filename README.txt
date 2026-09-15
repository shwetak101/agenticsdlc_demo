REFUNDOPS - HIGH-VALUE APPROVAL CANDIDATE
=========================================

Purpose
-------
A local, synthetic refund-operations application for an agentic software
engineering demonstration. Refunds of INR 10,000 or less are sent immediately
to a mock payment provider. Refunds above INR 10,000 wait for an independent
approver, and rejection sends no payment instruction. There is no production
payment connection and no AI model embedded in the application.

The browser interface is deliberately polished while the backend targets
Java 11 and Spring Boot 2.7.18. Spring Boot 2.7 is an intentionally outdated
baseline. Do not expose this application to the internet or use it for real
payments, customers or production workloads. The default bind is 127.0.0.1.

Start on Windows
----------------
Requirements: JDK 11 or later and Maven 3.6.3 or later on PATH.
JDK 21 can run this Java-11-compatible baseline.

From this directory in PowerShell:

    .\Start-Demo.ps1

The launcher builds the application and uses the public demo-only sign-ins
listed below. It enforces a loopback-only bind (127.0.0.1). Open:

    http://127.0.0.1:8081

To reuse an existing build:

    .\Start-Demo.ps1 -SkipBuild

To use a different local port:

    .\Start-Demo.ps1 -Port 8082

The candidate defaults to port 8081 and uses the
REFUNDOPS_APPROVAL_SESSION cookie name, so it can run beside the unchanged
legacy baseline on port 8080 without sharing application data or login state.

If your organisation restricts script execution, follow its approved policy.
Do not change machine-wide execution policy for this demo. A manual alternative
is to supply all three password environment variables below, run "mvn clean verify",
then run "java -jar" with the application JAR produced under target.

Users and credentials
---------------------
dahnesh -> Dahnesh -> Requestor + Approver + Demo operator
shweta  -> Shweta  -> Requestor
auditor -> Auditor -> Auditor (read-only)

Default username / password:
    dahnesh / dahnesh
    shweta  / shweta
    auditor / auditor

These publicly documented defaults are only for the local synthetic demo.
They are not secrets and must never protect real data or production systems.

Dahnesh and Shweta can request refunds. Dahnesh can approve or reject high-value
requests made by Shweta. Dahnesh cannot decide his own requests, even though he
also has the Approver role. Selecting a user on the login screen does not
authenticate them; their password is still required.

The Auditor is a synthetic demo identity, not a real user. It can read the
dashboard's orders, refunds, approval queue, payments and events, including the
Approvals history, Payment ledger and Activity screens. It cannot request,
approve or reject refunds, or reset data. Reset is a separate operational
permission: only Dahnesh has DEMO_OPERATOR. REQUESTOR and APPROVER do not grant
reset permission. The API enforces these boundaries even if browser controls
are bypassed; hiding or disabling buttons is not the security boundary.

Permission summary (all accounts must authenticate):
                         Dahnesh   Shweta   Auditor
Read shared records      Yes       Yes      Yes
Request refund           Yes       Yes      No
Approve/reject others    Yes       No       No
Reset shared demo        Yes       No       No

Optionally set these environment variables before launching to supply your own
local demo passwords:

    REFUNDS_DAHNESH_PASSWORD
    REFUNDS_SHWETA_PASSWORD
    REFUNDS_AUDITOR_PASSWORD

Environment-variable overrides take precedence over the demo defaults.
Direct JAR startup requires all three variables to be non-blank and has no
password defaults. Start-Demo.ps1 supplies the documented defaults only for
its loopback-only launch and removes defaults it supplied when it exits.
The launcher logs usernames, roles and whether a default or override is used,
never password values. Use only on a private local demo machine.
Never commit override passwords or reuse real account passwords.
Sessions use Spring Security, server-assigned roles and CSRF protection.
HTTP is acceptable here only because this is a loopback-only local demo.

Suggested on-screen walkthrough
-------------------------------
1. Sign in as Dahnesh; point out the Requestor, Approver and Demo operator roles.
2. Request the INR 2,500 or INR 10,000 full-order refund and show its immediate
   payment receipt.
3. Sign in as Shweta and request the INR 25,000 order. Show the pending result
   and confirm that the payment ledger is unchanged.
4. Sign in as Dahnesh, open Approvals, and approve Shweta's request. Show the
   new payment and both identities in the decision history.
5. Reset, request the INR 25,000 order as Dahnesh, and show that self-approval
   is blocked.
6. Repeat with rejection and show that no payment instruction is created.
7. Sign in as Auditor. Browse orders, Approvals history, Payment ledger and
   Activity. Request controls are unavailable; approval decisions and reset
   are not offered. Sign in as Shweta to show that she also cannot reset.

"Sent to provider" means a mock payment instruction was recorded; it does not
mean a real settlement occurred.

Reset and repeat
----------------
The application stores demo state in process memory. As Dahnesh (DEMO_OPERATOR),
open Workspace details, then Reset demo data and its confirmation dialog, or
stop and restart the process. Shweta and Auditor receive HTTP 403 when posting
to /api/demo/reset, even with a valid CSRF token.

Reset restores the synthetic order set and clears refunds, mock payment records,
events and idempotency records. Data is shared by all three application users,
so a reset affects everyone. Do not reset during another person's walkthrough.

The reset is not a Git reset and does not affect code, repositories or external
systems. No database, cloud account or payment service needs provisioning.

Implementation boundaries
--------------------------
- Spring MVC REST endpoints and a same-origin HTML/CSS/JavaScript interface.
- No CDN, external fonts, analytics or third-party runtime assets.
- Full-order refunds only; one refund decision per eligible order.
- Amounts and requester identity come from the server.
- Repeated identical submissions with one idempotency key reuse the original
  result; conflicting requests do not send a second payment.
- Amounts above INR 10,000 enter PENDING_APPROVAL without a payment.
- Only an authenticated Approver other than the requester may decide a pending
  refund. Approval sends one payment; rejection sends none.
- AUDITOR is read-only. POST /api/refunds requires REQUESTOR, decision endpoints
  require APPROVER, and POST /api/demo/reset requires DEMO_OPERATOR. Every write
  retains Spring Security's CSRF protection.
- Atomic in-memory processing prevents concurrent duplicate decisions and sends.
- Mock provider records are application evidence, not tamper-proof audit logs.
- The frontend does not substitute fabricated data if an API request fails.
- Customer-facing screens use business wording ("Payment gateway", "Automatic
  processing"). Simulation and technical details are available under Workspace
  details and in this document; the payment integration is still a stub.

Source navigation
-----------------
pom.xml                     Legacy dependencies and Java source baseline
src\main\java                Authentication, endpoints, domain and mock provider
src\main\resources\static    Browser interface
src\main\resources\application.properties  Local runtime configuration
src\test                    Automated backend checks
Start-Demo.ps1               Build and local launch

Build
-----
    mvn clean verify

Still intentionally not implemented
-----------------------------------
Persistence, production payment connectivity, modernisation, release gates and
AI-assisted decisions remain outside this local demonstration.
