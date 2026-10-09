# chuschedules — Horaires récurrents

Recurring weekly and monthly provider schedules for OpenMRS, generated into ordinary
appointment blocks.

**Status:** phases 1–3 built, deployed and working on the CHU Blida instance. The system is
destined for clinical use but is currently in a development phase: the appointment services,
locations and providers it points at are still the stock OpenMRS demo records, so nothing
generated so far is clinically meaningful. Treat writes with the care the rest of this repo
demands (see `../CLAUDE.md`).
See [`../Recurring-Schedules-Design.md`](../Recurring-Schedules-Design.md) for the design
and the reasoning behind it.

---

## Why this exists

`appointmentschedulingui/scheduleProviders.page` creates **one appointment block per
provider per day, by hand, forever**. There is no recurrence anywhere in the shipped stack:
the calendar reacts to `dayClick` only, and on save it rebuilds the end timestamp from the
*start* date, so a block cannot even span two days by construction.

For a department running six clinics a week, that is roughly 250 manual dialogs per provider
per year.

Upgrading is not an alternative: `appointmentscheduling` **2.0.0** and
`appointmentschedulingui` **2.0.0** were both checked and add no recurrence, and leave the
unused `ProviderSchedule` entity unchanged (it has no day-of-week column).

## What it does

Define a provider's pattern once, preview what it would create, then generate — up to a year
ahead. Generated blocks are ordinary `AppointmentBlock` + whole-block `TimeSlot` rows, so
they are indistinguishable from hand-made ones everywhere downstream: calendar, booking,
daily lists, reports and REST all work unchanged. **No upstream table is modified.**

### Recurrence rules

Two rules, deliberately a small subset of iCalendar:

| Rule | Fields | Expresses |
| --- | --- | --- |
| `WEEKLY` | weekday, interval, anchor | every Tuesday; every **other** Tuesday; one week in three |
| `MONTHLY_NTH` | weekday, position 1–4 or last | the **first Monday** of the month |

The anchor is normalised onto the range's weekday, so cycle arithmetic is exact integer
maths with no week-start convention (Saturday? Sunday? Monday?) to get wrong.

Several ranges may share a weekday — a morning and an afternoon clinic are two ranges, which
is how staff describe them, and they generate two blocks.

## Using it

**Administration système → Horaires récurrents**, behind the `Manage Recurring Schedules`
privilege. Direct URL: `/openmrs/chuschedules/manageSchedules.page`.

1. **Nouvel horaire** — provider, location, services, validity dates, and a grid of sessions
   (weekday, start, end, repetition).
2. **Générer → Aperçu** — a dry run. Shows exactly what would be created and what would be
   skipped, and writes nothing.
3. **Confirmer** — only appears after a preview. Generation is never the default action.
4. **Jours fériés et congés** — dated exclusions, hospital-wide or per provider.

### The report

Skips are split by whether a person can act on the individual date:

- **À vérifier** — holiday/leave clashes and collisions with existing blocks, listed date by
  date. Each concerns one day and may need a decision.
- **Ignorées** — past dates, out-of-validity, inactive template, already generated. One
  sentence each, naming the cause.

That split exists because a year's generation against a template ending in December produced
**224 identical rows**, burying the one row that mattered: a clash with a block that already
had a patient booked into it.

### Nightly generation (optional, off by default)

A task **CHU Recurring Schedules - génération glissante** is registered on module start and
left **stopped**. Enable it in **Administration → Planificateur**. It tops every active
template up to `chuschedules.rollingHorizonDays` (default 365, clamped to 365) once a day at
02:00.

It is safe to run repeatedly: generation is idempotent, so a nightly run on a settled
schedule creates the few days that newly came within the horizon and skips the rest.

## Guarantees

**54 unit tests** cover these, split across the recurrence rules (19), the occurrence
planner (16), the refresh decision (13) and the report grouping (6). Each guarantee exists
because the alternative is dangerous in a hospital:

| Invariant | Why |
| --- | --- |
| Never writes to a date on or before today | The past is a record, not a schedule. |
| Never creates a block overlapping an existing one | The check is **provider-scoped**, so two doctors may hold clinic at the same hour, but work entered by hand is never shadowed or duplicated. |
| Never deletes or alters a block that has appointments | Editing a pattern voids only **future, generated, empty** blocks. Booked ones stay at the old times and are reported for a human to resolve. A silently moved booked clinic is the worst thing this module could do. |
| Idempotent | Enforced by `unique(range_id, target_date)` in the database, not by luck of timing. |
| Every skip reported with a reason | Both the manual action and the nightly task must be auditable afterwards. |
| One run is capped at 366 days | An unbounded horizon is how a mistyped date becomes a decade of blocks. |

Editing only the name or validity dates changes nothing about the schedule: the pattern is
fingerprinted and compared, so **a rename cannot move anybody's appointment**.

## Data model

