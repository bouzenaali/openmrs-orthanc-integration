# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# Project-specific rules — openmrs-orthanc-integration

This is a **live hospital system** (Neurosurgery, CHU Blida). The rules below are not
style preferences; each one exists because ignoring it has caused, or would cause, a real
failure. See `README.md` and `OHIF-Integration-Architecture.md` for the system itself.

## Layout

- `custom-imaging-openmrs/` — OpenMRS↔Orthanc imaging module (was a git submodule, now a
  plain directory; see the Git section below).
- `neuro-patientview/` — patient-view module, its own git repo.
- `chuschedules/` — recurring provider schedules (`Horaires récurrents`). Generates
  appointment blocks from weekly/monthly templates. Its `README.md` and
  `Recurring-Schedules-Design.md` carry the traps; **run `chuschedules/validate-xml.sh`
  before deploying it** — a malformed XML file in a module takes down the whole OpenMRS web
  context, not just that module.
- `modules/` — other OpenMRS custom modules built/maintained alongside this project:
  - `agentgateway/` — current source (`omod`/`api`), plus `agentgateway-module-backups/`
    for its built `.omod` history.
  - `Medreport-module/`, `openmrs-module-spa/`, `chublidatheme-omod-1.0.2.omod`.
- `services/clinical-agent-service/` — standalone FastAPI service (OpenMRS client, NLU,
  agent orchestration) that talks to this stack over the Docker network.
- `patches/`, `module-backups/`, `backup files/` — patch history and timestamped backups.
  These **are** tracked in git as of `d64d3d5`, including the `.omod` binaries under
  `patches/`. Archive only the build that matches what is deployed; intermediate builds from
  a working session are churn and should not be committed.
- `*-docker-compose.yml`, `ohif-app-config.js`, `orthanc-cors-proxy.conf` — the running
  stack's config; `openmrs-docker-compose.yml` binds an absolute host path
  (`/home/server/openmrs-persistent/java/cacerts`), so that directory must stay put.

Sibling directories outside this project root: `/home/server/report-generation-service`
(separate service, linked only via the Docker network name
`openmrs-orthanc-integration_default`, not by filesystem path) and
`/home/server/certificates` (TLS certs used by the stack).

## Working protocol

The maintainer is a domain expert, **not an infrastructure specialist**. For every command:

- State **WHAT / WHY / WHERE / EXPECTED / FAILURE / IF-IT-FAILS**.
- Classify it: **READ-ONLY / SAFE CHANGE / FILE CHANGE / DESTRUCTIVE / PRODUCTION-IMPACTING**.
- **One change at a time.** Propose, wait for approval, apply, verify. Never batch.
- **Read-only investigation before any write.** Always.
- **Never claim something works without demonstrating it.**
- Say **WHERE** a command runs — host vs. which container. That distinction is the single
  most common source of confusion here (a container name is not a hostname your browser
  knows; `orthanc-cors-proxy` resolves only inside Docker).

## Git — reads only

**Do not run** `git add`, `git commit`, `git checkout`, `git restore`, `git reset`,
`git clean`, or **any** `git submodule` command without explicit authorisation.

1. `openmrs-module-imaging` **was a submodule**, removed and replaced by the plain
   directory `custom-imaging-openmrs/`. A routine `git submodule update` could destroy
   that directory or resurrect the old pointer.
2. **Much of the operational config is not in git**, and `main` lags the working tree. Check
   `git ls-files <path>` before assuming a file is recoverable; `git checkout <file>` is
   **not** a reliable rollback path for anything untracked.

**Back up with timestamped copies as well**, into `backup files/`:

```bash
cp <file> "backup files/$(basename <file>).bak-$(date +%Y%m%d-%H%M%S)"
```

## Traps that have actually bitten

| Trap | Rule |
| --- | --- |
| **Single-file bind mounts** (`ohif-app-config.js`, `orthanc-cors-proxy.conf`) bind the **inode**. `sed -i` and most editors replace it, leaving the container serving stale content. | Truncate in place: `cat new > file`. Verify with `docker exec <c> cat <path>`. |
| **Internal vs published ports.** NPM connects over the Docker network. | Use the port *after* the arrow in `docker ps` — `ohif-viewer` is **80** not 3000, `orthanc-cors-proxy` is **80** not 8043. Wrong one gives 502. |
| **Backups inside the module source tree get packaged.** `omod/src/main/webapp/` is copied wholesale into the `.omod`. | Keep backups in `module-backups/`, never under `custom-imaging-openmrs/`. |
| **Three different Orthanc URLs**, easily confused. | `orthancBaseUrl` = `http://orthanc:8042` (server-side; changing it breaks uploads and syncing). `orthancProxyUrl` = `https://orthanc.hospital.lan` (browser links). `imaging.ohifBaseUrl` = `https://viewer.hospital.lan` (a **global property**, not on the Orthanc config page). |
| **Global properties are cached in memory.** A direct SQL `UPDATE` leaves the app serving the old value. | Set them through the OpenMRS UI. |
| **NPM's network attachment is not declarative.** Its compose file has no `networks:` section. | Recreating that container drops it and every proxy host 502s. Re-attach with `docker network connect openmrs-orthanc-integration_default nginx-proxy-manager-app-1`. |
| **`ERROR - ImagingActivator.started(29) Started Imaging`** looks like a failure. | It is a **success** message with the wrong log level. Ignore it. |
| **The server cannot resolve `*.hospital.lan`.** Its resolver is external; clients use the internal DNS. | Do **not** "fix" the server's DNS to make a test pass. Verify with `curl --resolve`, which overrides DNS for one request and changes nothing. A hosts entry exists for `viewer.hospital.lan` for local development only. |
| **Cached browser credentials mask a broken auth chain.** | Test viewers in a **private/incognito** window. Seeing images after typing a password is a **failure**, not a pass — it means the auth-injection proxy was bypassed. |

## Secrets

- Reference the MySQL password as `-p"$MYSQL_ROOT_PASSWORD"` **inside**
  `docker exec openmrs-mysql sh -c '...'` so it never enters host shell history. The
  "Using a password on the command line" warning is expected and harmless.
- `imaging_OrthancConfiguration` holds Orthanc credentials — `SELECT` only the columns you
  need, never `SELECT *`.
- Do not reproduce credentials in documentation, comments, or commit messages.
- **Known accepted risk (2026-09-07):** this project's GitHub repo is public and its
  current `origin/main` tip carries live-matching credentials (`.env`,
  `orthanc-docker-compose.yml`) — confirmed by hash comparison, not assumed. Deliberately
  left as-is for development; see `HANDOFF-2026-08-30.md` §4.3 for the finding and the
  mandatory pre-production checklist. Do not compound this by committing further secrets,
  and do not rotate, make the repo private, or rewrite git history without a fresh,
  explicit decision from the maintainer — this was already decided once and should not be
  silently redone or silently left undone.

## Evidence

`~/nginx-proxy-manager/data/logs/proxy-host-<n>_access.log` records every request a browser
actually made, with status codes. It is the authoritative evidence when a viewer
misbehaves, and it distinguishes a networking fault from a client-side rendering one.
Prefer it over inferring from what the browser appeared to do.
