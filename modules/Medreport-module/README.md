# Medreport — OpenMRS Module

Medical report generation for the neurosurgery department, CHU Blida.

Two capabilities, one module:

1. **Per-image observation reports** — a radiologist or surgeon writes free-text observations
   while viewing a DICOM study; the text is rendered as-is into a `.docx`, stored as an
   OpenMRS Complex Obs, versioned, audited and soft-deleted.
2. **Personalised patient reports** — one click on the patient dashboard opens a
   personalisation window where the clinician picks the language, format, template, and
   exactly which sets and fields of clinical data to include, previews the result, and
   downloads it.

**Target platform:** OpenMRS Platform 2.5.9 / Reference Application 2.12.2
**Module ID:** `medreport` · **Package:** `org.openmrs.module.medreport` · **Version:** `1.0.0`

---

## 1. Where things live

```
Medreport-module/
├── api/    domain model, persistence, services, the contributor SPI, the render client
├── omod/   module descriptor, REST controllers, pages, fragments, assets
└── scripts/ i18n sources + escape_props.py (see §8)

report-generation-service/   ← sibling checkout: the FastAPI renderer (its own README)
```

`api` has no dependency on `omod`. Neither has a dependency on `patientview` or `imaging` —
see §4.

---

## 2. Architecture at a glance

```
patient dashboard button ─┐
                          ├─► medreport REST ─► PatientReportService ─┐
imaging module tab ───────┘                 └─► ImageReportService  ──┤
                                                                      │
                              DataSourceRegistry ◄── manifests from   │
                              (what data exists)     any module       │
                                                                      ▼
                                              Report Generation Service (HTTP)
                                              docx / pdf / html / odt · fr / en / ar
```

The renderer is a separate service on the private container network. It owns **presentation**
only; medreport owns **selection, meaning and access control**. That split is what keeps
privilege enforcement in exactly one place.

---

## 3. Security model

### Privileges

| Privilege | Gates |
| --- | --- |
| `App: medreport.imaging.view` | Reading any imaging report and downloading its document |
| `App: medreport.imaging.manage` | Creating a report; editing/removing **one's own** |
| `App: medreport.admin` | Browsing a report's full version history; restoring a removed report |
| `App: medreport.dashboardGenerate` | Generating a patient report from the dashboard |

All four are `App:`-prefixed deliberately. **The Reference Application auto-grants every plain
(non-`App:`/`Task:`) privilege to every role**, so a plain privilege restricts nothing on this
deployment. `View Medical Reports` / `Manage Medical Reports` also exist, for OpenMRS
distributions that do not follow that convention — they are belt-and-suspenders, never the
boundary. `ModuleWiringTest.everyPrivilegeThatGatesSomethingIsAppPrefixed` enforces this.

**Suggested assignment:** nurses → `imaging.view` + `dashboardGenerate`; surgeons and
radiologists → additionally `imaging.manage`; department head / system administrator →
additionally `medreport.admin`.

### `Rapport_3`'s "Add/Edit reports" — checked, no overlap

`Rapport 3.txt` assigns *Add reports*, *Add reports objects*, *Edit reports*, *Edit reports
objects*. Those are **OpenMRS core privileges over the legacy `Report`/`ReportObject` domain
objects** — report *definitions* (cohort queries, data exports) — not clinical documents.
They are also plain, unprefixed, hence auto-granted to every role and gating nothing. They
neither cover nor conflict with `medreport.*`, so the two sets are not redundant and no
consolidation is needed.

### Enforcement is in two layers, always (RP6)

