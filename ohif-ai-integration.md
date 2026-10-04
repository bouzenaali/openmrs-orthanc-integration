# OHIF-AI — integration with the production PACS

**Project:** openmrs-orthanc-integration — Neurosurgery EMR, CHU Blida
**Status (2026-10-04):** **ALL FIVE STAGES COMPLETE AND VERIFIED IN PRODUCTION.** Both AI
features work against the real PACS, segmentations round-trip into it and are visible from
the existing viewer, and the §2.2 authentication bypass is closed. Remaining work is
clinical and operational, not architectural — §10. Stage 3 is written and validated but **not started** — `monai_server` still runs
in the evaluation stack. The credential rotation is complete — the published credential now returns
401 (§4.2.4). The imaging module ships the OHIF-AI button and the configuration editor
(1.4.1, deployed). Nothing of OHIF-AI itself is deployed.
**Companion:** `OHIF-AI.md` — what OHIF-AI is, why it is a second viewer, and the
evaluation that proved both AI features work. **Read that first.** This document covers
only what it takes to put it in front of the real PACS.

> This is a living document. Each stage gets marked **VERIFIED** with a date as it is
> demonstrated, in the same convention as `OHIF-AI.md`. A claim here is not true until
> something has been shown to work.

---

## 1. What is being integrated, and what changes

The evaluation ran self-contained on Server 2 against a throwaway Orthanc. Integration
means pointing it at the hospital's real PACS, with **nothing about the existing viewer
changing** (`OHIF-AI.md` §1).

The finding that shapes everything below: **there are two independent consumers of the
PACS, not one.**

| Consumer | Needs | Runs on |
| --- | --- | --- |
| The browser | DICOMweb for display (QIDO/WADO), STOW to save a segmentation | the clinician's workstation |
| **`monai_server`** | **Downloads the series itself** to run inference, writes the SEG back | **Server 2** |

MONAI is not a passive backend. Its MONAI Label datastore is configured with a DICOMweb
URL (`--studies …/dicom-web`) and fetches studies over the network. During the evaluation
that was the bundled Orthanc on the same host; in production it is a **second machine
reaching into the PACS**, and it needs credentials of its own. That is the substance of
stage 1.

---

## 2. Security findings

Both were found on 2026-09-24 while verifying the integration path. Neither was introduced
by this work; both must be resolved before it proceeds.

### 2.1 The PACS admin credential is in a public repository

`orthanc-cors-proxy.conf` hardcodes an `Authorization: Basic …` header so the proxy can
inject credentials on the browser's behalf. The file is **tracked in git and present on
`origin/main`**, and that remote is public.

`CLAUDE.md` records a known accepted risk for `.env` and `orthanc-docker-compose.yml`,
confirmed by hash comparison. **`orthanc-cors-proxy.conf` is not on that list**, so this
exposure appears never to have been assessed.

**Decision (2026-09-24): rotate.** See §4.2 for the runbook.

> Rotation makes the published value worthless, which is the substantive remedy. It does
> **not** remove it from git history. Rewriting history remains a separate decision that
> `CLAUDE.md` requires be taken explicitly, and it has not been taken.

### 2.2 Port 8043 gives the whole LAN unauthenticated admin access to the PACS

`ohif-docker-compose.yml` publishes `"8043:80"`, which binds `0.0.0.0` — not the host only,
despite the comment. Because that proxy **injects admin credentials**, any host on the
hospital network can read and write the PACS without presenting any of its own.
Demonstrated from Server 2:

```
curl http://<server1>:8043/dicom-web/studies   →  HTTP 200   (no credentials sent)
curl http://<server1>:8042/dicom-web/studies   →  HTTP 401   (Orthanc itself, correctly)
```

The auth injection that protects the browser is, viewed from the LAN, an authentication
bypass. Nginx Proxy Manager does not need the published port — it reaches the proxy over
the Docker network on port **80**.

This matters doubly here, because the convenient way to let MONAI fetch studies is exactly
that open port. **It works because of the gap.** Using it would build the integration on
top of a defect.

**Decision (2026-09-24): fix it.** MONAI gets a proper authenticated route instead (§4.1).

### 2.3 Tracked configuration turns branch switching into a production change

`orthanc-cors-proxy.conf` and `orthanc-docker-compose.yml` are **live, bind-mounted
production configuration** and were tracked in git. During this work the branch was
switched several times, and each switch silently rewrote them:

