# Changelog — Clinical Agent Gateway (`agentgateway`)

## 1.2.0 — 2026-09-02

Dictation. The first feature release since 1.1.0; everything between was a fix.

A microphone button in the chat composer. Click to start, click again to stop, and the transcript
lands in the input box **as an editable draft**. The clinician reads it, corrects anything wrong,
and presses send themselves.

No schema change. One new privilege, three new settings, one new endpoint. Nothing about the
existing chat, audit or rollback paths changed — `AgentAuditFilter`, the operation log and the
confirmation gate are untouched.

### Added

- **`App: agentgateway.voice.use`.** Separate from `chat.use` on purpose: dictation sends audio to
  a GPU service, and an administrator must be able to switch that off — for a role or hospital-wide
  — without withdrawing the assistant from anyone. **Assign it, or no microphone appears.**
- **`agentgateway.sttServiceUrl`**, **`agentgateway.sttChannelSecret`**,
  **`agentgateway.sttTimeoutMillis`**. The secret must be a *different* value from
  `agentgateway.channelSecret`; the button stays hidden while it is empty or identical to it, and
  the dictation service refuses any request presenting the chat's secret.
- **`POST /module/agentgateway/transcribe.form`** — relays raw 16 kHz mono PCM to the dictation
  service and returns the transcript. Gated on `voice.use`, bounded at 30 seconds.
- **`purpose=stt` tokens, minted for audience `stt-service`**, never carrying a write capability.
  A chat token cannot drive the GPU and a dictation token cannot open a chat turn — the same
  separation `purpose` already provided between chat, rollback and internal reads, enforced a
  second time by an audience the recipient checks first. `AgentAuditFilter` still verifies only
  `clinical-agent-service`, so a dictation token can never authenticate an OpenMRS API call.

### Two safety rules, enforced by the build rather than by comments

`agent-voice.js` **never sends and never confirms**. A new `ModuleWiringTest` case strips comments
and then fails the build if the file so much as mentions `agentSend(`, `agentPost(` or
`agentConfirm(` in code. Speech recognition is imperfect and always will be; those two rules are
what keep that a usability property rather than a safety one, and they are one careless convenience
away from being lost.

### Fixed during this release, before it shipped

- **A privilege description over 250 characters is rejected at module start, not at build time.**
  `App: agentgateway.voice.use` packaged cleanly, deployed cleanly, then threw
  `ValidationException` on every retry and was never created — leaving a feature whose privilege
  did not exist, with nothing obviously wrong. Now checked by
  `everyPrivilegeDescriptionSurvivesOpenmrsValidation`.
  Privileges only: both columns are `TEXT`, so this is a validator rule and it does **not** apply
  to global properties — a 355-character `sttChannelSecret` description was written successfully in
  the same deploy that rejected the privilege. Verified against the database, not assumed.

### Deployment

Two steps the module cannot do for itself:

1. **Assign `App: agentgateway.voice.use`** to the roles that should dictate.
2. **Set `agentgateway.sttChannelSecret`** through **Administration → Settings → Agentgateway**, to
   the `STT_CHANNEL_SECRET` value from `server2-stack/.env`. Through the UI, not SQL: global
   properties are cached in memory and a direct `UPDATE` leaves the running instance serving the
   old value.

And one on the host: **`stt.hospital.lan` must resolve from inside `openmrs-app`.** On this
deployment the container resolves through the host's `systemd-resolved`, which reads Server 1's
`/etc/hosts` — which is how `agent.hospital.lan` already works. Add the matching line:

```
10.0.211.250  stt.hospital.lan
```

Verify with `docker exec openmrs-app getent hosts stt.hospital.lan`.

## 1.1.5 — 2026-08-31

Chat panel only. No schema change, no new privilege, no API change, no change to the security model.

Given its own version string rather than rebuilt in place, which is the lesson 1.1.4's own entry
below asks to be applied: a rebuild that changes behaviour and keeps the version is not findable
afterwards.

### Fixed

- **The chat forgot the conversation on a page reload.** `agentConversationId` was a plain
  javascript variable, so refreshing the page - or following a link and coming back - silently
  started a new conversation. Everything the assistant had established went with it: the patient,
  the field being changed, a half-finished create. The clinician sees the same panel and has no way
  to know it has forgotten them. The id now lives in `sessionStorage`, whose lifetime matches the
  agent service's own conversation buffer (`CONVERSATION_TTL_SECONDS`); `localStorage` would have
  outlived the state it points at and come back referring to nothing. Storage being unavailable
  (private browsing, locked-down policy) falls back to the previous behaviour rather than erroring.