- **UI** — a button the user cannot use is hidden, or rendered disabled with a `title=`
  explaining why (a disabled Edit on someone else's report reads better than a missing one).
- **API** — every REST handler opens with an explicit `MedreportPrivileges.require*`, and the
  service re-checks independently. `ModuleWiringTest.everyRestEndpointEnforcesAPrivilegeItself`
  scans the source at build time so a new endpoint cannot skip it.

The UI check is a convenience. **The server check is the guard**, and the tests treat a
hand-crafted request as the threat model, not a curiosity.

### Ownership

Holding `imaging.manage` lets a user *write* reports; it never lets them touch someone
else's. Update and remove additionally require authorship, decided from the authenticated
user — never from the request. Refused attempts are audited.

### Confidentiality

- Report documents leave the server with `Cache-Control: no-store, private`,
  `X-Content-Type-Options: nosniff`, and (for previews) a restrictive CSP.
- Rendered artifacts are transient: the renderer deletes them after a short TTL, and
  medreport discards them as soon as the Complex Obs is persisted. In Docker the output
  directory is a **tmpfs**, so rendered PHI never touches the host disk.
- The renderer is protected by a shared token compared in constant time, and must not be
  published on the hospital LAN.
- The 403 body is deliberately generic; which privilege was missing goes to the audit log.
- A user who cannot view a datum cannot include it in a report — enforced at both the
  catalogue and the generation step (§5).

---

## 4. Independence from `patientview` and `imaging`

The module requires neither. `config.xml` declares them `aware_of_modules`, not
`require_modules`, and `ModuleWiringTest.contributingModulesAreAwareOfNotRequired` keeps it
that way.

### How a module contributes its data

A contributing module ships **one JSON file**, `medreport-datasource.json`, on its classpath:

```jsonc
{
  "id": "patientview",
  "requiredPrivilege": "App: patientview.neurosurgeryDashboard",
  "sections": [{
    "id": "patientview.diagnosis",
    "label": { "fr": "Diagnostic neurochirurgical", "en": "…", "ar": "…" },
    "repeating": true,
    "recordTitleKey": "dateCreated",
    "source": {
      "serviceClass": "org.openmrs.module.patientview.api.PatientviewService",
      "method": "getNeurosurgicalDiagnoses",
      "args": ["patient"]
    },
    "fields": [
      { "id": "diagnosis", "type": "text", "emphasis": true,
        "label": { "fr": "Diagnostic", "en": "Diagnosis", "ar": "التشخيص" } }
    ]
  }]
}
```

medreport scans every started module for that resource, and calls the named service method
reflectively through `Context.getService(...)`. The method must take a `Patient` (optionally
plus an `int` limit) and return `Map<String,Object>` or `List<Map<String,Object>>`.

**Why a manifest and not a Java interface.** Making `patientview` implement a medreport
interface would force it to depend on medreport — inverting the intended direction and making
the clinical record module unable to start without the reporting module. With a manifest the
contract is JDK types plus one file; the contributor works unchanged whether medreport is
installed or not. Contributing cost `patientview` exactly one resource file and **zero lines
of code**.

Because the call goes through the service registry, the contributor's own `@Authorized`
advice still runs — medreport can never be a way around a contributor's access rules.

A typed alternative, `ClinicalDataContributor`, exists for contributors that must compute
their catalogue dynamically. medreport's own demographics contribution uses it, so the
interface is exercised by the module that defines it.

**Failure isolation:** a malformed manifest, a missing service, or a throwing contributor is
logged and skipped. The rest of the report still renders.

### The `imaging` module's change

Two edits, both additive:

- `SeriesPageController` — publishes the study identifiers it already holds, plus
  `ModuleFactory.isModuleStarted("medreport")`.
- `series.gsp` — includes `medreport`'s `imageReports` fragment, guarded by that flag, and
  offers a per-series "write a report" action.

No report logic, no dependency, and the page renders exactly as before when medreport is
absent.

---

## 5. Use case 2 — the personalisation window

Opened by the dashboard button (`medreport_extension.json`, privilege-gated).

- **Language** fr (default) / en / ar, **format** PDF / DOCX / ODT / HTML, **template** from
  the renderer's registry.
- **Hierarchical selection** — sets with tri-state checkboxes. Ticking a set includes all of
  it; unfolding it exposes each field. Filter, select-all/none, expand/collapse, and a live
  summary of what is selected.
- **Presentation options** — show empty fields, table of contents, signature block, page
  numbers, confidentiality notice, include imaging observations.
- **Preview before download**, in a modal, every time.
- **Preferences persist per user** (`medreport_user_preference`), so the second report starts
  from the first one's choices.

### Privilege coherence

Two independent filters, same rule:

1. `getCatalog` omits sets and fields the user lacks the privilege for — **absent, not
   disabled**, so their existence is not disclosed.
2. `generate` re-applies the identical filter to whatever selection actually arrives.

So a preference saved before a role change, or an id posted straight to the REST endpoint,
gains the caller nothing. `ReportPrivilegeCoherenceTest` asserts both, including the forged
request.

---

## 6. Use case 1 — imaging observation reports

### Data model

| Table | Purpose |
| --- | --- |
| `medreport_image_report` | stable identity: patient, author, soft-delete flags |
| `medreport_image_report_version` | one immutable row per state transition, chained |
| `medreport_report_image` | **report ↔ image many-to-many** (RP9) |
| `medreport_operation_log` | append-only audit trail (RP7) |
| `medreport_user_preference` | per-user personalisation choices |

No report content lives on the identity row: text, document and covered images all belong to
a version, so an update archives instead of overwriting.

**The many-to-many is real.** `medreport_report_image` carries both `report_id` and
`version_id`: `report_id` answers "which reports cover this study" (indexed both ways), while
`version_id` keeps each archived version's image set immutable, so editing coverage does not
rewrite history. Neither entity holds a foreign key to the other. Images are referenced by
Orthanc/DICOM UID rather than by a foreign key into the imaging module's schema — Orthanc
stays the source of truth.

### Lifecycle

| Action | Who | Effect |
| --- | --- | --- |
| Create | `manage` | v1, `is_current = true`, rendered `.docx` as Complex Obs |
| Update | `manage` **+ author** | new version, previous archived, chain extended |
| Remove | `manage` **+ author** | `voided = true` + a `REMOVE` version; nothing deleted |
| Restore | `medreport.admin` | clears the soft delete, appends a `RESTORE` version |
| History | `medreport.admin` | full chain — the author does **not** see it |

Exactly one version is current at any time (`clearCurrentFlag` immediately precedes the new
row). Non-admins are always shown that one well-defined version. The DAO exposes **no delete
method at all**, and the Complex Obs handler refuses `purgeComplexData` — the operations that
would violate RP5/RP8 are not expressible.

Rendering is best-effort: the clinician's text is the clinical record and is committed before
the document is rendered, so a renderer outage cannot lose it. The failure is logged and
audited.

### Audit (RP7)

Every create, update, remove, restore, history view and generation is written to
`medreport_operation_log` — who, what, which report, when, from which IP, and whether it
succeeded. **Refused attempts are logged too.** Log writes run in `PROPAGATION_REQUIRES_NEW`
so they survive a rollback of the business transaction — the cases most worth recording are
exactly the ones where the outer transaction rolls back.

---

## 7. Configuration

| Global property | Default | Notes |
| --- | --- | --- |
| `medreport.renderService.baseUrl` | `http://medreport-rgs:8300` | private network only |
| `medreport.renderService.token` | *(empty)* | **required**; must equal the service's `MEDREPORT_RGS_TOKEN` |
| `medreport.renderService.timeoutSeconds` | `90` | |
| `medreport.defaultLanguage` | `fr` | |
| `medreport.defaultTemplate` | `chu_blida_neuro` | |
| `medreport.imaging.template` | `chu_blida_imaging` | |
| `medreport.facilityName` / `.departmentName` | CHU de Blida / Service de Neurochirurgie | |
| `medreport.imaging.conceptUuid` / `.encounterTypeUuid` | fixed UUIDs | created automatically on first start |

The token ships **empty on purpose** — a default would be a shared secret published in source
control and identical on every install (`ModuleWiringTest.theRenderServiceTokenGlobalPropertyShipsEmpty`).

The activator is idempotent: it registers the Complex Obs handler, creates the
`ConceptComplex` and the encounter type if absent, and rescans data sources (module start
order is not fixed).

**Admin screen:** `medreport/settings.page` — renderer health, PDF availability, the template
registry, which modules are contributing, and the recent audit trail.

### Adding a report template

No code, no restart, two routes:

- a **JSON style profile** in the renderer's `templates/profiles/` (≈15 lines: colours, fonts,
  header/footer, layout switches), or
