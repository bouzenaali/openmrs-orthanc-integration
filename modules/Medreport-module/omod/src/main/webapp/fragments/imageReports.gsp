<%
    // The one thing the imaging module includes. Everything about reports lives here and in
    // medreport's REST resources; the imaging module supplies only the identifiers it
    // already has on screen.
    //
    // Config:
    //   patientId       (required) OpenMRS patient id
    //   availableImages (optional) list of maps the host page knows about, each with
    //                   studyUid + optional seriesUid/label/modality/studyDate/
    //                   studyDescription. Drives the "which images does this report cover"
    //                   picker and the per-image filter. Without it the fragment still works,
    //                   scoped to studyUid/seriesUid below.
    //   studyUid        (optional) preselect/scope to one study
    //   seriesUid       (optional) preselect/scope to one series
    ui.includeCss("medreport", "medreport.css")
    ui.includeJavascript("medreport", "medreport-imaging.js")

    def images = config.availableImages ?: []
    def scopeStudyUid = config.studyUid ?: ''
    def scopeSeriesUid = config.seriesUid ?: ''
    def patientId = config.patientId ?: ''
%>

<div class="mr-panel" id="mr-imaging-panel">
    <h2>${ ui.message("medreport.imaging.title") }</h2>
    <div class="mr-panel-body">

        <% if (!canView) { %>

            <%-- No view privilege: say nothing about whether reports exist for this patient. --%>
            <p class="mr-empty">${ ui.message("medreport.accessDenied") }</p>

        <% } else { %>

            <div id="mr-imaging-notice" class="mr-note" hidden></div>

            <div class="mr-tree-tools">
                <%--
                  RP6, UI half: the create button renders enabled only for a user holding
                  medreport.imaging.manage. The POST endpoint re-checks the same privilege, so
                  this is a convenience and never the guard.
                --%>
                <% if (canManage) { %>
                    <button type="button" id="mr-new-report" class="mr-btn mr-btn-primary">
                        ${ ui.message("medreport.imaging.new") }
                    </button>
                <% } else { %>
                    <button type="button" class="mr-btn" disabled
                            title="${ ui.message('medreport.accessDenied') }">
                        ${ ui.message("medreport.imaging.new") }
                    </button>
                <% } %>

                <%-- Requirement 2: pick an image, get every report about it. --%>
                <label class="mr-inline-label" for="mr-image-filter">
                    ${ ui.message("medreport.imaging.filterByImage") }
                </label>
                <select id="mr-image-filter" class="mr-select mr-select-inline">
                    <option value="">${ ui.message("medreport.imaging.allImages") }</option>
                </select>

                <%-- Requirement 3: see my own reports. --%>
                <label class="mr-check mr-check-inline">
                    <input type="checkbox" id="mr-mine-only"/>
                    <span>${ ui.message("medreport.imaging.mineOnly") }</span>
                </label>

                <button type="button" id="mr-refresh" class="mr-btn mr-btn-small">
                    ${ ui.message("medreport.imaging.refresh") }
                </button>
            </div>

            <p class="mr-meta" id="mr-list-summary"></p>
            <div id="mr-report-list">
                <p class="mr-empty">&hellip;</p>
            </div>

        <% } %>

    </div>
</div>

<%-- ============ editor ============ --%>
<div class="mr-overlay" id="mr-editor-overlay" role="dialog" aria-modal="true"
     aria-labelledby="mr-editor-title" hidden>
    <div class="mr-dialog">
        <div class="mr-dialog-head">
            <h2 id="mr-editor-title">${ ui.message("medreport.imaging.new") }</h2>
            <button type="button" class="mr-dialog-close" id="mr-editor-close"
                    aria-label="${ ui.message('medreport.close') }">&times;</button>
        </div>
        <div class="mr-dialog-body">
            <div id="mr-editor-notice" class="mr-note" hidden></div>

            <div class="mr-field">
                <label for="mr-report-title">${ ui.message("medreport.imaging.reportTitle") }</label>
                <input type="text" id="mr-report-title" class="mr-input" maxlength="200"/>
            </div>

            <div class="mr-field">
                <label>
                    ${ ui.message("medreport.imaging.images") }
                    <span class="mr-count" id="mr-image-count"></span>
                </label>
                <p class="mr-meta">${ ui.message("medreport.imaging.imagesHint") }</p>
                <%--
                  Requirement 1: select one OR MANY images. This is a checkbox list of the
                  studies the host page already listed, which is also why the report/image
                  relationship has to be many-to-many (RP9).
                --%>
                <div id="mr-image-picker" class="mr-picker"></div>
                <button type="button" id="mr-add-image" class="mr-btn mr-btn-small">
                    ${ ui.message("medreport.imaging.addImage") }
                </button>
            </div>

            <div class="mr-field">
                <label for="mr-report-text">${ ui.message("medreport.imaging.observation") }</label>
                <%-- Stored verbatim and rendered as-is into the .docx (SF3.2). --%>
                <textarea id="mr-report-text" class="mr-textarea"></textarea>
            </div>

            <div class="mr-field" id="mr-reason-field" hidden>
                <label for="mr-change-reason">${ ui.message("medreport.imaging.changeReason") }</label>
                <input type="text" id="mr-change-reason" class="mr-input" maxlength="480"/>
            </div>
        </div>
        <div class="mr-dialog-foot">
            <button type="button" class="mr-btn" id="mr-editor-cancel">
                ${ ui.message("medreport.cancel") }
            </button>
            <button type="button" class="mr-btn mr-btn-primary" id="mr-editor-save">
                ${ ui.message("medreport.save") }
            </button>
        </div>
    </div>