### Added

- **A waiting indicator while the assistant is thinking.** The input greyed out for up to the
  model's full timeout with nothing on screen to say why. Three pulsing dots now appear in the
  message list where the reply will land, and are removed when it arrives.

  Deliberately *not* token streaming. The reply is one short paragraph, and the path is
  browser -> OpenMRS -> agent, whose middle hop (`HttpJsonClient`) reads the whole body before it
  returns; streaming would mean rebuilding the Java relay as a chunked proxy for a payload measured
  in sentences. The silence was the problem, not the absence of tokens. Honours
  `prefers-reduced-motion`.

## 1.1.4 (rebuilt) — Phase 22, after the dated 1.1.4 release below

`OperationTarget`'s rollback path parsing fixed; version string intentionally left at 1.1.4 since
this was an in-place correction during active deployment work, not a separate release, and the exact
date was not recorded at the time (bracketed by Phase 21 and Phase 23, which HANDOFF.md dates
2026-08-26). Covered by five new `OperationTargetTest` cases (module test count 65 → 69). See
`IMPLEMENTATION-LOG.md` Phase 22 for the defect and the fix. Noted here, after the fact, because a
rebuild that changes behaviour without changing the version string is exactly the kind of thing this
file exists to make findable — and the omission is itself worth learning from: **give a rebuild that
changes behaviour a new version string next time**, even a `-p1` style patch marker, so this file
does not need forensic reconstruction to stay accurate.

## 1.1.4 — 2026-08-24

Fixes one defect with an outsized effect. No schema change, no new privilege, no API change.

### Fixed

- **The stylesheet had never loaded, on any page.** `ui.includeCss(provider, file)` resolves to
  `/moduleResources/<provider>/styles/<file>`, while `ui.includeJavascript` resolves to
  `.../scripts/<file>`. The javascript was in `resources/scripts/` and loaded; `agentgateway.css` was
  in `resources/css/` and returned **404** — on the chat page, the patient-dashboard widget and the
  administrator's operation log alike. Verified on the running server before moving anything.

  The chat therefore worked perfectly while being completely unstyled, which was reported as "there is
  no visual distinction between the clinician's messages and the assistant's". There always was:
  `.agent-message-user` is right-aligned on a blue ground, `.agent-message-bot` on a light one. They
  were simply never served.

  `agentgateway.css` moved to `resources/styles/`, and `ModuleWiringTest` now asserts it is where
  `includeCss` looks — a 404 on a stylesheet is invisible until someone looks at the page.

## 1.1.3 — 2026-08-18

Audit-log readability. No behavioural change to the chat, the security model or the schema.

### Fixed

- **The operation log's "Utilisateur" column was blank on every row.** It read
  `User.getUsername()`, which is null for an account that authenticates by system id — and this
  installation's own `admin` account is one. The log had recorded who acted; it simply could not say
  so. Now falls back to the system id, then the uuid, the same order as
  `DelegatedTokenService.subjectFor` (1.1.1). Same root cause as the 1.1.1 mint failure, surfacing
  in a second place.
- **Dates rendered as epoch milliseconds** (`1787072923000`). Formatted server-side as
  `yyyy-MM-dd HH:mm:ss` in the server's zone. This page exists for an administrator deciding whether
  to reverse an operation; a number is not a date.

### Note on versioning

The parent POM version is unrelated to this module's version and must not be bumped with it. A
blanket search-and-replace over `pom.xml` hits both, and because `maven-parent-openmrs-module`
1.1.1 and 1.1.2 exist upstream, releases 1.1.1 and 1.1.2 were built against a parent that had been
changed by accident. The build and tests were unaffected, and the parent is pinned back to 1.1.0
here, with a comment in `pom.xml` so it does not recur.

## 1.1.2 — 2026-08-18

Bugfix release. No schema change, no new privilege, no new global property. **Requires the matching
agent-service build**: the agent must address calls through the new relay prefix, so deploy both or
neither.

### Fixed