- an **uploaded `.docx` letterhead** with jinja2 placeholders, for full typographic control.

Both appear in the personalisation window immediately.

---

## 8. Internationalisation

French (default), English, Arabic — including **right-to-left** layout in both DOCX and HTML,
with the complex-script font set explicitly so Arabic does not fall back to a font without
coverage.

Bundles are ASCII with `\uXXXX` escapes, because `java.util.Properties` reads `.properties` as
ISO-8859-1 and raw UTF-8 renders as mojibake. **Edit `scripts/i18n-src/*.properties` and run
`python scripts/escape_props.py`** — never hand-edit the escapes.
`ModuleWiringTest.messageBundlesAreAsciiEscaped` and `allThreeLanguageBundlesDeclareTheSameKeys`
enforce both.

Clinical labels are *not* in these bundles: they come from each contributor's manifest, so a
contributing module ships its own vocabulary rather than pushing translations into this
module.

---

## 9. Build & test

```bash
cd Medreport-module && mvn clean install
```

```bash
cd report-generation-service && ./.venv/Scripts/python -m pytest tests -q
```

**129 tests, all passing** (counts verified by running the suites, not asserted from memory):

| Suite | Count | Covers |
| --- | --- | --- |
| `report-generation-service` — `test_render.py` | 27 | auth, templates, DOCX content, 3 languages + direction, Arabic RTL/complex-script font, empty-field handling, preview availability, both v1 payloads, path traversal, TTL sweep, filename sanitisation, cache headers |
| `report-generation-service` — `test_conversion.py` | 15 | LibreOffice command construction, private profile, output discovery, failure/timeout/silent-non-production, PDF end-to-end, preview fallback |
| `report-generation-service` — `test_compat.py` | 4 | Python 3.11 floor; self-checking f-string-backslash detector |
| `medreport api` — `ImageReportServiceRulesTest` | 26 | RP1–RP9 individually |
| `medreport api` — `ReportPrivilegeCoherenceTest` | 16 | catalogue filtering, forged-selection rejection, value formatting |
| `medreport api` — `DataSourceManifestTest` | 12 | manifest schema against a copy of the real one |
| `medreport omod` — `ModuleWiringTest` | 23 | packaging, privilege-enforcement scan, **JS-escaping scan**, Liquibase, GSP braces, i18n, CSS namespace |
| `patientview api` — `MedreportDatasourceManifestTest` | 6 | manifest ↔ service signatures ↔ DAO map keys; absence of a medreport dependency |