</div>

<%-- ============ version history (administrators only) ============ --%>
<div class="mr-overlay" id="mr-history-overlay" role="dialog" aria-modal="true"
     aria-labelledby="mr-history-title" hidden>
    <div class="mr-dialog">
        <div class="mr-dialog-head">
            <h2 id="mr-history-title">${ ui.message("medreport.history.title") }</h2>
            <button type="button" class="mr-dialog-close" id="mr-history-close"
                    aria-label="${ ui.message('medreport.close') }">&times;</button>
        </div>
        <div class="mr-dialog-body mr-scroll">
            <div id="mr-history-body"></div>
        </div>
        <div class="mr-dialog-foot">
            <button type="button" class="mr-btn" id="mr-history-dismiss">
                ${ ui.message("medreport.close") }
            </button>
        </div>
    </div>
</div>

<script type="text/javascript">
    //
    // Every interpolated string goes through ui.escapeJs(). The French bundle is full of
    // apostrophes ("Aucun compte rendu n'a encore été rédigé", "Seul l'auteur..."), and one
    // raw apostrophe inside a single-quoted JS string terminates it early - a parse error
    // that kills the entire <script> block, so init() never runs and the panel sits inert
    // with no visible error. Study descriptions come from DICOM and are equally untrusted.
    // ModuleWiringTest enforces this.
    //
    jQuery(function () {
        medreportImaging.init({
            base: '/' + OPENMRS_CONTEXT_PATH + '/module/medreport',
            patientId: '${ ui.escapeJs(patientId.toString()) }',
            scopeStudyUid: '${ ui.escapeJs(scopeStudyUid.toString()) }',
            scopeSeriesUid: '${ ui.escapeJs(scopeSeriesUid.toString()) }',
            canManage: ${ canManage },
            isAdmin: ${ isAdmin },
            availableImages: [
                <% images.eachWithIndex { image, index -> %>
                {
                    studyUid: '${ ui.escapeJs((image.studyUid ?: '').toString()) }',
                    seriesUid: '${ ui.escapeJs((image.seriesUid ?: '').toString()) }',
                    studyInstanceUid: '${ ui.escapeJs((image.studyInstanceUid ?: '').toString()) }',
                    modality: '${ ui.escapeJs((image.modality ?: '').toString()) }',
                    studyDate: '${ ui.escapeJs((image.studyDate ?: '').toString()) }',
                    studyDescription: '${ ui.escapeJs((image.studyDescription ?: '').toString()) }'
                }<% if (index < images.size() - 1) { %>,<% } %>
                <% } %>
            ],
            messages: {
                none: '${ ui.escapeJs(ui.message("medreport.imaging.none")) }',
                noneForImage: '${ ui.escapeJs(ui.message("medreport.imaging.noneForImage")) }',
                noneMine: '${ ui.escapeJs(ui.message("medreport.imaging.noneMine")) }',
                author: '${ ui.escapeJs(ui.message("medreport.imaging.author")) }',
                version: '${ ui.escapeJs(ui.message("medreport.imaging.version")) }',
                edit: '${ ui.escapeJs(ui.message("medreport.imaging.edit")) }',
                remove: '${ ui.escapeJs(ui.message("medreport.imaging.remove")) }',
                history: '${ ui.escapeJs(ui.message("medreport.imaging.history")) }',
                restore: '${ ui.escapeJs(ui.message("medreport.imaging.restore")) }',
                download: '${ ui.escapeJs(ui.message("medreport.download")) }',
                removed: '${ ui.escapeJs(ui.message("medreport.imaging.removed")) }',
                own: '${ ui.escapeJs(ui.message("medreport.imaging.own")) }',
                notOwner: '${ ui.escapeJs(ui.message("medreport.imaging.notOwner")) }',
                confirmRemove: '${ ui.escapeJs(ui.message("medreport.imaging.confirmRemove")) }',
                removeReason: '${ ui.escapeJs(ui.message("medreport.imaging.removeReason")) }',
                saved: '${ ui.escapeJs(ui.message("medreport.imaging.saved")) }',
                updated: '${ ui.escapeJs(ui.message("medreport.imaging.updated")) }',
                deleted: '${ ui.escapeJs(ui.message("medreport.imaging.deleted")) }',
                restored: '${ ui.escapeJs(ui.message("medreport.imaging.restored")) }',
                emptyText: '${ ui.escapeJs(ui.message("medreport.imaging.emptyText")) }',
                noImages: '${ ui.escapeJs(ui.message("medreport.imaging.noImages")) }',
                newReport: '${ ui.escapeJs(ui.message("medreport.imaging.new")) }',
                current: '${ ui.escapeJs(ui.message("medreport.history.current")) }',
                action: '${ ui.escapeJs(ui.message("medreport.history.action")) }',
                by: '${ ui.escapeJs(ui.message("medreport.history.by")) }',
                reason: '${ ui.escapeJs(ui.message("medreport.history.reason")) }',
                images: '${ ui.escapeJs(ui.message("medreport.history.images")) }',
                addImagePrompt: '${ ui.escapeJs(ui.message("medreport.imaging.addImagePrompt")) }',
                selectedCount: '${ ui.escapeJs(ui.message("medreport.imaging.selectedCount")) }',
                reportCount: '${ ui.escapeJs(ui.message("medreport.imaging.reportCount")) }',
                studyPrefix: '${ ui.escapeJs(ui.message("medreport.imaging.studyPrefix")) }'
            }
        });
    });
</script>
