/*
 * Imaging observation reports (use case 1) - the behaviour behind the fragment the imaging
 * module includes on the patient's Imaging tab.
 *
 * Three workflows, which is what the panel is laid out around:
 *   1. write a report covering one OR SEVERAL images (checkbox picker, not a UID prompt);
 *   2. pick an image and read every report about it, newest first, each showing its author;
 *   3. filter to your own reports and manage them (edit / remove), plus admin history.
 *
 * The capability flags this file reads (canEdit / canRemove / isAdmin) come from the server
 * on every response and are recomputed per user per report. They decide what is *rendered*;
 * they decide nothing about what is *allowed*. Every action re-posts to an endpoint that
 * re-checks the privilege and the authorship server-side.
 */
var medreportImaging = (function () {
    'use strict';

    var cfg = {};
    var editing = null;    // report uuid when editing, null when creating
    var picked = [];       // images the report being edited covers (RP9: a list, not one)
    var lastReports = [];

    var SEP = ' · ';

    function $(id) { return document.getElementById(id); }

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        // textContent, never innerHTML: study descriptions come from DICOM and report text
        // from clinicians; neither is markup we control.
        if (text !== undefined && text !== null) { node.textContent = text; }
        return node;
    }

    function m(key) { return (cfg.messages && cfg.messages[key]) || key; }

    // ---------------------------------------------------------------
    // transport
    // ---------------------------------------------------------------

    function readJson(response) {
        return response.json().catch(function () {
            return { success: false, message: 'HTTP ' + response.status };
        }).then(function (body) {
            if (!response.ok || body.success === false) {
                var error = new Error(body.message || ('HTTP ' + response.status));
                error.status = response.status;
                throw error;
            }
            return body;
        });
    }

    function get(url) {
        return fetch(url, { credentials: 'same-origin' }).then(readJson);
    }

    function post(url, params) {
        var body = [];
        for (var key in params) {
            if (Object.prototype.hasOwnProperty.call(params, key)) {
                body.push(encodeURIComponent(key) + '=' + encodeURIComponent(params[key]));
            }
        }
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: body.join('&')
        }).then(readJson);
    }

    function notify(target, kind, text) {
        var box = $(target);
        if (!box) { return; }
        box.className = 'mr-note mr-note-' + kind;
        box.textContent = text;
        box.hidden = false;
    }

    function clear(target) {
        var box = $(target);
        if (box) { box.hidden = true; }
    }

    // ---------------------------------------------------------------
    // image helpers
    // ---------------------------------------------------------------

    function imageLabel(image) {
        var bits = [];
        if (image.modality) { bits.push(image.modality); }
        if (image.studyDescription) { bits.push(image.studyDescription); }
        if (image.studyDate) { bits.push(image.studyDate); }
        if (!bits.length) {
            bits.push(m('studyPrefix') + ' ' + (image.seriesUid || image.studyUid || '?'));
        }
        return bits.join(SEP);
    }

    function imageKey(image) {
        return (image.studyUid || '') + '|' + (image.seriesUid || '');
    }

    /** Images the host page listed, plus anything added by UID during this edit. */
    function knownImages() {
        var all = (cfg.availableImages || []).slice();
        picked.forEach(function (image) {
            var already = all.some(function (candidate) {
                return imageKey(candidate) === imageKey(image);
            });
            if (!already) { all.push(image); }
        });
        return all;
    }

    // ---------------------------------------------------------------
    // 2. read reports, optionally scoped to one image
    // ---------------------------------------------------------------

    function currentFilter() {
        var select = $('mr-image-filter');
        return select ? select.value : '';
    }

    function listUrl() {
        var filter = currentFilter();
        if (filter) {
            var parts = filter.split('|');
            // A series is more specific than its study, so prefer it when one was chosen.
            if (parts[1]) {
                return cfg.base + '/imageReports.form?seriesUid=' + encodeURIComponent(parts[1]);
            }
            return cfg.base + '/imageReports.form?studyUid=' + encodeURIComponent(parts[0]);
        }
        if (cfg.scopeSeriesUid) {
            return cfg.base + '/imageReports.form?seriesUid=' + encodeURIComponent(cfg.scopeSeriesUid);
        }
        if (cfg.scopeStudyUid) {
            return cfg.base + '/imageReports.form?studyUid=' + encodeURIComponent(cfg.scopeStudyUid);
        }
        return cfg.base + '/imageReports.form?patientId=' + encodeURIComponent(cfg.patientId);
    }

    function reload() {
        clear('mr-imaging-notice');
        get(listUrl()).then(function (body) {
            lastReports = body.reports || [];
            render();
        }).catch(function (error) {
            notify('mr-imaging-notice', 'error', error.message);
            lastReports = [];
            render();
        });
    }

    function visibleReports() {
        var mineOnly = $('mr-mine-only') && $('mr-mine-only').checked;
        return lastReports.filter(function (report) {
            return !mineOnly || report.isOwn;
        });
    }

    function render() {
        var host = $('mr-report-list');
        if (!host) { return; }
        host.innerHTML = '';

        var reports = visibleReports();
        var summary = $('mr-list-summary');
        if (summary) {
            summary.textContent = reports.length + ' ' + m('reportCount');
        }

        if (!reports.length) {
            var message = m('none');
            if ($('mr-mine-only') && $('mr-mine-only').checked) {
                message = m('noneMine');
            } else if (currentFilter() || cfg.scopeStudyUid || cfg.scopeSeriesUid) {
                message = m('noneForImage');
            }
            host.appendChild(el('p', 'mr-empty', message));
            return;
        }

        // The server already orders newest first; keep that as the visible contract.
        reports.forEach(function (report) {
            host.appendChild(renderReport(report));
        });
    }

    function renderReport(report) {
        var current = report.current || {};
        var card = el('div', 'mr-report'
            + (report.isOwn ? ' mr-own' : '')
            + (report.voided ? ' mr-removed' : ''));

        var head = el('div', 'mr-report-head');
        head.appendChild(el('span', 'mr-report-title', current.title || m('newReport')));
        if (report.isOwn) {
            head.appendChild(el('span', 'mr-tag mr-tag-own', m('own')));
        }
        if (report.voided) {
            head.appendChild(el('span', 'mr-tag mr-tag-removed', m('removed')));
        }
        card.appendChild(head);

        // RP2: every report view shows its author, one's own or anyone else's.
        var meta = el('div', 'mr-meta');
        meta.appendChild(document.createTextNode(m('author') + ' : '));
        meta.appendChild(el('strong', null, report.author || '-'));
        meta.appendChild(document.createTextNode(
            SEP + (current.dateCreated || '') + SEP + m('version') + ' ' + (current.versionNumber || 1)));
        card.appendChild(meta);

        if (current.observationText) {
            card.appendChild(el('div', 'mr-report-text', current.observationText));
        }

        if (current.images && current.images.length) {
            var chips = el('div', 'mr-images');
            current.images.forEach(function (image) {
                chips.appendChild(el('span', 'mr-image-chip', image.label || image.studyUid));
            });
            card.appendChild(chips);
        }

        card.appendChild(renderActions(report, current));
        return card;
    }

    function renderActions(report, current) {
        var actions = el('div', 'mr-report-actions');

        if (current.hasDocument) {
            var download = el('a', 'mr-btn mr-btn-small', m('download'));
            download.href = cfg.base + '/imageReportDocument.form?versionUuid='
                          + encodeURIComponent(current.uuid);
            actions.appendChild(download);
        }

        // RP3/RP5: edit and remove need the manage privilege AND authorship. For someone
        // else's report the buttons render disabled with an explanation, so it reads as
        // "not yours" rather than as a broken UI.
        actions.appendChild(actionButton(m('edit'), report.canEdit, function () {
            openEditor(report, current);
        }, report.isOwn ? null : m('notOwner')));

        actions.appendChild(actionButton(m('remove'), report.canRemove, function () {
            removeReport(report);
        }, report.isOwn ? null : m('notOwner'), 'mr-btn-danger'));

        // RP4: the version chain is administrator-only - the author does not see it either.
        if (cfg.isAdmin) {
            actions.appendChild(actionButton(m('history'), true, function () {
                openHistory(report);
            }));
            if (report.voided) {
                actions.appendChild(actionButton(m('restore'), true, function () {
                    restoreReport(report);
                }));
            }
        }

        return actions;
    }

    function actionButton(label, enabled, handler, disabledReason, extraClass) {
        var button = el('button', 'mr-btn mr-btn-small' + (extraClass ? ' ' + extraClass : ''), label);
        button.type = 'button';
        if (enabled) {
            button.addEventListener('click', handler);
        } else {
            button.disabled = true;
            if (disabledReason) { button.title = disabledReason; }
        }
        return button;
    }

    // ---------------------------------------------------------------
    // 1. create / edit, covering one or many images
    // ---------------------------------------------------------------

    function defaultSelection() {
        // Scoped page (one study/series open): preselect it. Patient-wide page: preselect
        // whatever the image filter is pointing at, else nothing - the clinician chooses.
        if (cfg.scopeStudyUid || cfg.scopeSeriesUid) {
            return [{
                studyUid: cfg.scopeStudyUid,
                seriesUid: cfg.scopeSeriesUid || null
            }];
        }
        var filter = currentFilter();
        if (filter) {
            var parts = filter.split('|');
            var match = (cfg.availableImages || []).filter(function (image) {
                return image.studyUid === parts[0] && (image.seriesUid || '') === (parts[1] || '');
            });
            if (match.length) { return [match[0]]; }
        }
        return [];
    }

    function openEditor(report, current) {
        editing = report ? report.uuid : null;
        clear('mr-editor-notice');

        $('mr-editor-title').textContent = report ? m('edit') : m('newReport');
        $('mr-report-title').value = (current && current.title) || '';
        $('mr-report-text').value = (current && current.observationText) || '';
        $('mr-reason-field').hidden = !report;
        $('mr-change-reason').value = '';

        picked = report && current && current.images && current.images.length
            ? current.images.map(function (image) {
                  return {
                      studyUid: image.studyUid,
                      seriesUid: image.seriesUid || null,
                      studyInstanceUid: image.studyInstanceUid || null,
                      modality: image.modality || null,
                      studyDate: image.studyDate || null,
                      studyDescription: image.studyDescription || null
                  };
              })
            : defaultSelection();

        renderPicker();
        $('mr-editor-overlay').hidden = false;
        $('mr-report-text').focus();
    }

    function closeEditor() {
        $('mr-editor-overlay').hidden = true;
        editing = null;
    }

    function isPicked(image) {
        return picked.some(function (candidate) {
            return imageKey(candidate) === imageKey(image);
        });
    }

    function renderPicker() {
        var host = $('mr-image-picker');
        if (!host) { return; }
        host.innerHTML = '';

        var all = knownImages();
        if (!all.length) {
            host.appendChild(el('p', 'mr-empty', m('noImages')));
        }

        all.forEach(function (image) {
            var row = el('label', 'mr-check');
            var box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = isPicked(image);
            box.addEventListener('change', function () {
                if (box.checked) {
                    if (!isPicked(image)) { picked.push(image); }
                } else {
                    picked = picked.filter(function (candidate) {
                        return imageKey(candidate) !== imageKey(image);
                    });
                }
                updatePickerCount();
            });
            row.appendChild(box);
            row.appendChild(el('span', null, imageLabel(image)));
            host.appendChild(row);
        });

        updatePickerCount();
    }

    function updatePickerCount() {
        var badge = $('mr-image-count');
        if (badge) {
            badge.textContent = picked.length + ' ' + m('selectedCount');
        }
    }

    function addImageByUid() {
        // The imaging module owns the study browser; rebuilding one here would move imaging
        // logic into medreport. This is only the escape hatch for a study the host page did
        // not list (e.g. a prior scan from another episode).
        var uid = window.prompt(m('addImagePrompt'));
        if (!uid || !uid.trim()) { return; }
        uid = uid.trim();

        var image = { studyUid: uid, seriesUid: null };
        if (!isPicked(image)) { picked.push(image); }
        renderPicker();
    }

    function save() {
        var text = $('mr-report-text').value;
        if (!text || !text.trim()) {
            notify('mr-editor-notice', 'error', m('emptyText'));
            return;
        }
        if (!picked.length) {
            notify('mr-editor-notice', 'error', m('noImages'));
            return;
        }

        var button = $('mr-editor-save');
        button.disabled = true;

        var payload = {
            title: $('mr-report-title').value || '',
            observationText: text,
            images: JSON.stringify(picked.map(function (image) {
                return {
                    studyUid: image.studyUid,
                    seriesUid: image.seriesUid || null,
                    studyInstanceUid: image.studyInstanceUid || null,
                    modality: image.modality || null,
                    studyDate: image.studyDate || null,
                    studyDescription: image.studyDescription || null
                };
            }))
        };

        var wasEditing = !!editing;
        var request;
        if (wasEditing) {
            payload.reportUuid = editing;
            payload.changeReason = $('mr-change-reason').value || '';
            request = post(cfg.base + '/imageReportUpdate.form', payload);
        } else {
            payload.patientId = cfg.patientId;
            request = post(cfg.base + '/imageReports.form', payload);
        }

        request.then(function () {
            closeEditor();
            notify('mr-imaging-notice', 'ok', wasEditing ? m('updated') : m('saved'));
            reload();
        }).catch(function (error) {
            notify('mr-editor-notice', 'error', error.message);
        }).then(function () {
            button.disabled = false;
        });
    }

    // ---------------------------------------------------------------
    // 3. manage: remove / restore
    // ---------------------------------------------------------------

    function removeReport(report) {
        if (!window.confirm(m('confirmRemove'))) { return; }
        var reason = window.prompt(m('removeReason')) || '';
        post(cfg.base + '/imageReportRemove.form', {
            reportUuid: report.uuid,
            reason: reason
        }).then(function () {
            notify('mr-imaging-notice', 'ok', m('deleted'));
            reload();
        }).catch(function (error) {
            notify('mr-imaging-notice', 'error', error.message);
        });
    }

    function restoreReport(report) {
        var reason = window.prompt(m('reason')) || '';
        post(cfg.base + '/imageReportRestore.form', {
            reportUuid: report.uuid,
            reason: reason
        }).then(function () {
            notify('mr-imaging-notice', 'ok', m('restored'));
            reload();
        }).catch(function (error) {
            notify('mr-imaging-notice', 'error', error.message);
        });
    }

    // ---------------------------------------------------------------
    // history (administrators only)
    // ---------------------------------------------------------------

    function openHistory(report) {
        get(cfg.base + '/imageReportHistory.form?reportUuid=' + encodeURIComponent(report.uuid))
            .then(function (body) {
                renderHistory(body.versions || []);
                $('mr-history-overlay').hidden = false;
            })
            .catch(function (error) {
                notify('mr-imaging-notice', 'error', error.message);
            });
    }

    function renderHistory(versions) {
        var host = $('mr-history-body');
        host.innerHTML = '';

        if (!versions.length) {
            host.appendChild(el('p', 'mr-empty', '-'));
            return;
        }

        var table = el('table', 'mr-table');
        var head = el('thead');
        var headRow = el('tr');
        [m('version'), m('action'), m('by'), 'Date', m('reason'), m('images'), ''].forEach(function (label) {
            headRow.appendChild(el('th', null, label));
        });
        head.appendChild(headRow);
        table.appendChild(head);

        var body = el('tbody');
        versions.forEach(function (version) {
            var row = el('tr');
            row.appendChild(el('td', null, 'v' + version.versionNumber
                + (version.current ? ' (' + m('current') + ')' : '')));
            row.appendChild(el('td', null, version.changeType));
            row.appendChild(el('td', null, version.createdBy || '-'));
            row.appendChild(el('td', null, version.dateCreated || '-'));
            row.appendChild(el('td', null, version.changeReason || '-'));
            row.appendChild(el('td', null, (version.images || []).map(function (image) {
                return image.label || image.studyUid;
            }).join(', ') || '-'));

            var actions = el('td');
            if (version.hasDocument) {
                var link = el('a', 'mr-btn mr-btn-small', m('download'));
                link.href = cfg.base + '/imageReportDocument.form?versionUuid='
                          + encodeURIComponent(version.uuid);
                actions.appendChild(link);
            }
            row.appendChild(actions);
            body.appendChild(row);
        });
        table.appendChild(body);

        var scroll = el('div', 'mr-scroll');
        scroll.appendChild(table);
        host.appendChild(scroll);
    }

    // ---------------------------------------------------------------
    // bootstrap
    // ---------------------------------------------------------------

    function populateFilter() {
        var select = $('mr-image-filter');
        if (!select) { return; }
        (cfg.availableImages || []).forEach(function (image) {
            var option = document.createElement('option');
            option.value = imageKey(image);
            option.textContent = imageLabel(image);
            select.appendChild(option);
        });
        // On a page already scoped to one study, start on that study.
        if (cfg.scopeStudyUid) {
            select.value = cfg.scopeStudyUid + '|' + (cfg.scopeSeriesUid || '');
        }
    }

    function bind(id, event, handler) {
        var node = $(id);
        if (node) { node.addEventListener(event, handler); }
    }

    function init(options) {
        cfg = options || {};

        populateFilter();

        bind('mr-new-report', 'click', function () { openEditor(null, null); });
        bind('mr-image-filter', 'change', reload);
        bind('mr-mine-only', 'change', render);
        bind('mr-refresh', 'click', reload);
        bind('mr-editor-close', 'click', closeEditor);
        bind('mr-editor-cancel', 'click', closeEditor);
        bind('mr-editor-save', 'click', save);
        bind('mr-add-image', 'click', addImageByUid);
        bind('mr-history-close', 'click', function () { $('mr-history-overlay').hidden = true; });
        bind('mr-history-dismiss', 'click', function () { $('mr-history-overlay').hidden = true; });

        document.addEventListener('keydown', function (event) {
            if (event.key !== 'Escape') { return; }
            if ($('mr-editor-overlay') && !$('mr-editor-overlay').hidden) { closeEditor(); }
            if ($('mr-history-overlay') && !$('mr-history-overlay').hidden) {
                $('mr-history-overlay').hidden = true;
            }
        });

        reload();
    }

    /**
     * Open the editor pre-filled with one series. Exposed so the imaging module's series
     * table can offer a per-row "write a report" action without knowing how reports work.
     */
    function openForSeries(series) {
        if (!cfg.canManage) { return; }
        editing = null;
        clear('mr-editor-notice');
        $('mr-editor-title').textContent = m('newReport');
        $('mr-report-title').value = '';
        $('mr-report-text').value = '';
        $('mr-reason-field').hidden = true;
        picked = [{
            studyUid: series.studyUid || cfg.scopeStudyUid,
            seriesUid: series.seriesUid || null,
            studyInstanceUid: series.studyInstanceUid || null,
            modality: series.modality || null,
            studyDate: series.studyDate || null,
            studyDescription: series.studyDescription || null
        }];
        renderPicker();
        $('mr-editor-overlay').hidden = false;
        $('mr-report-text').focus();
    }

    return { init: init, reload: reload, openForSeries: openForSeries };
}());