| Observed | Effect |
| --- | --- |
| switch to a branch where the file is tracked with the old credential | the rotated proxy config was **overwritten**, reverting the credential |
| switch to a branch where the file is untracked | the proxy config was **deleted outright**; the container kept serving an unlinked inode, so nothing appeared wrong until a restart would have failed on a missing bind-mount source |
| the compose file reverted to literals | the next `docker compose up -d` would have recreated Orthanc **without `pacsadmin`**, taking imaging down |
| the module source tree, committed on one branch only | **deleted from the working tree** on switching to the other |

`CLAUDE.md` warns that git is not a reliable *restore* path for operational config. The
inverse is just as dangerous and was not written down: git **will** restore tracked config
over a running system, without asking.

Resolved 2026-09-28 by making every branch agree: `orthanc-cors-proxy.conf` is ignored
everywhere (it carries the injected credential; its shape lives in a sanitised
`.example`), the compose reads its users from `.env` and is committed identically on
`main`, `develop` and `feature_ohif_ai`, and `*.secret-bak-*` is ignored so that backups
of credential-bearing files never land in the tracked `backup files/` directory.

> A file that is tracked on one branch and absent on another **will be deleted** when you
> switch. For live configuration that is a production outage waiting for a `git checkout`.

---

## 3. The five stages

| Stage | What | Deployed? |
| --- | --- | --- |
| **1** | Prerequisites and decisions — auth route, credential rotation, labelling, privilege | — |
| **2** | Production images: carry the patches, bake the viewer config | **DONE 2026-09-29** — built, not deployed |
| **3** | Expose `monai_server` from Server 2, properly | **DONE 2026-09-29** — written and validated, not started |
| **4** | One origin on Server 1: `ai-viewer.hospital.lan` | **PART DONE 2026-09-29** — §7.1 |
| **5** | Verification | — |

Stage 2 is build work with nothing at stake and can start while stage 1 is settled.
Stage 4 is the first that touches production.

---

## 4. Stage 1 — prerequisites (DECIDED 2026-09-24)

### 4.1 MONAI authenticates to the PACS over a proper route

**Decision: a dedicated, authenticated, transport-encrypted, source-restricted route. No
reuse of the LAN-open injecting proxy.**

Design, following the idiom `server2-stack` already uses for `clinical-agent`:

1. **Stop publishing 8043 to the LAN.** Remove the `ports:` mapping from
   `orthanc-cors-proxy` in `ohif-docker-compose.yml`, or bind it to `127.0.0.1`. NPM
   reaches it over the Docker network on port 80 and does not need it published. This
   closes §2.2 on its own.
2. **Create a dedicated Orthanc account for MONAI**, separate from the admin account, in
   Orthanc's `RegisteredUsers`. **Done 2026-09-24; verified 2026-09-28.**

   > **Correction.** This step originally said "least privilege: QIDO/WADO read and STOW
   > write, nothing else". That is **not achievable this way**. No authorization plugin is
   > loaded, so `RegisteredUsers` grants every account full administrative rights —
   > measured: the `monai` account's `DELETE /studies/...` returns **404 (not found)**, not
   > 403, so it is authorised to delete. Least privilege must therefore be enforced at the
   > vhost in stage 3, which restricts by path and source address and needs no Orthanc
   > plugin, exactly as `server2-stack`'s agent vhost already does.
3. **Publish a DICOMweb vhost for machine clients on NPM**, e.g. `pacs-api.hospital.lan`,
   TLS-terminated, **restricted by source address to Server 2**, proxying to
   `orthanc:8042`. It must *pass through* the caller's credentials — it must **not** inject
   any, or it recreates §2.2 under a new name.
4. **MONAI holds its credential in Server 2's environment file** (`chmod 600`, gitignored
   like the rest), and its datastore points at
   `https://pacs-api.hospital.lan/dicom-web`.
5. **The browser path is unchanged.** `orthanc-cors-proxy` keeps injecting for
   same-origin browser traffic exactly as it does today.

Net effect: credentials in transit are TLS-protected, each consumer has its own identity,
neither can act as the other, and nothing on the LAN has ambient access.

> **Stronger option, noted not chosen:** Server 2 already holds a certificate signed by
> `hospitalCA`, so mTLS between Server 2 and the PACS vhost is achievable and would remove
> the shared secret entirely. `server2-stack`'s own README records mTLS as ADR-9, blocked
> on the OpenMRS side — that blocker does not apply here, since this hop is nginx-to-nginx.
> Worth revisiting if the Basic credential proves awkward to rotate.

### 4.2 Rotate the exposed credential

**Decision (2026-09-24): rotate.**

#### 4.2.1 Who actually uses it — enumerated 2026-09-24

Established by scanning 2,739 files across the project and both sibling service
directories, the OpenMRS database, Nginx Proxy Manager, every running container's
environment, and Server 2. Values were compared, never printed.

