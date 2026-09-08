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

The launcher builds the application and prints two newly generated local
demo passwords. Open:

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
is to supply the two password environment variables below, run "mvn clean verify",
then run "java -jar" with the application JAR produced under target.

Users and credentials
---------------------
dahnesh -> Dahnesh -> Requestor + Approver
shweta  -> Shweta  -> Requestor

Both users can request refunds. Dahnesh can approve or reject high-value
requests made by Shweta. Dahnesh cannot decide his own requests, even though he
also has the Approver role. Selecting a user on the login screen does not
authenticate them; their password is still required.

Optionally set these environment variables before launching to supply your own
local demo passwords:

    REFUNDS_DAHNESH_PASSWORD
    REFUNDS_SHWETA_PASSWORD

Do not store credentials in source, commit them or reuse real account passwords.
Sessions use Spring Security, server-assigned roles and CSRF protection.
HTTP is acceptable here only because this is a loopback-only local demo.

Suggested on-screen walkthrough
-------------------------------
1. Sign in as Dahnesh; point out the identity and both application roles.
2. Request the INR 2,500 or INR 10,000 full-order refund and show its immediate
   payment receipt.
3. Sign in as Shweta and request the INR 25,000 order. Show the pending result
   and confirm that the payment ledger is unchanged.
4. Sign in as Dahnesh, open Approvals, and approve Shweta's request. Show the
   new payment and both identities in the decision history.
5. Reset, request the INR 25,000 order as Dahnesh, and show that self-approval
   is blocked.
6. Repeat with rejection and show that no payment instruction is created.

"Sent to provider" means a mock payment instruction was recorded; it does not
mean a real settlement occurred.

Reset and repeat
----------------
The application stores demo state in process memory. Reset demo data from the
Workspace details, then Reset demo data and its confirmation dialog, or stop
and restart the process.

Reset restores the synthetic order set and clears refunds, mock payment records,
events and idempotency records. Data is shared by both application users, so a
reset affects both. Do not reset during another person's walkthrough.

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