- **No agent call to `fhir2` could succeed.** `fhir2` registers its own `AuthenticationFilter` on
  `/ws/fhir2/*`, and since `fhir2` is a bundled module it starts — and so registers that filter —
  before `agentgateway`. Module filters run in start order inside web.xml's single `ModuleFilter`,
  so fhir2 answered **401** before this module could authenticate the delegated clinician. The only
  FHIR path that worked was `/metadata`, which fhir2 exempts.

  Agent calls now arrive at `/module/agentgateway/relay` + the real path — a path fhir2 does not
  guard. The audit filter authenticates the clinician there and **forwards** to the real servlet.
  Module filters are not mapped for `FORWARD`, so fhir2's gate is not consulted again, while the
  real fhir2 servlet still serves the request and every OpenMRS privilege check still runs as that
  clinician.

  `/ws/fhir2/R4/...` is rewritten to `/ms/fhir2Servlet/...` on the forward, because fhir2's own
  `ForwardingFilter` normally does that rewrite and is itself skipped. `/ws/rest/v1/...` needs no
  rewrite — web.xml maps `/ws/*` to OpenMRS's DispatcherServlet, and servlet mappings do apply to a
  forward.

- **Before-state capture and rollback were broken the same way.** `DelegatedApiCaller` reaches
  OpenMRS over HTTP for both, so its calls hit the same 401. It now uses the relay prefix too. This
  would have surfaced as silently non-reversible writes (CA9) and failing rollbacks (CA10) the
  moment a write path became reachable.

### Notes for operators

- `OpenmrsFilter` **is** mapped for `FORWARD` and re-reads the user context from the HTTP session,
  which would have replaced the delegated context mid-forward. The filter therefore seeds the
  session with the delegated context before forwarding, and invalidates that session afterwards.
  Each agent call arrives on its own cookie-less connection, so the session is created, used by one
  request, and destroyed — it is never a session a browser could join. The invalidation is not
  tidiness: without it, every chat turn leaks a session.
- This changes a design position. §3 of the architecture states that `agentgateway` verifies and
  logs in-process rather than proxying that leg. That was only possible if its filter ran before the
  API's own authentication, which on this platform it cannot. The property that matters — every call
  runs under the clinician's own privileges, never a service account (CA7, ADR-9) — is unchanged.

## 1.1.1 — 2026-08-18

Bugfix release. No schema change, no new privilege, no new global property, no API change. A
1.1.0 deployment can be replaced in place: copy the `.omod` in, remove the old one, restart
OpenMRS.

### Fixed

- **The chat could not relay a turn for any account without a username.** Delegated tokens were
  minted from `User.getUsername()` alone, so every turn from such an account failed with
  `TokenException: Cannot mint a delegated token without a username` and never reached the agent
  service.

  OpenMRS does not require a username — an account may authenticate by `system_id` alone, which is
  what the Reference Application's user-creation flow produces. The subject is now the username,
  falling back to the system id, and an account with neither produces an error that names the
  account instead of a bare "without a username".

  This affected all three places a token is minted: a chat turn, an administrator's rollback, and
  the module's own read-only before-state call.

### Changed

- **A delegated token's user is now resolved by uuid, not by name.**
  `DelegatedAuthenticationScheme` looks the user up with `ContextDAO.getUserByUuid` using the
  `user_uuid` claim the token already carried, falling back to the subject when that claim is
  absent (as in tokens minted by 1.1.0).

  This is not a change in what is trusted — both values arrive inside the same signed token. It
  means the subject stays a human-readable label for the audit trail and never has to be
  interpreted as one kind of identifier or the other, which also removes the question of what
  should happen if a username and a system id collide across two accounts.

### Added

- `DelegatedSubjectTest` — 6 tests pinning the subject fallback, the refusal when an account has
  neither identifier, and the uuid/subject pair surviving into `DelegatedCredentials`.

### Notes for operators

- `agentgateway.signingPrivateKey` is written to the Tomcat log in cleartext whenever it is saved,
  because OpenMRS's `LoggingAdvice` logs every global-property save with its value. That is
  platform behaviour, not this module's. Treat `docker logs openmrs-app` as holding a credential.
- The audit log contains PHI by design — the prompt and the data it produced are the only way an
  administrator can review or reverse an operation.

## 1.1.0

Initial delivery of Phase 2: chat relay, delegated-token minting and verification, the audit
filter, `agentgateway_operation_log`, and admin review and rollback. Built and tested against a
mock OpenMRS; never exercised against the live instance.