**Live consumers — these break if they are not updated:**

| # | Consumer | Where it lives | How it must be changed |
| --- | --- | --- | --- |
| 1 | **Orthanc itself** (the authority) | `ORTHANC__REGISTERED_USERS` in `orthanc-docker-compose.yml`, reaching `orthanc-pacs` as container env | edit, then recreate the container |
| 2 | **`orthanc-cors-proxy`** — injects Basic auth for the browser | the `Authorization` header in `orthanc-cors-proxy.conf` | **single-file bind mount: `cat new > file`, never `sed -i`**, then reload nginx |
| 3 | **OpenMRS imaging module** | DB `imaging_OrthancConfiguration`, row `id=1`, `orthancUsername=orthanc` — confirmed byte-identical to the exposed value | **through the OpenMRS UI, never SQL** — module config is cached in memory |

There is no fourth. That is the whole functional surface.

**Confirmed clean — checked, not assumed:** the live NPM database and all three
`proxy_host/*.conf` files (NPM never needed the credential); `report-generation-service`;
`certificates/`; every tracked file in `server2-stack`; and every running container on
Server 1 other than `orthanc-pacs`.

**Copies that leak it without being consumers.** Tracked in git, therefore published:

```
orthanc-cors-proxy.conf                                     (live)
orthanc-docker-compose.yml                                  (live)
backup files/orthanc-cors-proxy.conf.bak-20260830-114426
backup files/orthanc-cors-proxy.conf.bak-20260830-154028
backup files/orthanc-cors-proxy.conf.bak-20260901-104751
backup files/ohif-app-config.js.bak-20260826-131435
backup files/ohif-app-config.js.bak-20260826-153349
backup files/README.md.bak-20260830-154028
backup files/orthanc-docker-compose.yml.before-persistence
```

**Nine tracked files, not one.** Two of them are old `ohif-app-config.js` backups, meaning
the credential was once in the **browser-side** config before the same-origin redesign
moved it server-side. Rotation neutralises all nine at once, which is precisely why it is
the right remedy rather than deleting files.

Local-only copies, never pushed: `chatbot-neuro/HANDOFF.md` on Server 2 (that repository
has no remote), plus that machine's search-index cache and assistant transcripts.

#### 4.2.2 Zero-downtime order

Orthanc's `RegisteredUsers` holds a **map**, so old and new credentials can coexist. Adding
before removing means imaging is never broken, and every step before the last is trivially
reversible.

1. **Add** the new admin user *and* the dedicated MONAI user (§4.1) alongside the existing
   one; recreate Orthanc. *Verify:* the old credential still returns 200 and the new one
   also returns 200.
2. **Update `orthanc-cors-proxy.conf`** to the new admin credential; reload nginx.
   *Verify:* `docker exec orthanc-cors-proxy cat /etc/nginx/conf.d/default.conf` shows the
   new value — the inode trap makes this check mandatory, not optional — and DICOMweb
   still answers 200 through the proxy.
3. **Update the imaging module through the OpenMRS UI.** *Verify:* a study opens in OHIF,
   and **Get studies** reconciles.
4. **Point MONAI at its own account** (§4.1). *Verify:* it lists studies with its
   credential and is refused without one.
5. **Only now remove the old user**; recreate Orthanc. *Verify:* the old credential
   returns **401**, and steps 2–4 still work.

#### 4.2.4 Outcome — VERIFIED 2026-09-28

```
running Orthanc users      pacsadmin, monai      (old 'orthanc' removed)
old credential   -> 401    pacsadmin -> 200      monai -> 200   bogus -> 401
browser path via proxy     -> 200
imaging module user        pacsadmin
OpenMRS                    -> 200
```

**The nine published copies of the old credential are now worthless**, which was the
point of rotating rather than deleting files. `ORTHANC_OLD_*` remain in `.env` for
rollback only and can be dropped once this has held for a while.

Step 3 was impossible until the imaging module gained an edit form — the page could only
add and delete, and deletion is refused once studies reference a configuration. That
shipped as module **1.4.1**, along with two defects found on first use: the edit path
called the save-new service method and so tripped the duplicate-URL guard against the
row being edited, and the edit icon had no CSS rule and rendered at full size.

> After step 5, **Stone Web Viewer and Orthanc Explorer 2** will prompt again: they are
> served by Orthanc directly and authenticate in the browser, so saved credentials become
> invalid. Expected, not a fault.

#### 4.2.3 Stop the secret returning to git

Rotation is pointless if the new value is committed. Both live files that carry it are
tracked, so:

- The new secrets live in the repository-root **`.env`**, which is `0600`, listed in
  `.gitignore`, **untracked and never pushed** (verified 2026-09-24).
