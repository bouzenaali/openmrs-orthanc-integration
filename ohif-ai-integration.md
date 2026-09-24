# OHIF-AI — integration with the production PACS

**Project:** openmrs-orthanc-integration — Neurosurgery EMR, CHU Blida
**Status (2026-09-24):** **PLANNING. Nothing in this document has been deployed.**
Stage 1 decisions are made (§4); stages 2–5 are designed and not yet built.
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

---

## 3. The five stages

| Stage | What | Deployed? |
| --- | --- | --- |
| **1** | Prerequisites and decisions — auth route, credential rotation, labelling, privilege | — |
| **2** | Production images: carry the patches, bake the viewer config | no |
| **3** | Expose `monai_server` from Server 2, properly | no |
| **4** | One origin on Server 1: `ai-viewer.hospital.lan` | yes |
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
   Orthanc's `RegisteredUsers`. Least privilege: it needs QIDO/WADO read and STOW write,
   nothing else.
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

**Decision: rotate.** Runbook, in order. Each step is production-impacting and must be
verified before the next.

1. Choose the new password. Do not put it in any tracked file.
2. Update Orthanc's `RegisteredUsers` and restart Orthanc. Verify: direct `8042` still
   answers **401** to the old credential and **200** to the new one.
3. Update `orthanc-cors-proxy.conf`. **This is a single-file bind mount — truncate in
   place with `cat new > file`; `sed -i` and most editors replace the inode and leave the
   container serving stale content.** Verify with `docker exec orthanc-cors-proxy cat
   /etc/nginx/conf.d/default.conf`, then reload nginx.
4. Update the OpenMRS imaging module's Orthanc credentials **through the OpenMRS UI, not
   SQL** — global properties and module config are cached in memory and a direct `UPDATE`
   leaves the running app serving the old value.
5. Re-check every other consumer before declaring done: the imaging module's sync, the
   Stone and Orthanc-Explorer viewers, and anything in `patches/` or `backup files/` that
   carries the old value.
6. Consider whether the new secret belongs in a file that is tracked at all. Injecting it
   from an environment variable at container start keeps it out of the repository
   permanently and prevents a repeat.

> Do not treat rotation as complete until a **fresh clone of the public repo** has been
> checked for any remaining live-matching value — the same hash-comparison method
> `CLAUDE.md` used for the 2026-09-07 finding.

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
breaks months from now. Verify after building:

```bash
docker run --rm --entrypoint sh monai -c \
  "grep -c 'Slice range too wide' /code/monailabel/tasks/infer/basic_infer.py"   # expect 1
```

### 5.2 Bake the viewer's data source

`APP_CONFIG` is resolved at build time, not mounted — unlike the existing viewer's
`ohif-app-config.js`, which is a live bind mount. Pointing the AI viewer at the production
PACS therefore means a config file and a **rebuild** (~4 minutes).

The config must use **same-origin paths** (`/dicom-web`, `/wado`), and the bundled-Orthanc
`/pacs/` route must go. Verify:

```bash
docker run --rm --entrypoint sh webapp -c "grep -o 'dicom-web' /var/www/html/app-config.js"
docker run --rm --entrypoint sh webapp -c "du -sh /var/www/html"    # expect ~213 MB
```

---

## 6. Stage 3 — expose MONAI from Server 2

Follow `server2-stack/README.md` §"Adding another service later" — the mechanism is
already documented and proven by `clinical-agent`:

- **An overlay** `docker-compose.monai.yml`, with `expose:` and **never `ports:`**. A
  published port bypasses everything the proxy enforces.
- **A vhost template** modelled on `agent.conf.template`: TLS, an IP allowlist restricted
  to Server 1, a rate limit, and `return 404` outside the endpoints it means to expose.
- Extend `NGINX_ENVSUBST_FILTER` to cover the new variables, or nginx's own `$host` and
  `$remote_addr` get silently blanked.
- `client_max_body_size` large enough for SEG writes.

`monai_server` keeps its attachment to `server2_net` for vLLM, and **must keep `default`
listed alongside it** (`OHIF-AI.md` §10.2).

---

## 7. Stage 4 — one origin on Server 1

```
https://ai-viewer.hospital.lan            NPM on Server 1, TLS terminated here
  ├── /            → ohif-ai-viewer:80        Server 1, static bundles
  ├── /dicom-web/  → orthanc-cors-proxy:80    Server 1, unchanged, injects for the browser
  ├── /wado/       → orthanc-cors-proxy:80    Server 1, unchanged
  └── /monai/      → Server 2, via server2-proxy (TLS, allowlisted)
```

Checklist, each item a known trap in this stack:

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
