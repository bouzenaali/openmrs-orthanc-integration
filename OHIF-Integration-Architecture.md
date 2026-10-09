# OHIF integration — architecture and operations

**Project:** openmrs-orthanc-integration — Neurosurgery EMR, CHU Blida
**Server:** `server-PowerEdge-T140`, LAN `10.0.211.249` (`eno1`)
**Status:** verified end to end on 2026-08-30 — imaging module **1.2.0** deployed and
confirmed pruning stale studies; DICOMweb transport, TLS and auth injection all verified
**Applies to:** imaging module **1.2.0**, OHIF `ohif/app:v3.9.2`, Orthanc `orthancteam/orthanc:latest`

This document is the authoritative description of the OHIF integration.

It replaces two earlier documents that are **no longer present in this directory**:
`OHIF-Integration-Status.md` (which described an *intended* design that was never
deployed, and disagreed with reality on several points) and `OHIF-HTTPS-HANDOFF.md`
(a migration plan whose approach was superseded — see §3, "The design that was
rejected"). An archived copy of the former survives under
`../openmrs-orthanc-integration-backup_25_08_2026/`.

---

## 1. What the integration does

A clinician opens a patient in OpenMRS, sees their imaging studies, and clicks an
**OHIF** icon. OHIF opens in a new tab, loads that study from Orthanc over HTTPS, and
**never prompts for a password**.

Three viewers are reachable from the studies table. They are independent:

| Viewer | Served from | Purpose |
| --- | --- | --- |
| Stone Web Viewer | Orthanc itself | lightweight 2D review |
| Orthanc Explorer 2 | Orthanc itself | raw DICOM administration |
| **OHIF** | its own container | MPR, volume rendering, segmentation |

---

## 2. Component inventory

| Container | Image | Host port | Internal port |
| --- | --- | --- | --- |
| `openmrs-app` | `openmrs/openmrs-reference-application-distro` | 8080 | 8080 |
| `openmrs-mysql` | `mysql:8.0` | — | 3306 |
| `orthanc-pacs` | `orthancteam/orthanc` | 4242, 8042 | 4242, 8042 |
| `orthanc-postgres-db` | `postgres:13-alpine` | — | 5432 |
| `ohif-viewer` | `ohif/app:v3.9.2` | 3000 | **80** |
| `orthanc-cors-proxy` | `nginx:alpine` | 8043 | **80** |
| `nginx-proxy-manager-app-1` | `jc21/nginx-proxy-manager` | 80, 81, 443 | — |

> **The most common deployment mistake in this stack** is configuring a reverse proxy
> with the *host-published* port instead of the *internal* port. Nginx Proxy Manager
> connects over the Docker network, so `ohif-viewer` is port **80** (not 3000) and
> `orthanc-cors-proxy` is port **80** (not 8043). Using the published port yields
> `502 Bad Gateway`.

NPM is attached to **both** `nginx-proxy-manager_default` and
`openmrs-orthanc-integration_default`, which is why it can resolve the other
containers by name.

---

## 3. Request flow — the same-origin design

Everything the browser touches is served from **one origin**,
`https://viewer.hospital.lan`:

```
                        https://viewer.hospital.lan
                                    │
                    ┌───────────────┴────────────────┐
                    │  nginx-proxy-manager (host 3)  │   TLS terminates here
                    │  cert: /data/custom_ssl/npm-3  │
                    └───────────────┬────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        │ location /                │ location /dicom-web       │ location /wado
        ▼                           ▼                           ▼
  ohif-viewer:80            orthanc-cors-proxy:80       orthanc-cors-proxy:80
  (static OHIF app)                 │                           │
                                    │  injects Authorization    │
                                    └─────────────┬─────────────┘
                                                  ▼
                                            orthanc:8042
```

### Why one origin matters

OHIF's JavaScript fetches DICOM data with `fetch()`. If the viewer were served from
one origin and the data from another, every request would be **cross-origin**, requiring
CORS preflights, an `Access-Control-Allow-Origin` allowlist, and correct handling of the
`Authorization` header across origins.

By routing `/dicom-web` and `/wado` through the *same* hostname that serves the viewer,
the browser considers every request same-origin and **CORS never applies at all**.

This is the same reason the Stone Web Viewer has always worked: it is served from
`orthanc.hospital.lan` and fetches from `orthanc.hospital.lan`.

### The design that was rejected

An earlier plan pointed the existing `orthanc.hospital.lan` proxy host at
`orthanc-cors-proxy` and had OHIF call it cross-origin from `viewer.hospital.lan`. That
would have required a CORS `map` allowlist in the proxy config **and** modified a proxy
host already serving working Stone Viewer / Orthanc Explorer traffic. The same-origin
design needs neither, and leaves `orthanc.hospital.lan` untouched.

---

## 4. How authentication works

Orthanc requires HTTP Basic authentication:

```yaml
ORTHANC__AUTHENTICATION_ENABLED: "true"
ORTHANC__REGISTERED_USERS: { ... }        # in orthanc-docker-compose.yml
```

A direct request proves it:

```
curl http://localhost:8042/dicom-web/studies?limit=1
  -> 401  WWW-Authenticate: Basic realm="Orthanc Secure Area"
```

That 401 is what makes a browser display a native username/password dialog.

**`orthanc-cors-proxy` removes the prompt by authenticating on the server's behalf.**
Its config (`orthanc-cors-proxy.conf`, bind-mounted to
`/etc/nginx/conf.d/default.conf`) sets the header before forwarding:

```nginx
location / {
    proxy_set_header Authorization "Basic <base64 credentials>";
    proxy_pass http://orthanc:8042;
}
```

Because `proxy_set_header` **overwrites** any `Authorization` the client sent, the
browser never needs credentials and cannot influence them.

### Consequences to understand

1. **The browser never receives the Orthanc password.** Before 1.2.0 the credential sat
   in `ohif-app-config.js`, downloadable by anyone who loaded the viewer. It has been
   removed — the `requestOptions.auth` block is gone.
2. **Anything that can reach `https://viewer.hospital.lan/dicom-web` gets
   authenticated Orthanc access**, with no password, across the whole DICOMweb API.
   Access control is now *network-level*, not password-level. Restrict who can reach
   the host if that is not acceptable.
3. **A password prompt appearing in OHIF means the proxy has been bypassed.** Typing the
   password and seeing images is **not** a passing test — it proves the opposite.
   Always verify in a private/incognito window, since a browser that has cached Orthanc
   credentials will silently supply them and mask a broken chain.

### Two paths to Orthanc — do not confuse them

| Caller | Uses | Value | Goes through the proxy? |
| --- | --- | --- | --- |
| **OpenMRS server-side** (uploads, `/changes` poller, queries) | `orthancBaseUrl` | `http://orthanc:8042` | **No** — direct on the Docker network |
| **Browser links** (Stone Viewer, Orthanc Explorer) | `orthancProxyUrl` | `https://orthanc.hospital.lan` | No — NPM host 2 → `orthanc-pacs:8042` |
| **OHIF DICOMweb** | `ohif-app-config.js` roots | `https://viewer.hospital.lan/dicom-web` | **Yes** |

Both `orthancBaseUrl` and `orthancProxyUrl` live in the `imaging_OrthancConfiguration`
table, edited at **Administration → Configuring the Orthanc server**. They are *not*
global properties, and `imaging.ohifBaseUrl` is *not* stored there.

Because OpenMRS's own traffic goes direct to `orthanc:8042`, **changes to NPM or to
`orthanc-cors-proxy` cannot break DICOM uploads or study syncing.**

---

## 5. Configuration reference

### 5.1 Nginx Proxy Manager — host 3

Created at `http://10.0.211.249:81` → Hosts → Proxy Hosts.

| Setting | Value |
| --- | --- |
| Domain Names | `viewer.hospital.lan` |
| Scheme / Forward Host / Port | `http` / `ohif-viewer` / **80** |
| SSL certificate | `npm-3` (custom) |
| Force SSL | on |
| HTTP/2 | off |
| Block Common Exploits | **off** — it rejects some DICOMweb URLs with 403 |

Custom Locations:

| Location | Forward to | Port |
| --- | --- | --- |
| `/dicom-web` | `orthanc-cors-proxy` | 80 |
| `/wado` | `orthanc-cors-proxy` | 80 |

Generated config: `/data/nginx/proxy_host/3.conf` inside the container, which is
`/home/server/nginx-proxy-manager/data/nginx/proxy_host/3.conf` on the host.

The generated `proxy_pass http://orthanc-cors-proxy:80;` has **no URI component**, so
nginx forwards the original path unchanged — `/dicom-web/studies` arrives at Orthanc as
`/dicom-web/studies`. No rewrite is needed or wanted.

### 5.2 TLS

Certificate `npm-3` = `certificates/hospital.crt` + `hospital.key`, issued by CHU Blida's
own CA (`certificates/hospitalCA.crt`).

```
Subject : CN = openmrs.hospital.lan, O = CHU Blida, OU = Neurosurgery Department
SANs    : openmrs.hospital.lan, orthanc.hospital.lan, viewer.hospital.lan
Validity: 2026-08-05 .. 2036-08-02
```

`viewer.hospital.lan` is already a SAN, so **no new certificate is required**.

> `custom_ssl/npm-2` holds what appears to be the **CA** uploaded as a server
> certificate. Do not use it for proxy hosts — use `npm-3`.

**Clients must trust `hospitalCA.crt`**, or the browser shows a warning. Because OHIF is
same-origin, accepting the warning once is enough for the viewer to work — but installing
the CA is the correct fix:

```bash
sudo cp certificates/hospitalCA.crt /usr/local/share/ca-certificates/hospitalCA.crt
sudo update-ca-certificates
```

Chrome and Firefox keep their own trust stores and may need a separate import.

### 5.3 DNS

`viewer.hospital.lan` must resolve to `10.0.211.249` **on the client machine**, not on
the server. The server itself uses `8.8.8.8` and cannot resolve any `hospital.lan` name —
that is expected and must not be "fixed"; it does not affect the containers, which use
Docker's internal DNS.

For development on the server, an `/etc/hosts` entry is used:

```
10.0.211.249  viewer.hospital.lan
```

For clinical workstations, add an **A record on the internal DNS server** instead.

### 5.4 `ohif-app-config.js`

Bind-mounted read-only to `/usr/share/nginx/html/app-config.js`:

```js
wadoUriRoot: 'https://viewer.hospital.lan/wado',
qidoRoot:    'https://viewer.hospital.lan/dicom-web',
wadoRoot:    'https://viewer.hospital.lan/dicom-web',
imageRendering:     'wadors',
thumbnailRendering: 'wadors',
```

> **Single-file bind mount — inode trap.** Docker binds the *inode*, not the path.
> `sed -i` and most text editors write a new file and rename over the old one, leaving
> the container serving the **old** content. Always truncate in place:
> ```bash
> cp ohif-app-config.js ohif-app-config.js.bak-$(date +%Y%m%d-%H%M%S)
> cat new-version.js > ohif-app-config.js
> ```
> The same applies to `orthanc-cors-proxy.conf`.

Because both rendering modes are `wadors`, **`wadoUriRoot` is never called**. Orthanc
returns 404 for `/wado` in this deployment; that is harmless and needs no fix.

### 5.5 OpenMRS

Global property, at **Administration → Maintenance → Settings → Imaging**:

```
imaging.ohifBaseUrl = https://viewer.hospital.lan
```

No trailing slash, no surrounding whitespace. **When empty, the OHIF button does not
render** — this is deliberate, so the module degrades cleanly where OHIF is not deployed.

Set it through the UI, not by SQL: OpenMRS caches global properties in memory, and a
direct `UPDATE` leaves the running application serving the old value.

---

## 6. Deployment checklist

1. Containers up: `ohif-viewer`, `orthanc-cors-proxy`, `orthanc-pacs`, NPM
2. NPM host 3 created per §5.1 — **internal ports**, cert `npm-3`
3. `ohif-app-config.js` roots point at `https://viewer.hospital.lan/...`, no `auth` block
4. `viewer.hospital.lan` resolves to `10.0.211.249` from the **client**
5. `hospitalCA.crt` trusted on the client (or accept the warning once)
6. imaging module **1.2.0** installed and started
7. `imaging.ohifBaseUrl` set
8. Verify with §7

---

## 7. Verification

All read-only. `--resolve` overrides DNS for one request without changing anything, so
these work before a DNS record exists:

```bash
R="--resolve viewer.hospital.lan:443:10.0.211.249"

# viewer shell -> 200 text/html
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -k $R https://viewer.hospital.lan/

# DICOMweb  -> 200 application/dicom+json
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -k $R \
  'https://viewer.hospital.lan/dicom-web/studies?limit=1'

# auth injection working -> NO output (no WWW-Authenticate header)
curl -s -o /dev/null -D - -k $R \
  'https://viewer.hospital.lan/dicom-web/studies?limit=1' | grep -i www-authenticate
```

The authoritative record of what a browser actually did:

```bash
tail -40 /home/server/nginx-proxy-manager/data/logs/proxy-host-3_access.log
```

A working study load shows `/dicom-web/studies`, then `.../series/<uid>/metadata`, then
`.../instances/<uid>/frames/<n>` — all `200`. Frame requests are the pixel data; if they
return 200 and images still do not appear, the failure is **client-side rendering**, not
networking.

---

## 8. Known limitations

| Issue | Detail |
| --- | --- |
| **No GPU on this server** | Matrox G200eW3, no DRI render node, OpenGL via `llvmpipe` (software). OHIF v3.9 needs **WebGL2** (not WebGPU). Recent Chrome removed automatic software-WebGL fallback, so images may not render *on the server*. Launch with `--enable-unsafe-swiftshader`, or use a workstation with a GPU. Prefer `/viewer` (stack) over `/segmentation` (volume). |
| **Segmentation saving** | **Resolved 2026-09-01.** `orthanc-cors-proxy` is stock `nginx:alpine`, whose 1 MB `client_max_body_size` rejected OHIF's STOW-RS writes; `client_max_body_size 0;` is now set at the server level. Verified: a 20 MB POST traverses the full chain. NPM was never a second ceiling — it sets `2000m` in its own `nginx.conf`. Saving from a client is still to be confirmed in use. |
| **CA trust** | Untrusted CA produces a browser warning. Same-origin means accepting once is sufficient, but install the CA properly for clinical use. |
| **`/wado` returns 404** | WADO-URI is not enabled in Orthanc. Harmless — `wadors` rendering never calls it. |
| **Network-level access control** | See §4: reaching the host grants authenticated Orthanc access. |
| **NPM network attachment is not declarative** | `nginx-proxy-manager/docker-compose.yml` has **no `networks:` section**, yet NPM must sit on `openmrs-orthanc-integration_default` to resolve `ohif-viewer` and `orthanc-cors-proxy`. The attachment was made manually. **Recreating the NPM container drops it, and every proxy host starts returning 502.** Re-attach with `docker network connect openmrs-orthanc-integration_default nginx-proxy-manager-app-1`, or add the network to its compose file as `external: true`. |
| **Credentials publicly exposed on GitHub** | Repo is public; `origin/main`'s tip carries a committed `.env` and `orthanc-docker-compose.yml` confirmed (2026-09-07, SHA-256, values never printed) to match what's live. **Accepted risk for this development environment** — explicit decision, not an oversight. Full detail and the mandatory pre-production checklist: `HANDOFF-2026-08-30.md` §4.3. |
| **DB connection pool (c3p0)** | `min_size=2` set 2026-09-07 to stop the pool shrinking to zero idle connections before its 50-min validation cycle runs (root-caused the Aug 30 `500`s). Effectiveness against a real multi-day idle period **not yet confirmed** — see `HANDOFF-2026-08-30.md` §4.1. |
| **Orthanc deletions** | Orthanc's `/changes` log contains **no** deletion events (change rows are removed with the resource). Only a full "Get studies" reconciles removals — see the module README. |

---

## 9. Rollback

| Change | Rollback |
| --- | --- |
| NPM host 3 | Delete the proxy host in the NPM UI. Nothing else depends on it. |
| `ohif-app-config.js` | `cat ohif-app-config.js.bak-<timestamp> > ohif-app-config.js` (inode-preserving) |
| `orthanc-cors-proxy.conf` | same pattern, then `docker exec orthanc-cors-proxy nginx -t && docker exec orthanc-cors-proxy nginx -s reload` |
| imaging module | Re-upload the previous `.omod` via Manage Modules |
| `imaging.ohifBaseUrl` | Clear the value — the button disappears |

> **None of the operational config is in git.** `origin/main` holds only a README and an
> old submodule pointer. `git checkout <file>` is **not** a rollback path — use the
> timestamped `.bak-*` copies.