- `orthanc-docker-compose.yml` references them by variable instead of embedding literals.
- `orthanc-cors-proxy.conf` is **untracked** and replaced in the repository by a sanitised
  `.example` template, so the shape stays version-controlled and the secret does not.

> The stronger option, not taken now: render the proxy config from an nginx
> `envsubst` template, as `server2-stack` does. It keeps the file tracked and injects the
> header at container start. It is deferred because `NGINX_ENVSUBST_FILTER` must be scoped
> or nginx's own `$host` and `$remote_addr` are silently blanked — a failure mode worth
> introducing deliberately, not in the middle of a credential rotation.

Rotation is not complete until a **fresh clone of the public repository** has been checked
for any remaining live-matching value, by the hash-comparison method `CLAUDE.md` used for
the 2026-09-07 finding.

### 4.3 Clinical labels on AI segmentations

**Decision: add them.** Segmentations written by this system land in the production PACS
as real clinical data, and the evaluation produced segment labels like
`nninter_pred_20260924184932` — a timestamp, not a clinical finding.

Requirements:

- A segment must carry a **clinically meaningful label** chosen by the operator.
- It must be **identifiable as AI-assisted**, so a later reader is never misled about
  provenance. DICOM SEG already models this: `SegmentAlgorithmType` was `SEMIAUTOMATIC` in
  the evaluation output, and `SegmentAlgorithmName` should name the model.
- The convention must be agreed before clinical use, not invented per user.

Open: who authors the label list, and whether it is free text or a controlled vocabulary.

### 4.4 An OpenMRS privilege gates access

**Decision: yes.** Access is privileged, following the pattern `agentgateway` already
uses (`App: agentgateway.voice.use`).

Planned work, pending the imaging module source:

- A new privilege, e.g. `App: imaging.ohifai.use`.
- A button in the imaging module's studies table linking to the AI viewer, **shown only to
  users holding that privilege** — alongside the existing Stone / Orthanc Explorer / OHIF
  links.
- The link target as a global property, as `imaging.ohifBaseUrl` already is, so the URL is
  configurable without a rebuild.

> The maintainer will supply the imaging module code; the button and privilege are added
> then. Note the existing trap: **global properties are cached in memory — set them
> through the UI, never by SQL.**

---

## 5. Stage 2 — production images

### 5.1 Carry the patches into the `monai` image

The evaluation applies three fixes by **bind-mounting** files over the image
(`OHIF-AI.md` §10.7):

| File | Fix |
| --- | --- |
| `basic_infer.py` | slice-range guard; `vllm_max_tokens` 8192 → 1536; `OpenAI(api_key=…)`; 21 broken `raise MONAILabelError(...)` |
| `endpoints/infer.py` | surfaces `MONAILabelException` as HTTP 400 with its message |

**These must be baked into the production image.** A rebuild silently discards them while
the bind-mount keeps them looking present — the single most likely way this deployment
breaks months from now.

**DONE and VERIFIED 2026-09-29.** The Dockerfile's `COPY ./monai-label/. ./` picks the
patched sources up, so a rebuild bakes them in. Confirmed against the image with no mounts
attached, then both bind-mounts were removed from the compose file and `monai_server`
recreated from the image alone:

```
slice-range guard        : 1
vllm_max_tokens 1536     : 1
OpenAI api_key fix       : present
broken raises remaining  : 0      <- all 21 fixed
endpoint returns 400     : 1
patch bind-mounts        : 0
```

Then the behaviour itself, replaying the request that first failed — an unbounded slice
range now returns **HTTP 400** with the actionable message, from the image, with nothing
mounted over it.

> The image is the only source of truth for these fixes now. That is the point: a mount
> that silently stops being applied is worse than no mount.

### 5.2 Bake the viewer's data source

`APP_CONFIG` is resolved at build time, not mounted — unlike the existing viewer's
`ohif-app-config.js`, which is a live bind mount. Pointing the AI viewer at the production
PACS therefore means a config file and a **rebuild** (~4 minutes).

The config must use **same-origin paths** (`/dicom-web`, `/wado`), and the bundled-Orthanc
`/pacs/` route must go.

**DONE and VERIFIED 2026-09-29.** `APP_CONFIG` is now a build **ARG** with its previous
value as the default, so the evaluation image builds exactly as before and the production
image is a separate tag:

```bash
docker build --build-arg APP_CONFIG=config/chu-production.js -t webapp:prod \
  -f Viewers/platform/app/.recipes/Nginx-Orthanc/dockerfile Viewers/
```

`Viewers/platform/app/public/config/chu-production.js` uses **relative** paths, so no
hostname is baked into the image and the same build works whatever the host is called:

