# The proxy architecture — Server 1 and Server 2

**Project:** openmrs-orthanc-integration — Neurosurgery EMR, CHU Blida
**Status (2026-09-30):** **VERIFIED END TO END.** Every path below was measured against the
running system; the numbers in §8 are that measurement, not an intention.
**Companions:** `OHIF-AI.md` (what OHIF-AI is), `ohif-ai-integration.md` (how it was
integrated), `OHIF-Integration-Architecture.md` (the original viewer).

There are **four** nginx instances between a clinician and a GPU. This document explains
what each one does and why, because the failure modes are not guessable from any single one
of them.

---

## 1. The one rule that explains the whole design

**The browser must see a single origin.**

A viewer that loads from one hostname and fetches DICOM from another is a cross-origin
setup: every request needs a CORS preflight, and the credential Orthanc demands has to live
somewhere the page's JavaScript can reach — which means it is not a secret.

So each viewer gets **one hostname**, and the proxy fans that hostname out to the static
files, the PACS and the GPU backend. The browser never learns that three different machines
answered. And because the PACS credential is added by a proxy the browser cannot see, it
never leaves the server side.

> **The test that matters:** open the viewer in a private window. If it asks for a password,
> that is a **failure**, not a pass — it means the injecting proxy was bypassed and the
> browser is talking to Orthanc directly.

---

## 2. The whole path, end to end

```
                         CLINICIAN'S BROWSER
                                 │
                                 │  https://ai-viewer.hospital.lan/...
                                 ▼
╔════════════════════════ SERVER 1  10.0.211.249 ════════════════════════╗
║                                                                        ║
║   ┌──────────────────────────────────────────────────────────────┐    ║
║   │  Nginx Proxy Manager      (the only thing on :80 and :443)   │    ║
║   │  TLS terminates here. Five hostnames, one fan-out:           │    ║
║   │                                                              │    ║
║   │   /            ──http──▶  ohif-ai-viewer:80   (static files) │    ║
║   │   /dicom-web   ──http──▶  orthanc-cors-proxy:80 ─┐           │    ║
║   │   /wado        ──http──▶  orthanc-cors-proxy:80 ─┤           │    ║
║   │   /monai/      ──https─▶  monai.hospital.lan:443 │  (Server 2)│   ║
║   └──────────────────────────────────────────────────┼───────────┘    ║
║                                                      │                ║
║                      ┌───────────────────────────────┘                ║
║                      ▼                                                ║
║   ┌──────────────────────────────────────────┐                        ║
║   │  orthanc-cors-proxy  (nginx:alpine)      │                        ║
║   │  ADDS: Authorization: Basic <pacsadmin>  │◀── the whole reason    ║
║   │  client_max_body_size 0  (SEG writes)    │    no password prompt  ║
║   └──────────────────┬───────────────────────┘    ever appears        ║
║                      ▼                                                ║
║   ┌──────────────────────────────────────────┐                        ║
║   │  orthanc-pacs :8042                      │                        ║
║   │  AUTHENTICATION_ENABLED=true             │                        ║
║   │  users: pacsadmin, monai                 │                        ║
║   └──────────────────────────────────────────┘                        ║
╚════════════════════════════════════════════════════════════════════════╝
                                 │
                                 │  https://monai.hospital.lan/...
                                 ▼
╔════════════════════════ SERVER 2  10.0.211.250 ════════════════════════╗
║                              (the GPU host)                            ║
║   ┌──────────────────────────────────────────────────────────────┐    ║
║   │  server2-proxy   (the ONLY container publishing a port)      │    ║
║   │  TLS + IP allowlist (Server 1 only) + PATH allowlist          │    ║
║   │                                                              │    ║
║   │   /info/      ──▶ monai_server:8002    200                   │    ║
║   │   /infer/     ──▶ monai_server:8002    200                   │    ║
║   │   /nninter/   ──▶ monai_server:8002    200                   │    ║
║   │   everything else                      404  ◀── /train/ too  │    ║
║   └──────────────────┬───────────────────────────────────────────┘    ║
║                      ▼                                                ║
║   ┌──────────────────────────────────────────┐                        ║
║   │  monai_server  (expose 8002, no ports:)  │                        ║
║   │  nnInteractive · SAM2 · MedSAM2 · VoxTell│                        ║
║   │  RTX 5070 Ti                             │                        ║
║   └───────┬──────────────────────────┬───────┘                        ║
║           │                          │                                ║
║           │ reports                  │ fetches the images it segments ║
║           ▼                          ▼                                ║
║   ┌───────────────┐    https://pacs-api.hospital.lan/dicom-web        ║
║   │ vllm :8000    │              (back to Server 1, authenticated     ║
║   │ MedGemma 1.5  │               as the `monai` account)             ║
║   └───────────────┘                                                   ║
╚════════════════════════════════════════════════════════════════════════╝
```