```
chu_schedule_template         provider, location, validity, active
  └── chu_schedule_template_range   weekday, times, recurrence rule
  └── chu_schedule_template_type    services offered
chu_schedule_exception        holidays (global) and leave (per provider)
chu_generated_block           provenance: which block came from which range, on which date
```

`chu_generated_block` is what makes generation idempotent **and** rollback possible: it is
the authoritative list of what this module created, so module-generated blocks can always be
told from hand-entered ones. `block_uuid` deliberately carries **no foreign key** — the
appointment block table belongs to another module, and a constraint would couple our schema
to its liquibase.

## Building and deploying

```bash
./validate-xml.sh          # REQUIRED before every deploy — see below
mvn clean package
cp omod/target/chuschedules-1.0.0.omod "../module-backups/chuschedules/chuschedules-1.0.0.omod.$(date +%Y%m%d-%H%M%S)"
docker cp omod/target/chuschedules-1.0.0.omod openmrs-app:/usr/local/tomcat/.OpenMRS/modules/chuschedules-1.0.0.omod
docker restart openmrs-app
```

**Readiness after a restart:** `/openmrs/` returns 200 while modules are still loading. The
honest signal is `/openmrs/referenceapplication/login.page` returning **200**; until then a
perfectly good module still 404s.

## Traps

Each of these cost real time. They are cheap to re-introduce.

| Trap | Rule |
| --- | --- |
| **A `--` inside an XML comment took the whole application UI down.** Spring refreshes every module's web context together, so one malformed file stops the entire OpenMRS web layer — the login page 404s and the app looks dead. Neither Maven nor omod packaging parses these files. | Run `./validate-xml.sh` before every deploy. |
| **Calendar dates mapped as `java.util.Date` land on the wrong day.** The app JVM runs `Africa/Algiers` (UTC+1) while MySQL runs UTC, so binding a date as a *timestamp* turns local midnight into 23:00 the previous day and a `DATE` column keeps the earlier day. A template saved as valid from 1 Sept was stored as 31 Aug. | Map calendar dates as Hibernate `type="date"`. Only genuine instants — the audit columns — stay timestamps. |
| **Hard-deleting a range breaks editing, invisibly.** `getRanges().clear()` under `all-delete-orphan` DELETEs rows that `chu_generated_block` references. The database refuses, but the violation surfaces during the *end-of-request* flush, after the controller's try/catch has returned — so the page renders normally and the user's edit vanishes silently. It only manifests once a template has generated something. | Cascade `all`, void ranges instead of deleting, and `Context.flushSession()` inside the service call. |
| **A scheduled task registered without a start time does nothing when started**, with no error explaining why. | Always `setStartTime()` when registering a `TaskDefinition`. |
| **`07:00` in the database is an `08:00` clinic.** MySQL stores UTC, the app renders CET. | Verify times through the app or REST, never by reading the raw column. Hand-made blocks show the same offset. |

## Known gaps

- **No OpenMRS context-sensitive (integration) test.** One was attempted and abandoned;
  see below. The decision logic it would have covered is now pure and unit tested
  (`RefreshPlanner`, 13 tests); what remains uncovered is the thin adapter around it —
  the service calls that fetch a block by uuid and void it.
- **No capacity model.** Booking capacity remains the upstream rule (time left in the slot
  versus service duration). Templates set *when* a clinic is open, not how many patients fit.
- **Lunar holidays are data, not an algorithm.** Eid moves each year and must be entered.
- **An `appointmentscheduling` upgrade could change the block/slot contract.** §2 of the
  design doc is the thing to re-verify.


## Why there is no context-sensitive test

An `OpenMRS BaseModuleContextSensitiveTest` for `refreshFutureBlocks` was attempted on
2026-09-17 and abandoned deliberately.

The harness itself is available and the test boots — but the OpenMRS test context loads
**every `moduleApplicationContext.xml` on the classpath**, and this module compiles against
`appointmentscheduling-api`, whose context wires reporting beans. Satisfying them cascaded:

```
appointmentscheduling-api  →  needs reporting-api
reporting-api              →  needs calculation-api
calculation-api            →  needs serialization.xstream
                           →  ... and onward
```

Three modules deep to reach a forty-line method, with no end in sight. The resulting test
would have been slow, and brittle in the worst way: failing on version drift in modules this
one does not use, for reasons unrelated to the rule under test. That is a test that gets
disabled the first time it goes red.

Instead the decision was extracted into `RefreshPlanner.decide(...)` — pure, no context, no
clock — and tested exhaustively, including every combination of (block exists, block voided,
has appointments) against past and future dates. That is the same split already used for
generation (`OccurrencePlanner`), and it covers the part where a wrong answer would delete a
clinic a patient is booked into.

**If a context-sensitive test is ever wanted**, the tractable route is not to assemble the
module graph but to test against a real running instance — which is how every behaviour in
this module has in fact been verified, including this one: editing a template with 89
generated blocks, one of them booked, reports `voided=88 kept=1` and leaves the booked
appointment untouched.