| | `webapp:prod` | `webapp:latest` (evaluation) |
| --- | --- | --- |
| `qidoRoot` / `wadoRoot` | `/dicom-web` | `/pacs/dicom-web` |
| `wadoUriRoot` | `/wado` | `/wado` |
| `/pacs/` occurrences | **0** | 1 |
| bundles | 213 MB | 213 MB |

`dicomUploadEnabled` is **false** in the production config, deliberately: studies reach
the PACS through the OpenMRS imaging module, which records who uploaded what. A
drag-and-drop route into a clinical PACS from a viewer bypasses that.

### 5.3 Getting the viewer image onto Server 1

The viewer runs on Server 1 (§3) but is **built on Server 2** — Server 1 has 7 GB of RAM
shared with OpenMRS, MySQL, Orthanc and NPM, and an OHIF webpack build is not a good
neighbour. There is no registry, so the image is streamed directly:

```bash
ssh <server2> 'docker save webapp:prod | gzip -1' | docker load    # ~11 s on this LAN
```

### 5.4 The viewer's own nginx config

The image's final stage is `FROM nginx:alpine`, and the upstream recipe supplies the
nginx config by bind-mount rather than baking it. **Without one the container serves
nginx's default root, not the viewer.** `ohif-ai-viewer.conf` in the repository root is
that file: it serves the bundles and nothing else, since NPM does the routing.

It is a **single-file bind mount**, so it carries the same inode trap as
`orthanc-cors-proxy.conf`: change it with `cat new > file`, never `sed -i`, and verify
with `docker exec`.

Verified 2026-09-29 by running the image with that config on a spare port:

```
/                 HTTP 200 text/html
/app-config.js    HTTP 200, qidoRoot:"/dicom-web"
SPA fallback      HTTP 200      (unknown paths are client-side routes)
/sw.js            Cache-Control: no-cache
gzip              1056 -> 635 bytes
nginx errors      0
```

One deviation from the recipe: it sets `Access-Control-Allow-Origin: *`, which it needs
because it serves DICOM from a different path. In the same-origin design nothing
cross-origin is expected, so that header is **not** reproduced.

---

## 6. Stage 3 — expose MONAI from Server 2

**DONE 2026-09-29 — written and validated, deliberately not started.** It follows
`server2-stack/README.md` §"Adding another service later": one overlay plus one vhost
template, with nothing in the base files changed.

| File | What it does |
| --- | --- |
| `docker-compose.monai.yml` | defines `monai_server` with `expose:` and **never `ports:`**, a pinned image, the GPU reservation, and the `MONAI_` additions to `NGINX_ENVSUBST_FILTER` |
| `nginx/templates-monai/monai.conf.template` | the vhost: TLS, allowlisted to Server 1, rate-limited, and a **path allowlist** |
| `nginx/nginx.conf` | adds the `monai_infer` rate-limit zone |

### 6.1 The path allowlist, and why it is the real least-privilege control

§4.1 promised MONAI least privilege and could not deliver it in Orthanc — no
authorization plugin, so the `monai` account has full rights there. **This vhost is where
that promise is actually kept**, by restricting paths and source address instead. It needs
no Orthanc plugin.

The allowlist is not guesswork. It is what the viewer actually requested across the entire
evaluation:

```
112  POST /infer/segmentation
 14  POST /nninter/session/        (+ .../<token>/release)
  1  GET  /info/
```

Nothing else was ever used, so nothing else is exposed. That matters because MONAI Label
also serves `/datastore/...` and **`/train/`** — a browser being able to start model
training on the hospital's GPU is not a theoretical concern, and `return 404` costs
nothing.

### 6.2 Two things the vhost gets right that are easy to get wrong

**NPM must strip the `/monai` prefix.** The browser calls
`https://ai-viewer.hospital.lan/monai/infer/segmentation`, but MONAI Label serves
`/infer/segmentation`. NPM's `proxy_pass` needs its trailing slash. Without it every
request lands in the catch-all and returns 404 — which looks exactly like the allowlist
being wrong.

**The rate limit is effectively global, not per clinician.** Every request arrives from
NPM, so they all share one `$binary_remote_addr`. It is set high and is a runaway-client
guard; the GPU lock does the real serialising.

### 6.3 Validated by

```
docker compose ... -f docker-compose.monai.yml config     parses
monai_server resolves: image pinned, expose 8002, ports none, server2_net
  VLLM_BASE_URL  http://vllm:8000/v1        (vLLM publishes no host port)
  DICOMWEB_USER/PASSWORD set from .env      (the dedicated monai account)
  --studies      https://pacs-api.hospital.lan/dicom-web
rendered vhost: server_name, allow 10.0.211.249/32 + deny all, 512m, 600s,
  /infer/ /nninter/ /info/, catch-all 404, plain HTTP -> 444
nginx -t        syntax is ok, test is successful
unsubstituted ${...} remaining: 0
```