**The part that surprises people:** the arrow at the bottom. `monai_server` is not a passive
backend — it downloads the series itself to run inference, so the PACS has **two**
independent consumers, and Server 2 reaches back into Server 1 to be one of them.

---

## 3. Server 1 — Nginx Proxy Manager

The only container publishing 80/443. TLS terminates here for every hostname.

| # | Hostname | Location | Forwards to | Scheme |
| --- | --- | --- | --- | --- |
| 1 | `openmrs.hospital.lan` | `/` | `openmrs-app:8080` | http |
| 2 | `orthanc.hospital.lan` | `/` | `orthanc-pacs:8042` | http |
| 3 | `viewer.hospital.lan` | `/` | `ohif-viewer:80` | http |
| | | `/dicom-web`, `/wado` | `orthanc-cors-proxy:80` | http |
| 4 | `ai-viewer.hospital.lan` | `/` | `ohif-ai-viewer:80` | http |
| | | `/dicom-web`, `/wado` | `orthanc-cors-proxy:80` | http |
| | | `/monai/` | `monai.hospital.lan:443` | **https** |
| 5 | `pacs-api.hospital.lan` | `/` | `orthanc-pacs:8042` | http |

**Only one hop is HTTPS** — the one that crosses machines. Everything else is plain HTTP on
the Docker network, because TLS already terminated and the traffic never leaves the host.
Setting those to `https` produces `SSL: wrong version number` and a 502.

**Ports are INTERNAL, not published.** `ohif-viewer` is **80**, not 3000.
`orthanc-cors-proxy` is **80**, not 8043. NPM connects over the Docker network, so the
published port is the wrong number. This is the most common deployment mistake here and it
presents as a flat 502.

**Block Common Exploits must be OFF on hosts 3, 4 and 5.** It rejects some DICOMweb paths
with 403. Simple QIDO survives it, so it looks fine until a segmentation write fails.

---

## 4. Server 1 — `orthanc-cors-proxy`, the piece that matters most

A stock `nginx:alpine` whose entire job is one line:

```nginx
proxy_set_header Authorization "Basic <base64 of pacsadmin:...>";
proxy_pass http://orthanc:8042;
```

Orthanc runs with `AUTHENTICATION_ENABLED=true` and would answer `401`, which the browser
turns into a native password box. This proxy supplies the credential **server-side**, so the
page never holds one and no prompt appears. Its name is historical — CORS is vestigial in
the same-origin design; authentication injection is what it actually does.

It also sets `client_max_body_size 0`, because saving a DICOM SEG is a large `STOW-RS` POST
and nginx's 1 MB default rejects it.

> **This file is a single-file bind mount.** Docker binds the **inode**. `sed -i` and most
> editors write a new file, leaving the container serving the old content — and a
> `git checkout` that restores it has the same effect in reverse. Edit with
> `cat new > file`, then confirm with
> `docker exec orthanc-cors-proxy cat /etc/nginx/conf.d/default.conf`.
>
> It is **untracked on every branch** for the same reason it must be: it contains a live
> credential. `orthanc-cors-proxy.conf.example` carries the shape.

