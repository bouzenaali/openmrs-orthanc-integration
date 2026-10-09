<%
    ui.decorateWith("appui", "standardEmrPage", [title: ui.message("medreport.wizard.title")])
    ui.includeCss("medreport", "medreport.css")
    ui.includeJavascript("medreport", "medreport-report.js")
%>

<% if (accessDenied) { %>

    <div class="mr-access-denied">
        <h2>${ ui.message("medreport.error") }</h2>
        <p>${ ui.message("medreport.accessDenied") }</p>
    </div>

<% } else { %>

<%
    // Guard against BOTH a true null and the literal four-character string "null":
    // Groovy's GString renders a real null as the text "null", and a value that was
    // persisted as the string "null" survives a plain truthy check. Both have shipped as
    // visible bugs in this deployment before, so every interpolation uses this pattern.
    def show = { value -> (value && value.toString().trim().toLowerCase() != 'null') ? value.toString().trim() : '' }
    def familyName = show(patient.familyName)
    def givenName = show(patient.givenName)
    def identifier = patient.activeIdentifiers ? show(patient.activeIdentifiers[0].identifier) : ''
%>

<div class="mr-wrap">

    <div class="mr-header">
        <div>
            <h1>${ ui.message("medreport.wizard.title") }</h1>
            <p class="mr-sub">${ ui.message("medreport.wizard.subtitle") }</p>
        </div>
        <div class="mr-patient-chip">
            <strong>${ familyName } ${ givenName }</strong><br/>
            <% if (identifier) { %><span class="mr-meta">${ identifier }</span><br/><% } %>
            <span class="mr-meta">${ show(patient.gender) } · ${ patient.age != null ? patient.age : '' }</span>
        </div>
    </div>

    <div id="mr-notice" class="mr-note" hidden></div>

    <div class="mr-columns">

        <!-- ============ left: what goes in the report ============ -->
        <div>
            <div class="mr-panel">
                <h2>${ ui.message("medreport.wizard.sections") }</h2>
                <div class="mr-panel-body">

                    <div class="mr-tree-tools">
                        <input type="search" id="mr-filter" class="mr-input"
                               placeholder="${ ui.message('medreport.wizard.filter') }"
                               aria-label="${ ui.message('medreport.wizard.filter') }"/>
                        <button type="button" id="mr-select-all" class="mr-btn mr-btn-small">
                            ${ ui.message("medreport.wizard.selectAll") }
                        </button>
                        <button type="button" id="mr-select-none" class="mr-btn mr-btn-small">
                            ${ ui.message("medreport.wizard.selectNone") }
                        </button>
                        <button type="button" id="mr-expand-all" class="mr-btn mr-btn-small">
                            ${ ui.message("medreport.wizard.expandAll") }
                        </button>
                        <button type="button" id="mr-collapse-all" class="mr-btn mr-btn-small">
                            ${ ui.message("medreport.wizard.collapseAll") }
                        </button>
                    </div>

                    <!--
                      Only sets this user is allowed to include are ever sent here, so an
                      absent set means "not permitted", not "not ticked". The server applies
                      the same filter again when the report is built.
                    -->
                    <div id="mr-tree"></div>

                </div>
            </div>
        </div>

        <!-- ============ right: how it looks, and the actions ============ -->
        <div class="mr-rail">

            <div class="mr-panel">
                <h2>${ ui.message("medreport.wizard.step.layout") }</h2>
                <div class="mr-panel-body">

                    <div class="mr-field">
                        <label id="mr-language-label">${ ui.message("medreport.wizard.language") }</label>
                        <div class="mr-segment" id="mr-language" role="group" aria-labelledby="mr-language-label">
                            <button type="button" data-mr-value="fr" aria-pressed="true">${ ui.message("medreport.wizard.language.fr") }</button>
                            <button type="button" data-mr-value="en" aria-pressed="false">${ ui.message("medreport.wizard.language.en") }</button>
                            <button type="button" data-mr-value="ar" aria-pressed="false">${ ui.message("medreport.wizard.language.ar") }</button>
                        </div>
                    </div>

                    <div class="mr-field">
                        <label id="mr-format-label">${ ui.message("medreport.wizard.format") }</label>
                        <div class="mr-segment" id="mr-format" role="group" aria-labelledby="mr-format-label">
                            <button type="button" data-mr-value="pdf" aria-pressed="true">PDF</button>
                            <button type="button" data-mr-value="docx" aria-pressed="false">DOCX</button>
                            <button type="button" data-mr-value="odt" aria-pressed="false">ODT</button>
                            <button type="button" data-mr-value="html" aria-pressed="false">HTML</button>
                        </div>
                    </div>

                    <div class="mr-field">
                        <label for="mr-template">${ ui.message("medreport.wizard.template") }</label>
                        <select id="mr-template" class="mr-select"></select>
                    </div>

                    <div class="mr-field">
                        <label for="mr-title">${ ui.message("medreport.wizard.reportTitle") }</label>
                        <input type="text" id="mr-title" class="mr-input" maxlength="180"/>
                    </div>

                </div>
            </div>

            <div class="mr-panel">
                <h2>${ ui.message("medreport.wizard.options") }</h2>
                <div class="mr-panel-body">
                    <label class="mr-check">
                        <input type="checkbox" id="mr-opt-images" checked/>
                        <span>${ ui.message("medreport.wizard.imageObs") }</span>
                    </label>
                    <label class="mr-check">
                        <input type="checkbox" id="mr-opt-empty"/>
                        <span>${ ui.message("medreport.wizard.showEmpty") }</span>
                    </label>
                    <label class="mr-check">
                        <input type="checkbox" id="mr-opt-toc"/>
                        <span>${ ui.message("medreport.wizard.toc") }</span>
                    </label>
                    <label class="mr-check">
                        <input type="checkbox" id="mr-opt-signature" checked/>
                        <span>${ ui.message("medreport.wizard.signature") }</span>
                    </label>
                    <label class="mr-check">
                        <input type="checkbox" id="mr-opt-pages" checked/>
                        <span>${ ui.message("medreport.wizard.pageNumbers") }</span>
                    </label>
                    <label class="mr-check">
                        <input type="checkbox" id="mr-opt-confidential" checked/>
                        <span>${ ui.message("medreport.wizard.confidential") }</span>
                    </label>
                </div>
            </div>

            <div class="mr-panel">
                <h2>
                    ${ ui.message("medreport.wizard.step.content") }
                    <span id="mr-selected-count" class="mr-count"></span>
                </h2>
                <div class="mr-panel-body">
                    <ul id="mr-summary" class="mr-summary-list"></ul>

                    <label class="mr-check" style="margin-top:.7rem">
                        <input type="checkbox" id="mr-remember" checked/>
                        <span>${ ui.message("medreport.wizard.remember") }</span>
                    </label>

                    <div class="mr-toolbar" style="margin-top:.8rem">
                        <button type="button" id="mr-generate" class="mr-btn mr-btn-primary">
                            ${ ui.message("medreport.generate") }
                        </button>
                        <button type="button" id="mr-save-prefs" class="mr-btn mr-btn-small">
                            ${ ui.message("medreport.save") }
                        </button>
                    </div>
                </div>
            </div>

        </div>
    </div>
</div>

<!-- ============ preview, shown before anything is downloaded ============ -->
<div class="mr-overlay" id="mr-preview-overlay" role="dialog" aria-modal="true"
     aria-labelledby="mr-preview-title" hidden>
    <div class="mr-dialog">
        <div class="mr-dialog-head">
            <h2 id="mr-preview-title">${ ui.message("medreport.preview") }</h2>
            <button type="button" class="mr-dialog-close" id="mr-preview-close"
                    aria-label="${ ui.message('medreport.close') }">&times;</button>
        </div>
        <div class="mr-dialog-body">
            <iframe id="mr-preview-frame" class="mr-preview-frame" title="${ ui.message('medreport.preview') }"
                    sandbox="allow-same-origin"></iframe>
        </div>
        <div class="mr-dialog-foot">
            <span class="mr-meta" id="mr-preview-info"></span>
            <a id="mr-download" class="mr-btn mr-btn-primary" href="#">
                ${ ui.message("medreport.download") }
            </a>
        </div>
    </div>
</div>

<script type="text/javascript">
    //
    // Every message below goes through ui.escapeJs(). This is not optional tidiness: the
    // French bundle is full of apostrophes ("Sélectionnez au moins un ensemble
    // d'informations", "Le PDF n'est pas disponible..."), and interpolating one raw into a
    // single-quoted JS string closes the string early. That is a parse error for the whole
    // <script> block, so initReportPage() never runs and the page renders completely inert -
    // no data tree, no working buttons, no error message. ModuleWiringTest enforces this.
    //
    jQuery(function () {
        medreport.initReportPage({
            base: '/' + OPENMRS_CONTEXT_PATH + '/module/medreport',
            patientId: ${ patient.patientId },
            messages: {
                generating: '${ ui.escapeJs(ui.message("medreport.generating")) }',
                selected: '${ ui.escapeJs(ui.message("medreport.wizard.selected")) }',
                nothingSelected: '${ ui.escapeJs(ui.message("medreport.wizard.nothingSelected")) }',
                noData: '${ ui.escapeJs(ui.message("medreport.wizard.noData")) }',
                pdfUnavailable: '${ ui.escapeJs(ui.message("medreport.wizard.pdfUnavailable")) }',
                serviceUnavailable: '${ ui.escapeJs(ui.message("medreport.serviceUnavailable")) }',
                saved: '${ ui.escapeJs(ui.message("medreport.wizard.restored")) }'
            }
        });
    });
</script>

<% } %>