### 6.4 Before this can be started

1. **Re-issue the TLS certificate.** It currently covers `agent.hospital.lan` and
   `stt.hospital.lan` only. `certs/agent-san.cnf` now lists `monai.hospital.lan`, but the
   certificate itself has **not** been re-issued — TLS will fail for that name until it is.
   Note also that `certs/` is gitignored, so that CSR change is on disk and **not**
   version-controlled.
2. **DNS** for `monai.hospital.lan`, resolvable from Server 1.
3. **`pacs-api.hospital.lan` must exist** (§4.1) — `--studies` points at it, and it is
   created in stage 4 on Server 1.
4. **Retire the evaluation `monai_server`.** Both define the same container name, so they
   cannot run together. Starting this overlay means the evaluation stack stops being the
   thing serving MONAI.

---

## 7. Stage 4 — one origin on Server 1

```
https://ai-viewer.hospital.lan            NPM on Server 1, TLS terminated here
  ├── /            → ohif-ai-viewer:80        Server 1, static bundles
  ├── /dicom-web/  → orthanc-cors-proxy:80    Server 1, unchanged, injects for the browser
  ├── /wado/       → orthanc-cors-proxy:80    Server 1, unchanged
  └── /monai/      → Server 2, via server2-proxy (TLS, allowlisted)
```

### 7.1 Progress — 2026-09-29

**Done, and none of it touches the three live proxy hosts:**

| | |
| --- | --- |
| Certificate for the new names | `certificates/aiviewer.crt` — `ai-viewer.hospital.lan` + `pacs-api.hospital.lan`, signed by `hospitalCA`, verified to chain. A **separate** certificate: all three existing hosts share `npm-3`, and extending that would have put OpenMRS, Orthanc and the viewer at risk for the sake of two new names. |
| Server 2 certificate re-issued | now covers `agent`, `stt` **and** `monai`. Generated from the **existing private key**, so the old certificate stays valid and rollback is restoring one file. Installed, inode preserved, `nginx -t` passes — **but nginx has not been reloaded**, so it is still serving the old certificate. Harmless: the new one is a superset. |
| `ohif-ai-viewer` container | running on Server 1 from `webapp:prod`, **no published port**, NPM reaches it by name: `HTTP 200`, `qidoRoot:"/dicom-web"`. |
| `ohif-ai-docker-compose.yml` | added. |

**A trap found on the way.** `server2-stack/1-make-agent-csr.sh` regenerates
`agent-san.cnf` from a heredoc listing only `$AGENT_HOSTNAME`, `localhost` and the IPs —
**no `stt`, no `monai`**. The working file was hand-edited after that script last ran, so
re-running it would silently produce a certificate that breaks the STT vhost. The CSR here
was made by hand from the existing key for that reason. The script should be fixed before
anyone trusts it again.

### 7.2 Progress — 2026-09-30

DNS, the NPM hosts and the OpenMRS global property were done by the maintainer. The
Server 2 half is now **live**:

| | |
| --- | --- |
| `pacs-api.hospital.lan` | **200** with the `monai` credential, **401** without. Credentials are passed through, not injected — the opposite of the port-8043 defect (§2.2). |
| MONAI overlay | started; `monai_server` now belongs to **server2-stack**, on `server2_net`, and the evaluation container is retired |
| `monai.conf` | loaded by `server2-proxy` |
| MONAI datastore | `Init Datastore for: https://pacs-api.hospital.lan/dicom-web` — the production PACS, authenticated, no 401s |

**The path allowlist works, measured from Server 1:**

```
/info/            200
/train/           404      <- a browser cannot start GPU training
/datastore/label  404
/docs             404
plain HTTP        connection closed (return 444)
```

#### Still broken: the ai-viewer proxy host

`4.conf` does not exist. The host is present and enabled in NPM's database with the
correct `http` → `ohif-ai-viewer:80`, but nginx rejected the generated config, so NPM
deleted it and reloaded without it. TLS therefore answers `unrecognized name`.

The cause is the `/monai/` **Advanced** block: it contains `proxy_pass`, and **NPM already
emits its own `proxy_pass` for a custom location**. Two in one block is
`nginx: [emerg] "proxy_pass" directive is duplicate`, which fails the whole file.

Strip the prefix with a `rewrite` instead, and leave the location's own forward
fields (https / monai.hospital.lan / 443) to generate the `proxy_pass`:

```nginx
rewrite ^/monai/(.*)$ /$1 break;
proxy_ssl_server_name on;
proxy_set_header Host monai.hospital.lan;
```

> Worth remembering beyond this instance: a custom location's Advanced box is *inside* the
> generated `location`, so anything NPM already emits must not be repeated there. And the
> failure is silent from the outside — the host simply stops existing, and the symptom is a
> TLS name error rather than anything pointing at the directive.

#### `client_max_body_size`

Left at NPM's global **2000m**. A DICOM SEG is tens of megabytes, and the only mechanism
available without the UI applies to *every* proxy host, so setting `0` would remove the cap
for OpenMRS uploads to solve a problem that is not occurring.

#### Block Common Exploits

Already disabled on hosts 3, 4 and 5 — the DICOMweb-carrying ones. Nothing to change.

### 7.3 Checklist

Each item a known trap in this stack:

- **Internal ports in NPM** — `ohif-ai-viewer` is **80**, `orthanc-cors-proxy` is **80**.
  Using the published port gives `502`.
- **`client_max_body_size 0`** on the new proxy host, or saving a SEG fails.
- **Disable "Block Common Exploits"** on the host — it rejects some DICOMweb paths with
  `403`.
- **DNS** for `ai-viewer.hospital.lan`, and the name added to the certificate.
- The server cannot resolve `*.hospital.lan`; verify with `curl --resolve`, and do **not**
  change the server's DNS to make a test pass.

---

## 8. Stage 5 — verification

Nothing is "done" until these pass:

1. **Private/incognito window.** The study list loads with **no password prompt**. Seeing
   images after typing a password is a **failure** — it means the auth chain was bypassed.
2. **Segment and save.** The SEG appears **in the production Orthanc** and is visible from
   the **existing** viewer. This is the premise of the whole side-by-side design, and it
   was proved against the throwaway PACS on 2026-09-24 (`OHIF-AI.md` §5.2) — it must be
   proved again here.
3. **Report generation** on a bounded slice range.
4. **MONAI's own PACS access** works over the new authenticated route, and **fails**
   without credentials.
5. **From a third host on the LAN**, `8043` is no longer reachable (§2.2 closed).
6. **`~/nginx-proxy-manager/data/logs/proxy-host-<n>_access.log`** shows the real status
   codes. It is the authoritative evidence, not what the browser appeared to do.

### 8.1 Results — 2026-10-04

| # | Check | Result |
| --- | --- | --- |
| 1 | No password prompt | **PASS** — no `WWW-Authenticate`, and the client sends **zero** `Authorization` headers |
| 2 | Segment and save into the production PACS | **PASS** — see §8.4 |
| 3 | Report generation on a bounded slice range | **PASS** — HTTP 200 in **4.8 s** on a real MR series |
| 4 | MONAI's PACS access works, and fails without credentials | **PASS** — 200 as the `monai` account, 401 without |
| 5 | Port 8043 unreachable from the LAN | **PASS** — closed 2026-10-04, §8.5 |
| 6 | Access log shows the real codes | **PASS** — only the deliberate allowlist 404s |

The report, through the whole chain and on a real study, was:

> *"There are multiple ill-defined, hyperintense lesions in the periventricular white
> matter on the left side. The lesions appear to be centered around the lateral ventricles,
> extending to the subcortical white matter."*

A plausible FLAIR reading. Clinical accuracy is a separate question this document does not
answer — what is proven is that the path works end to end on production data.

### 8.2 Three faults only an end-to-end test could find

Each presented as a bare HTTP 500 naming nothing, and each was mine:

1. **MONAI did not trust `hospitalCA`.** `--studies` points at a TLS endpoint signed by the
   hospital's private CA, and the image ships only public roots, so every fetch died in
   `requests.exceptions.SSLError`. Fixed with a bundle that is **certifi's plus
   hospitalCA** — not hospitalCA alone, because nnInteractive and VoxTell fetch weights
   from Hugging Face.
2. **The credential variables were the wrong names, twice.** MONAI Label reads settings
   with a `MONAI_LABEL_` prefix, so a bare `DICOMWEB_USERNAME` is silently ignored. And
   `config.py` marks `MONAI_LABEL_DICOMWEB_*` deprecated in favour of `..._DATASTORE_*`,
   while `interfaces/app.py` still reads **DICOMWEB** — so following the documented name
   also yields a silent 401. Both pairs are now set.
3. **The datastore filtered to CT only.** Upstream defaults
   `MONAI_LABEL_DICOMWEB_SEARCH_FILTER` to `{"Modality": "CT"}`, which hides every MR study
   from a neurosurgery department that works mostly in MR.

