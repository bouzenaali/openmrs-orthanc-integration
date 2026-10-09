/*
 * Report personalisation window (use case 2).
 *
 * Plain ES5-compatible browser JS with no framework: the Reference Application already loads
 * jQuery and a particular Bootstrap version globally, and pulling in another library is how
 * OpenMRS module UIs end up fighting the host page. Everything here is self-contained and
 * namespaced under `medreport`.
 *
 * Security notes that matter in this file:
 *  - every label from the server is written with textContent, never innerHTML. Labels come
 *    from contributing modules' manifests, which are trusted-but-not-ours; treating them as
 *    markup would turn a manifest into a stored-XSS vector.
 *  - the selection this file builds is a *request*, not a permission. The server re-filters
 *    it against live privileges, so nothing here is a security control.
 */
var medreport = (function () {
    'use strict';

    var ctx = {
        base: '',
        patientId: null,
        catalog: [],
        templates: [],
        prefs: null,
        pdfAvailable: true,
        lastDocument: null,
        messages: {}
    };

    // ---------------------------------------------------------------
    // small helpers
    // ---------------------------------------------------------------

    function $(id) { return document.getElementById(id); }

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text !== undefined && text !== null) { node.textContent = text; }
        return node;
    }

    function msg(key, fallback) {
        return ctx.messages[key] || fallback || key;
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

    function get(url) {
        return fetch(url, { credentials: 'same-origin' }).then(readJson);
    }

    /**
     * Surface the server's own message rather than a generic failure. A 403 here means a
     * privilege the user genuinely lacks, and a 503 means the rendering service is down -
     * telling them apart is the difference between "ask your administrator for access" and
     * "the server is having a problem".
     */
    function readJson(response) {
        return response.json().catch(function () {
            return { success: false, message: 'HTTP ' + response.status };
        }).then(function (body) {
            if (!response.ok || body.success === false) {
                var error = new Error(body.message || ('HTTP ' + response.status));
                error.code = body.code;
                error.status = response.status;
                throw error;
            }
            return body;
        });
    }

    function notify(kind, text) {
        var box = $('mr-notice');
        if (!box) { return; }
        box.className = 'mr-note mr-note-' + kind;
        box.textContent = text;
        box.hidden = false;
    }

    function clearNotice() {
        var box = $('mr-notice');
        if (box) { box.hidden = true; }
    }

    function busy(button, isBusy, label) {
        if (!button) { return; }
        button.disabled = isBusy;
        if (isBusy) {
            button.dataset.mrLabel = button.textContent;
            button.textContent = '';
            button.appendChild(el('span', 'mr-spinner'));
            button.appendChild(document.createTextNode(' ' + (label || msg('generating', 'Generating...'))));
        } else if (button.dataset.mrLabel) {
            button.textContent = button.dataset.mrLabel;
        }
    }

    // ---------------------------------------------------------------
    // selection model
    // ---------------------------------------------------------------

    /** Every section node in the catalogue, flattened, so lookups stay O(1). */
    var nodes = {};

    function indexCatalog() {
        nodes = {};
        ctx.catalog.forEach(function (group) {
            (group.sections || []).forEach(function (section) { indexSection(section, null); });
        });
    }

    function indexSection(section, parentId) {
        nodes[section.id] = { section: section, parentId: parentId };
        (section.subsections || []).forEach(function (sub) { indexSection(sub, section.id); });
    }

    function sectionCheckbox(sectionId) {
        return document.querySelector('input[data-mr-section="' + cssEscape(sectionId) + '"]');
    }

    function fieldCheckboxes(sectionId) {
        return Array.prototype.slice.call(
            document.querySelectorAll('input[data-mr-parent="' + cssEscape(sectionId) + '"]'));
    }

    /** Attribute selectors need escaping: ids contain dots (e.g. "core.demographics"). */
    function cssEscape(value) {
        return String(value).replace(/["\\]/g, '\\$&');
    }

    /**
     * Reflect a section's checkbox from its fields: all ticked = checked, some = indeterminate,
     * none = unchecked. This is what makes "tick the set to get all of it, or unfold it and
     * pick" feel like one control rather than two.
     */
    function refreshSectionState(sectionId) {
        var box = sectionCheckbox(sectionId);
        if (!box) { return; }
        var fields = fieldCheckboxes(sectionId);
        if (fields.length === 0) { return; }
        var checked = fields.filter(function (f) { return f.checked; }).length;
        box.indeterminate = checked > 0 && checked < fields.length;
        box.checked = checked > 0;
        updateCount(sectionId, checked, fields.length);
    }

    function updateCount(sectionId, checked, total) {
        var badge = document.querySelector('[data-mr-count="' + cssEscape(sectionId) + '"]');
        if (badge) {
            badge.textContent = checked + ' / ' + total;
            badge.hidden = total === 0;
        }
    }

    function setSection(sectionId, checked) {
        var box = sectionCheckbox(sectionId);
        if (box) {
            box.checked = checked;
            box.indeterminate = false;
        }
        var fields = fieldCheckboxes(sectionId);
        fields.forEach(function (field) { field.checked = checked; });
        updateCount(sectionId, checked ? fields.length : 0, fields.length);

        var node = nodes[sectionId];
        if (node) {
            (node.section.subsections || []).forEach(function (sub) {
                setSection(sub.id, checked);
            });
        }
        refreshSummary();
    }

    function collectSelection() {
        var selection = { sections: [], fields: {} };
        Object.keys(nodes).forEach(function (sectionId) {
            var box = sectionCheckbox(sectionId);
            if (!box || (!box.checked && !box.indeterminate)) { return; }
            selection.sections.push(sectionId);

            var fields = fieldCheckboxes(sectionId);
            if (fields.length === 0) { return; }
            var chosen = fields.filter(function (f) { return f.checked; })
                               .map(function (f) { return f.dataset.mrField; });
            // Omitting the entry means "the whole set" on the server, which keeps the saved
            // preference stable when a contributor later adds a field to that set.
            if (chosen.length !== fields.length) {
                selection.fields[sectionId] = chosen;
            }
        });
        return selection;
    }

    function refreshSummary() {
        var list = $('mr-summary');
        if (!list) { return; }
        list.innerHTML = '';
        var total = 0;
        Object.keys(nodes).forEach(function (sectionId) {
            var box = sectionCheckbox(sectionId);
            if (!box || (!box.checked && !box.indeterminate)) { return; }
            var fields = fieldCheckboxes(sectionId);
            var checked = fields.filter(function (f) { return f.checked; }).length;
            total += checked || 1;

            var item = el('li');
            item.appendChild(el('span', null, nodes[sectionId].section.label));
            if (fields.length) {
                item.appendChild(el('span', 'mr-count', checked + '/' + fields.length));
            }
            list.appendChild(item);
        });
        if (!list.children.length) {
            list.appendChild(el('li', 'mr-empty', msg('nothingSelected', 'Nothing selected.')));
        }
        var counter = $('mr-selected-count');
        if (counter) {
            counter.textContent = total + ' ' + msg('selected', 'selected');
        }
        var generate = $('mr-generate');
        if (generate) { generate.disabled = total === 0; }
    }

    // ---------------------------------------------------------------
    // rendering the tree
    // ---------------------------------------------------------------

    function renderCatalog() {
        var host = $('mr-tree');
        host.innerHTML = '';

        if (!ctx.catalog.length) {
            host.appendChild(el('p', 'mr-empty', msg('noData', 'No data set is available to you.')));
            return;
        }

        ctx.catalog.forEach(function (group) {
            var wrapper = el('div', 'mr-group');
            wrapper.appendChild(el('p', 'mr-group-label', group.label));
            (group.sections || []).forEach(function (section) {
                wrapper.appendChild(renderSection(section, 0));
            });
            host.appendChild(wrapper);
        });
    }

    function renderSection(section, depth) {
        var node = el('div', 'mr-node');
        node.dataset.mrNode = section.id;

        var head = el('div', 'mr-node-head');
        var hasChildren = (section.fields || []).length > 0
                       || (section.subsections || []).length > 0;

        var twisty = el('button', 'mr-twisty' + (hasChildren ? '' : ' mr-empty'), '\u25B8');
        twisty.type = 'button';
        twisty.setAttribute('aria-expanded', 'false');
        twisty.setAttribute('aria-label', section.label);
        head.appendChild(twisty);

        var box = document.createElement('input');
        box.type = 'checkbox';
        box.dataset.mrSection = section.id;
        box.id = 'mr-sec-' + section.id;
        head.appendChild(box);

        var label = el('label', 'mr-node-title');
        label.setAttribute('for', box.id);
        label.appendChild(document.createTextNode(section.label));
        if (section.description) {
            label.appendChild(el('span', 'mr-node-desc', section.description));
        }
        head.appendChild(label);

        var count = el('span', 'mr-count', '');
        count.dataset.mrCount = section.id;
        count.hidden = true;
        head.appendChild(count);
        node.appendChild(head);

        var body = el('div', 'mr-node-body');
        body.hidden = true;

        if ((section.fields || []).length) {
            var grid = el('div', 'mr-fields');
            section.fields.forEach(function (field) {
                var wrap = el('label', 'mr-check');
                var input = document.createElement('input');
                input.type = 'checkbox';
                input.dataset.mrParent = section.id;
                input.dataset.mrField = field.id;
                input.addEventListener('change', function () {
                    refreshSectionState(section.id);
                    refreshSummary();
                });
                wrap.appendChild(input);
                var text = field.label + (field.unit ? ' (' + field.unit + ')' : '');
                wrap.appendChild(el('span', null, text));
                grid.appendChild(wrap);
            });
            body.appendChild(grid);
        }

        (section.subsections || []).forEach(function (sub) {
            body.appendChild(renderSection(sub, depth + 1));
        });
        node.appendChild(body);

        twisty.addEventListener('click', function () {
            var open = body.hidden;
            body.hidden = !open;
            twisty.textContent = open ? '\u25BE' : '\u25B8';
            twisty.setAttribute('aria-expanded', String(open));
        });

        box.addEventListener('change', function () {
            setSection(section.id, box.checked);
        });

        return node;
    }

    // ---------------------------------------------------------------
    // preferences <-> form
    // ---------------------------------------------------------------

    function applyPreferences(prefs) {
        setSegment('mr-language', prefs.language || 'fr');
        setSegment('mr-format', prefs.format || 'pdf');

        var template = $('mr-template');
        if (template && prefs.template) { template.value = prefs.template; }

        $('mr-opt-empty').checked = !!prefs.showEmptyFields;
        $('mr-opt-toc').checked = !!prefs.includeTableOfContents;
        $('mr-opt-signature').checked = prefs.includeSignatureBlock !== false;
        $('mr-opt-pages').checked = prefs.includePageNumbers !== false;
        $('mr-opt-confidential').checked = prefs.confidentialityNotice !== false;
        $('mr-opt-images').checked = prefs.includeImageObservations !== false;
        if (prefs.title) { $('mr-title').value = prefs.title; }

        // Apply the stored selection. A set that no longer exists, or that the user has
        // since lost access to, simply has no checkbox and is skipped - a stale preference
        // must never widen what gets included.
        var selected = prefs.sections || [];
        Object.keys(nodes).forEach(function (sectionId) {
            var box = sectionCheckbox(sectionId);
            if (!box) { return; }
            var isSelected = selected.indexOf(sectionId) !== -1;
            var explicit = prefs.fields && prefs.fields[sectionId];
            if (isSelected && explicit) {
                fieldCheckboxes(sectionId).forEach(function (field) {
                    field.checked = explicit.indexOf(field.dataset.mrField) !== -1;
                });
                refreshSectionState(sectionId);
            } else if (isSelected) {
                setSection(sectionId, true);
            } else {
                setSection(sectionId, false);
            }
        });
        refreshSummary();
    }

    function buildRequest() {
        var selection = collectSelection();
        return {
            language: readSegment('mr-language'),
            format: readSegment('mr-format'),
            template: $('mr-template') ? $('mr-template').value : null,
            sections: selection.sections,
            fields: selection.fields,
            includeImageObservations: $('mr-opt-images').checked,
            showEmptyFields: $('mr-opt-empty').checked,
            includeTableOfContents: $('mr-opt-toc').checked,
            includeSignatureBlock: $('mr-opt-signature').checked,
            includePageNumbers: $('mr-opt-pages').checked,
            confidentialityNotice: $('mr-opt-confidential').checked,
            title: $('mr-title').value || null
        };
    }

    // ---------------------------------------------------------------
    // segmented controls
    // ---------------------------------------------------------------

    function initSegment(id, onChange) {
        var group = $(id);
        if (!group) { return; }
        group.addEventListener('click', function (event) {
            var button = event.target.closest('button');
            if (!button || button.disabled) { return; }
            setSegment(id, button.dataset.mrValue);
            if (onChange) { onChange(button.dataset.mrValue); }
        });
    }

    function setSegment(id, value) {
        var group = $(id);
        if (!group) { return; }
        Array.prototype.forEach.call(group.querySelectorAll('button'), function (button) {
            var active = button.dataset.mrValue === value;
            button.setAttribute('aria-pressed', String(active));
        });
    }

    function readSegment(id) {
        var group = $(id);
        if (!group) { return null; }
        var active = group.querySelector('button[aria-pressed="true"]');
        return active ? active.dataset.mrValue : null;
    }

    // ---------------------------------------------------------------
    // generation
    // ---------------------------------------------------------------

    function generate() {
        clearNotice();
        var button = $('mr-generate');
        var request = buildRequest();
        if (!request.sections.length) {
            notify('warn', msg('nothingSelected', 'Select at least one set of information.'));
            return;
        }

        busy(button, true);
        post(ctx.base + '/generateReport.form', {
            patientId: ctx.patientId,
            payload: JSON.stringify(request),
            remember: $('mr-remember').checked ? 'true' : 'false'
        }).then(function (body) {
            ctx.lastDocument = body.document;
            showPreview(body.document);
            if (body.document.warnings && body.document.warnings.length) {
                notify('warn', body.document.warnings.join(' '));
            }
        }).catch(function (error) {
            notify('error', error.message);
        }).then(function () {
            busy(button, false);
        });
    }

    function showPreview(doc) {
        var overlay = $('mr-preview-overlay');
        var frame = $('mr-preview-frame');
        frame.src = ctx.base + '/reportPreview.form?artifactId='
                  + encodeURIComponent(doc.artifactId)
                  + '&format=' + encodeURIComponent(doc.previewFormat || 'pdf');
        overlay.hidden = false;

        var download = $('mr-download');
        download.href = ctx.base + '/reportDownload.form?artifactId='
                      + encodeURIComponent(doc.artifactId)
                      + '&format=' + encodeURIComponent(doc.format)
                      + '&filename=' + encodeURIComponent(doc.filename || 'rapport');
        download.setAttribute('download', doc.filename || 'rapport');

        var info = $('mr-preview-info');
        if (info) {
            info.textContent = (doc.format || '').toUpperCase()
                             + ' · ' + Math.max(1, Math.round((doc.sizeBytes || 0) / 1024)) + ' Ko';
        }
    }

    function closePreview() {
        $('mr-preview-overlay').hidden = true;
        // Drop the src so the browser stops holding a rendered PHI document in the iframe.
        $('mr-preview-frame').src = 'about:blank';
    }

    function savePreferencesOnly() {
        post(ctx.base + '/reportPreferences.form', {
            payload: JSON.stringify(buildRequest())
        }).then(function () {
            notify('ok', msg('saved', 'Choices saved.'));
        }).catch(function (error) {
            notify('error', error.message);
        });
    }

    // ---------------------------------------------------------------
    // filtering
    // ---------------------------------------------------------------

    function applyFilter(term) {
        var needle = (term || '').trim().toLowerCase();
        Array.prototype.forEach.call(document.querySelectorAll('[data-mr-node]'), function (node) {
            if (!needle) {
                node.hidden = false;
                return;
            }
            node.hidden = node.textContent.toLowerCase().indexOf(needle) === -1;
        });
    }

    // ---------------------------------------------------------------
    // bootstrap
    // ---------------------------------------------------------------

    function initReportPage(options) {
        ctx.base = options.base;
        ctx.patientId = options.patientId;
        ctx.messages = options.messages || {};

        initSegment('mr-language', function () { reloadCatalog(); });
        initSegment('mr-format');

        $('mr-generate').addEventListener('click', generate);
        $('mr-save-prefs').addEventListener('click', savePreferencesOnly);
        $('mr-preview-close').addEventListener('click', closePreview);
        $('mr-preview-overlay').addEventListener('click', function (event) {
            if (event.target === this) { closePreview(); }
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && !$('mr-preview-overlay').hidden) { closePreview(); }
        });

        $('mr-select-all').addEventListener('click', function () {
            Object.keys(nodes).forEach(function (id) {
                if (!nodes[id].parentId) { setSection(id, true); }
            });
        });
        $('mr-select-none').addEventListener('click', function () {
            Object.keys(nodes).forEach(function (id) {
                if (!nodes[id].parentId) { setSection(id, false); }
            });
        });
        $('mr-expand-all').addEventListener('click', function () { toggleAll(true); });
        $('mr-collapse-all').addEventListener('click', function () { toggleAll(false); });
        $('mr-filter').addEventListener('input', function () { applyFilter(this.value); });

        loadTemplates();
        reloadCatalog();
    }

    function toggleAll(open) {
        Array.prototype.forEach.call(document.querySelectorAll('.mr-node-body'), function (body) {
            body.hidden = !open;
        });
        Array.prototype.forEach.call(document.querySelectorAll('.mr-twisty'), function (twisty) {
            twisty.textContent = open ? '\u25BE' : '\u25B8';
            twisty.setAttribute('aria-expanded', String(open));
        });
    }

    function reloadCatalog() {
        var language = readSegment('mr-language') || 'fr';
        get(ctx.base + '/catalog.form?language=' + encodeURIComponent(language))
            .then(function (body) {
                ctx.catalog = body.catalog || [];
                indexCatalog();
                renderCatalog();
                return get(ctx.base + '/reportPreferences.form');
            })
            .then(function (body) {
                ctx.prefs = body.preferences || {};
                applyPreferences(ctx.prefs);
            })
            .catch(function (error) {
                notify('error', error.message);
            });
    }

    function loadTemplates() {
        get(ctx.base + '/reportTemplates.form').then(function (body) {
            ctx.templates = body.templates || [];
            var select = $('mr-template');
            select.innerHTML = '';
            var language = readSegment('mr-language') || 'fr';
            ctx.templates.forEach(function (template) {
                var option = document.createElement('option');
                option.value = template.id;
                option.textContent = (template.label && (template.label[language] || template.label.fr))
                                   || template.id;
                select.appendChild(option);
            });

            // If this host has no LibreOffice, PDF and ODT genuinely cannot be produced -
            // disable them and say why rather than letting the user pick a format that will
            // silently come back as DOCX.
            var health = body.service || {};
            ctx.pdfAvailable = health.pdfAvailable !== false;
            if (!ctx.pdfAvailable) {
                ['pdf', 'odt'].forEach(function (value) {
                    var button = document.querySelector('#mr-format button[data-mr-value="' + value + '"]');
                    if (button) {
                        button.disabled = true;
                        button.title = msg('pdfUnavailable', 'Unavailable on this server.');
                    }
                });
                if (readSegment('mr-format') === 'pdf') { setSegment('mr-format', 'docx'); }
                notify('warn', msg('pdfUnavailable', 'PDF is unavailable on this server.'));
            }
            if (health.reachable === false) {
                notify('error', msg('serviceUnavailable', 'The report service is unavailable.'));
                $('mr-generate').disabled = true;
            }
        }).catch(function (error) {
            notify('error', error.message);
        });
    }

    return {
        initReportPage: initReportPage,
        // exposed for the imaging fragment, which shares the helpers above
        _internal: { get: get, post: post, el: el, notify: notify, busy: busy }
    };
}());