`make_samples.py` renders a representative report in every language/format into `samples/`.

### Two regressions found in production testing, now guarded

Both had the same character: valid on the machine that wrote them, fatal on the machine that
ran them, and silent rather than loud.

1. **A backslash inside an f-string expression** in `layout_html.py`. Legal on Python 3.12
   (PEP 701), a `SyntaxError` on 3.11 — and it broke *import*, so the entire service and its
   whole test suite went down from one line. Guarded by `test_compat.py`, whose detector is
   itself tested against the offending line.
2. **Unescaped message interpolation into JavaScript** in both GSP templates. French messages
   contain apostrophes (`d'informations`, `l'auteur`); inside a single-quoted JS string one
   apostrophe ends the string, which is a parse error for the whole `<script>` block. The
   result was a completely inert page: no data tree, dead buttons, and nothing in the UI to
   say why. Every interpolation now goes through `ui.escapeJs(...)`, enforced by
   `ModuleWiringTest.everyValueInterpolatedIntoJavaScriptIsEscaped`.

### Still not executed anywhere

**LibreOffice-backed PDF/ODT rendering.** Everything *around* the conversion is tested against
a faked `subprocess.run` — command construction, profile isolation, output discovery, and
every failure path — and the graceful-degradation path (PDF requested, DOCX returned with a
warning, HTML preview substituted) is verified. The conversion itself has never run in this
project. Close the gap on any machine with Docker:

```bash
cd report-generation-service && docker compose up --build -d && curl -fsS localhost:8300/health
```

`pdf_available: true` in that response means LibreOffice is working in the container.

---

## 10. Conventions worth knowing

- **CSS classes are `mr-`-prefixed, no exceptions.** The Reference Application loads Bootstrap
  globally; `.panel`, `.btn`, `.modal`, `.close`, `.badge` are all taken, and reusing one
  silently restyles the host page. Enforced by a test.
- **Liquibase changesets are immutable once shipped** — always append, never edit.
- **Pages never use `config`** (fragment-only); fragments never call `ui.decorateWith`.
- **Never interpolate a possibly-null value in a GSP** without also guarding the literal
  string `"null"` — Groovy renders a true null as that text, and legacy write paths store it.
  The same guard is applied server-side when formatting contributed values.
- **Labels from manifests are written with `textContent`, never `innerHTML`** — a manifest is
  trusted-but-not-ours, and treating it as markup would make it an XSS vector.

---

## 11. Known limitations

- No CSRF token on the `.form` POST endpoints — matches the surrounding legacy OpenMRS UI
  framework convention; a pre-existing ecosystem gap, not introduced here.
- "Add another study or series" in the imaging editor prompts for a UID rather than offering a
  picker. The study on screen is pre-filled, and building a second study browser inside
  medreport would put imaging logic where it does not belong.
- The version history is admin-only by privilege; there is no per-report delegation.
- PDF/ODT require LibreOffice on the renderer host; without it those formats degrade to DOCX
  with an explicit warning.