> The common thread: a misconfigured credential or trust store surfaces as `500`, never as
> "401 from the PACS". When inference fails, read `docker logs monai_server` — the HTTP
> status tells you nothing.

### 8.3 What still needs a human

**Segment a study in the browser and save it**, then confirm the SEG lands in the
production Orthanc and is visible from the **existing** viewer. That round-trip was proven
against the evaluation PACS (`OHIF-AI.md` §5.2) and must be proven again here.

**Use the right study.** Of the two in the PACS, one (`…1116259`, "IRM CEREBRALE") contains
only an "AW electronic film" secondary-capture series with **no 3D geometry at all** —
MPR, axial and dual views are black by necessity, and nnInteractive cannot work on it. The
other (`…1449903`) has twenty-odd real MR series with full geometry, including `3D T1 GADO`
(268 instances) and `Ax T2 FLAIR` (34). Use that one. The rendering diagnosis is in
`NGINX-OHIF-AI.md` §12.

**Not from a server desktop.** Server 1 has no GPU render node at all, so Firefox there
falls back to software rendering. See `NGINX-OHIF-AI.md` §11 for which machine does which
computation.

### 8.4 The PACS round-trip — VERIFIED 2026-10-04

A segmentation made in the browser and saved as `PACStest` is in the production Orthanc:

```
Modality          SEG          SOPClassUID  1.2.840.10008.5.1.4.1.1.66.4
512 x 512, 23 frames, 2 segments from nnInteractive
references        'CORO T2' (44 instances, MR) - a real series in the same study
patient           BOUSSEDRAYA MERIEM / PAT-1329397
```

**And it is visible from both viewers.** Queried through each origin independently, each
returns the same 23 series including `PACStest`:

```
viewer.hospital.lan     23 series, SEG: PACStest     <- the existing OHIF 3.9.2
ai-viewer.hospital.lan  23 series, SEG: PACStest     <- OHIF-AI
```

That is the premise the entire side-by-side design rests on (§1.1 of `OHIF-AI.md`): one
PACS, two viewers, work done in either visible from the other. It is now demonstrated on
production data rather than assumed.

> **The labels confirm §4.3 is still outstanding.** The segments are named
> `nninter_pred_20261004172547` and `nninter_pred_20261004172558` — timestamps, not clinical
> findings — and `SegmentAlgorithmName` carries `nninter_0.1776447296142578`, which is a
> timing value that has leaked into a provenance field. A reader a year from now cannot tell
> what was segmented or by which model version. Agree the convention before clinical use.

### 8.5 Port 8043 closed — 2026-10-04

`orthanc-cors-proxy` no longer publishes a port. It injects admin credentials into every
request it forwards, so a published port was an **authentication bypass**: anything on the
hospital LAN could read and write the PACS without presenting a credential of its own.

```
from Server 2:  http://<server1>:8043/dicom-web/studies   ->  unreachable
viewer.hospital.lan     /dicom-web/studies  ->  200
ai-viewer.hospital.lan  /dicom-web/studies  ->  200
WWW-Authenticate headers                    ->  0
```

Nothing broke, because NPM always reached it over the Docker network on port 80 and never
needed the published port. The finding in §2.2 is closed.

---

## 9. Operating constraints, measured

| Constraint | Value | Consequence |
| --- | --- | --- |
| Report slice range | **≤ 9–10 slices** at `--max-model-len 4096` | enforced by the guard with an actionable message; wider ranges need a vLLM change that costs concurrency |
| VRAM | vLLM 7.5 GB + STT 3.7 GB + nnInteractive ~1.9 GB on a 16 GB card | ~1.1 GB free with everything resident — **too tight**. 4.8 GB with STT stopped. Needs a policy or a larger card. |
| GPU driver | see `OHIF-AI.md` §11.1 | a kernel update leaves the driver unloaded and takes NLU, dictation **and** this down until someone runs `modprobe` |
| Model context | 4096 tokens, KV cache 9,072 | raising `--max-model-len` buys prompt room and costs concurrency |

---

## 10. Open items

| Item | Owner |
| --- | --- |
| New password chosen and rotated (§4.2) | maintainer |
| Whether to rewrite git history for the exposed value, or accept rotation as sufficient | maintainer — `CLAUDE.md` requires an explicit decision |
| Clinical label vocabulary (§4.3) | clinical |
| Imaging module source, for the button and privilege (§4.4) | maintainer |
| VRAM policy: is dictation stopped during imaging sessions, or is a larger card budgeted? | maintainer |
| Driver autoload at boot, so a kernel update stops taking clinical features down | infrastructure |
