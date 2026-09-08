REFUNDOPS - LEGACY REFUND DEMONSTRATION
=====================================

Purpose
-------
A local, synthetic refund-operations application for an agentic software
engineering demonstration. This is the BEFORE state: every valid full-order
refund is immediately sent to a mock payment provider, regardless of value.
There is no approval workflow, no production payment connection and no AI model
embedded in the application.

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

    http://127.0.0.1:8080

To reuse an existing build:

    .\Start-Demo.ps1 -SkipBuild

To use a different local port:

    .\Start-Demo.ps1 -Port 8081

If your organisation restricts script execution, follow its approved policy.
Do not change machine-wide execution policy for this demo. A manual alternative
is to supply the two password environment variables below, run "mvn clean verify",
then run "java -jar" with the application JAR produced under target.

Users and credentials
---------------------
dahnesh -> Dahnesh -> Requestor + Approver
shweta  -> Shweta  -> Requestor

Default username / password:
    dahnesh / dahnesh
    shweta  / shweta

These publicly documented defaults are only for the local synthetic demo.
They are not secrets and must never protect real data or production systems.

Both users can request refunds. The Approver role is assigned server-side but
has no approval action in this legacy version. Selecting a user on the login
screen does not authenticate them; their password is still required.

Optionally set these environment variables before launching to supply your own
local demo passwords:

    REFUNDS_DAHNESH_PASSWORD
    REFUNDS_SHWETA_PASSWORD

Environment-variable overrides take precedence over the demo defaults.
Never commit override passwords or reuse real account passwords.
Sessions use Spring Security, server-assigned roles and CSRF protection.
HTTP is acceptable here only because this is a loopback-only local demo.

Suggested on-screen walkthrough
-------------------------------
1. Sign in as Dahnesh; point out the identity and both application roles.
2. Browse the synthetic orders and request a low-value full-order refund.
3. Show the resulting "Sent to provider" receipt and payment ledger entry.
4. Request a high-value refund. It takes the SAME immediate processing path.
5. Show that no independent approval was requested.
6. Sign out and sign in as Shweta; demonstrate the Requestor-only identity.
7. Use the activity view to show server-recorded actors and payment events.

Amounts above INR 10,000 can be highlighted for the presenter, but that is
informational only. It does not represent an implemented approval threshold.
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
- Full-order refunds only; one refund per eligible order.
- Amounts and requester identity come from the server.
- Repeated identical submissions with one idempotency key reuse the original
  result; conflicting requests do not send a second payment.
- Atomic in-memory processing prevents concurrent duplicate sends.
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

Future stages intentionally not implemented
------------------------------------------
High-value approval rules, an approval queue, independent-approver enforcement,
modernisation, pull-request automation, release gates and GitHub publishing are
separate steps. This repository contains only the legacy baseline.