---

## 5. Server 1 — the viewer container's own nginx

`ohif-ai-viewer` serves static bundles and nothing else; NPM does all routing.

Two details that are not obvious:

- **The bundles live in `/var/www/html`**, not nginx's default root. Looking in
  `/usr/share/nginx/html` shows nginx's stock placeholder and gives the false impression of
  an empty build.
- **`/sw.js` must never be cached.** OHIF-AI ships a service worker, and a cached one pins
  clients to an old build. Service workers also only register in a **secure context** —
  which is one more reason this viewer is HTTPS-only rather than "HTTPS when convenient".

---

## 6. Server 2 — `server2-proxy`

The only container on that host publishing a port. Three vhosts, all TLS, all closed to
everything except Server 1:

| vhost | Backend | Exposes |
| --- | --- | --- |
| `agent.hospital.lan` | `clinical-agent:8000` | `/chat`, `/capabilities`, `/health` |
| `stt.hospital.lan` | `stt-gateway:8000` | dictation |
| `monai.hospital.lan` | `monai_server:8002` | `/info/`, `/infer/`, `/nninter/` |

Everything outside each list returns **404**. For MONAI that is deliberate and
evidence-based: across the entire evaluation the viewer requested exactly
`POST /infer/segmentation` (112), `POST /nninter/session/` and its releases (14), and
`GET /info/` (1). MONAI Label also serves `/datastore/...` and **`/train/`** — a browser
being able to start model training on the hospital's GPU is not theoretical.

**This is where least privilege actually lives.** Orthanc has no authorization plugin, so
the `monai` account has full rights *there*. Restricting by path and by source address here
is what bounds it, and it needs no Orthanc plugin.

Plain HTTP to these names returns **444** — connection closed, no redirect. A redirect would
mean the request had already been sent in clear text.

---

## 7. The `/monai/` hop, and the two traps in it

This single hop cost more debugging than everything else combined. Both traps come from the
same root cause: **NPM generates its own directives, and anything repeated in its Advanced
box is sent twice, not overridden.**

The Advanced block for the `/monai/` location must be exactly:

```nginx
rewrite ^/monai/(.*)$ /$1 break;
proxy_ssl_server_name on;
```

**Trap 1 — no `proxy_pass` here.** NPM already emits one for a custom location. A second is
`nginx: [emerg] "proxy_pass" directive is duplicate`, the config fails its test, and **NPM
silently deletes the whole host file and reloads without it**. The symptom is not an error
message: it is TLS answering `unrecognized name`, because the vhost no longer exists.

**Trap 2 — no `proxy_set_header Host` here.** NPM emits `Host $host` *after* the Advanced
block, so a `Host` set here does not override it — nginx sends **both**, and the upstream
nginx rejects a request carrying two `Host` headers with **400 Bad Request**. The request
reaches Server 2, matches the vhost, and is refused before ever touching `monai_server`.

Because `Host` cannot be rewritten from NPM, **Server 2's vhost accepts the forwarded name
instead**:

```nginx
server_name ${MONAI_SERVER_NAME} ${MONAI_CLIENT_SERVER_NAME};
```

TLS is unaffected — the certificate is still chosen by SNI, which remains
`monai.hospital.lan`.

**Why the `rewrite`:** the browser calls `/monai/info/`, but MONAI Label serves `/info/`.
Without stripping the prefix every request lands in the catch-all and returns 404 — which
looks exactly like the path allowlist being wrong.

---

## 8. Verification — measured 2026-09-30

```
https://ai-viewer.hospital.lan/                     200
https://ai-viewer.hospital.lan/app-config.js        200
https://ai-viewer.hospital.lan/dicom-web/studies    200   -> 2 studies
https://ai-viewer.hospital.lan/monai/info/          200
https://ai-viewer.hospital.lan/monai/train/         404   <- allowlist holds end to end
https://ai-viewer.hospital.lan/monai/datastore/label 404

WWW-Authenticate header            : none        <- no password prompt
Authorization headers sent by client: 0          <- credential is server-side only
pacs-api with the monai credential : 200
pacs-api without any credential    : 401
plain HTTP to monai.hospital.lan   : connection closed (444)
```

