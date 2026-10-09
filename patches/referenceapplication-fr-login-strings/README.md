# referenceapplication French login strings ("Connecté en Tant que {0} au {1}.")

Applied: 2026-09-08. Target: `openmrs-app`, referenceapplication 2.12.0 (`.OpenMRS/modules`).

## Symptom

On the French login page the submit button reads

```
Connecté en Tant que {0} au {1}.
```

clipped by the button width to `Connecté en Tant que {0} au`, so it looks like a
half-rendered sentence. Three other strings on the same page are also wrong
French ("le nom d'utilisateur", "Vous ne pouvez pas Etre connecter?",
"Veuillez contacter votre Système Administrateur.").

## Root cause

Not a template or backend fault. `login.gsp` renders one plain message key with
no format arguments:

```groovy
value="${ ui.message(selectLocation ? "general.done" : "referenceapplication.login.button") }"
```

so any `{0}` / `{1}` in the value stays literal. The module's French bundle
carries a sentence from a different context under that key:

| locale | `referenceapplication.login.button` |
|---|---|
| en | `Log In` |
| de | `Anmelden` |
| es | `Iniciar sesión` |
| **fr** | **`Connecté en Tant que {0} au {1}.`** |

All 19 other locales are a short verb; only French is wrong. It is bad upstream
Transifex content, not a local misconfiguration. The visual truncation is
`#loginButton { inline-size: 100% }` (chublidatheme) on an `<input type="submit">`,
which cannot wrap - the string overflows the pill and is clipped.

## Fix

`messages_fr.properties` inside the omod, four keys:

```
referenceapplication.login.button=Se connecter
referenceapplication.login.username=Nom d'utilisateur
referenceapplication.login.cannotLogin=Impossible de se connecter ?
referenceapplication.login.cannotLoginInstructions=Veuillez contacter votre administrateur système.
```

Nothing else in the module is touched: the rebuilt omod differs from the
original in exactly one zip entry (verified by CRC), and the file keeps its
UTF-8 encoding and LF endings.

## Files

- `referenceapplication-2.12.0.omod` - patched module, install this.
- `referenceapplication-2.12.0.omod.original-backup` - pristine 2.12.0 (md5 `d5b21baf1de4b9b3f5b8c3e94041d51f`).
- `messages_fr.properties.original` / `.patched` - the bundle before and after, for review.
- `apply.sh` - installs the omod and restarts openmrs-app (~2-3 min downtime), then verifies.
- `verify.sh` - anonymous GET of the login page; fails if the button still renders `{0}`.

## Caveats

- A restart is required: module message bundles are read at module start.
- **A referenceapplication upgrade reverts this.** Re-run `apply.sh` after any
  module upgrade, or rebase the patch onto the new version's bundle.
- The real long-term fix is upstream: correct the French `referenceapplication`
  resource on OpenMRS Transifex so the next release ships `Se connecter`.
- Rollback: `docker cp referenceapplication-2.12.0.omod.original-backup
  openmrs-app:/usr/local/tomcat/.OpenMRS/modules/referenceapplication-2.12.0.omod`
  then restart.