Reproduce without touching DNS — `--resolve` overrides it for one request and changes
nothing:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  --cacert /home/server/certificates/hospitalCA.crt \
  --resolve ai-viewer.hospital.lan:443:10.0.211.249 \
  https://ai-viewer.hospital.lan/dicom-web/studies
```

`~/nginx-proxy-manager/data/logs/proxy-host-4_access.log` is the authoritative record of
what a browser actually did. Prefer it over inferring from what the browser appeared to do.

---

## 9. Certificates

| Cert | Covers | Used by |
| --- | --- | --- |
| NPM `npm-3` | `openmrs`, `orthanc`, `viewer` | hosts 1–3 |
| NPM `npm-4` | `ai-viewer`, `pacs-api` | hosts 4–5 |
| Server 2 `agent.crt` | `agent`, `stt`, `monai`, `localhost`, both IPs | all three vhosts |

All are signed by **hospitalCA**, so any machine that already trusts the hospital CA needs
nothing extra.

`npm-4` is deliberately **separate** rather than an extension of `npm-3`: all three existing
hosts share `npm-3`, and re-issuing it would have put OpenMRS, Orthanc and the viewer at
risk to add two names.

> **`server2-stack/1-make-agent-csr.sh` is unsafe to re-run.** It regenerates
> `agent-san.cnf` from a heredoc listing only the agent hostname, `localhost` and the IPs —
> it knows nothing about `stt` or `monai`, which were added by hand. Re-running it would
> silently issue a certificate that breaks the STT and MONAI vhosts. Fix the script before
> trusting it.

---

## 10. Known gaps

| Gap | Consequence |
| --- | --- |
| **`pacs-api` has no source restriction.** §4.1 called for it to be limited to Server 2; it is not. | Any host on the LAN can reach it, but it **passes credentials through rather than injecting them**, so an unauthenticated request gets 401. It is not a bypass — unlike port 8043 — but the defence-in-depth layer is missing. |
| ~~Port 8043 published to the LAN~~ | **CLOSED 2026-10-04.** `orthanc-cors-proxy` publishes nothing; verified unreachable from Server 2 while both viewers still answer 200. |
| **`Task: Use AI Imaging Viewer` is assigned to no role.** | Only superusers, who bypass privilege checks, can see the button. |
| **`stt-engine` is stopped.** | Dictation unavailable; stopped during GPU testing and not yet restarted. |

---

## 11. Where the computation actually happens

This matters more than it looks: **the AI runs on Server 2's GPU, but the *viewing* runs on
the GPU of whatever machine the clinician is sitting at.** A workstation that cannot do
WebGL2 shows a black viewport even though every server is healthy.

| Step | Runs on | Needs |
| --- | --- | --- |
| Study list, thumbnails, window/level, zoom, pan, scroll | **the clinician's browser** | WebGL2 + a working GPU render node |
| Decoding pixel data (compressed transfer syntaxes) | **the clinician's browser** | WASM codecs, CPU |
| **MPR / axial / dual view — building a 3D volume from slices** | **the clinician's browser** | WebGL2, VRAM ∝ volume size, **and per-slice 3D geometry in the DICOM** |
| Measurements, annotations, segmentation brush | **the clinician's browser** | WebGL2 |
| DICOM storage, QIDO / WADO / STOW, index queries | **Server 1 CPU** (Orthanc) | no GPU at all |
| Credential injection, TLS, routing | **Server 1 CPU** (nginx ×3) | trivial |
| **AI segmentation** — nnInteractive, SAM2, MedSAM2, VoxTell | **Server 2 GPU** | the RTX 5070 Ti |
| **Report generation** — MedGemma 1.5 | **Server 2 GPU** (vLLM) | the RTX 5070 Ti |
| Fetching the series to segment, and writing the SEG back | **Server 2 CPU**, pulling from Server 1 | the `pacs-api` route |

> **Neither server renders images for anybody.** Server 1 has no GPU render node at all
> (`/dev/dri` has no `renderD128`; the Matrox G200eW3 is a BMC display chip). Server 2 has
> one, but it is there for inference, not for looking at pictures.
>
> **So the viewer must be opened from a clinical workstation, not from a server's desktop.**
> Browsing from Server 1 means software rendering (llvmpipe): minutes to load, or black.

---

## 12. Black viewports and slow loading — diagnosed 2026-09-30

Three independent causes were found, **none of them in the proxy chain**. Metadata comes
back through the full chain in 0.05–0.24 s, so the network is not the problem.

### 12.1 The study has no 3D geometry, so MPR can never work

Study `1.2.826.0.1.3680043.8.853.2.1116259` ("IRM CEREBRALE", 80 instances):

```
SOPClassUID              1.2.840.10008.5.1.4.1.1.7    <- Secondary Capture, not MR Image
SeriesDescription        "AW electronic film"          <- a GE workstation film sheet
ImagePositionPatient     absent on all 20 sampled instances
ImageOrientationPatient  absent on all 20 sampled instances
SliceThickness           absent
SpacingBetweenSlices     absent
```

A volume is built from **where each slice sits in space**. Without
`ImagePositionPatient` and `ImageOrientationPatient` there is no geometry, so no volume, so
**MPR, axial and dual-view viewports are black by necessity** — in OHIF, OHIF-AI, or any
other viewer. This is a property of the data, not of the deployment.

"AW electronic film" is a screenshot of a workstation layout: a picture of images, not the
acquisition. The original MR series — the one with geometry — is what supports MPR. If it
was never sent to the PACS, no viewer can reconstruct it.

> This also means **nnInteractive cannot segment such a series usefully**: it is a 3D model,
> and there is no third dimension here.

`OHIF-Integration-Architecture.md` already carried the shorter version of this advice:
*prefer `/viewer` (stack) over `/segmentation` (volume)*.

### 12.2 Server 1 cannot render at all

```
/dev/dri:  by-path  card1       <- no renderD128, i.e. no render node
GPU:       Matrox G200eW3       <- BMC display chip, no 3D
```

Firefox on Server 1 falls back to software rendering. For a 42.5 MB series that is minutes,
or a blank viewport. **Expected, documented, and not fixable on that machine** — it is a
server, not a display.

### 12.3 Server 2 renders, but the desktop is being viewed remotely

Server 2 does have a render node (`renderD128`) and 4.4 GB of free VRAM. But it runs a
**Wayland** session reached over **RustDesk**, and hardware-accelerated WebGL surfaces are
a well-known weak point for remote-desktop capture: the page chrome draws fine while the
accelerated canvas comes through black.

Not proven here — it needs a comparison that only someone at the machine can make. To test,
open the same study **at Server 2's physical console** and compare with the RustDesk
session. If the console renders and RustDesk does not, that is the cause.

### 12.4 What to check in Firefox

`about:support` → the **Graphics** section:

- `WEBGL2_RENDERER` — if it says `llvmpipe` or `softpipe`, rendering is on the CPU and slow
  or black viewports are expected.
- `Compositing` — `WebRender` (GPU) versus `WebRender (Software)`.
- WebGPU is **not** required; OHIF uses WebGL2.

### 12.5 The short version

| Symptom | Cause | Fix |
| --- | --- | --- |
| MPR / axial / dual view black | the series has no 3D geometry (§12.1) | use a real acquisition series, not "electronic film" |
| Slow or black on Server 1 | no GPU render node (§12.2) | do not view from a server — use a clinical workstation |
| Black on Server 2's remote desktop | probably WebGL over RustDesk (§12.3) | compare at the physical console |
