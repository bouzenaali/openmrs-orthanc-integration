<%
    ui.decorateWith("appui", "standardEmrPage", [title: ui.message("medreport.settings.title")])
    ui.includeCss("medreport", "medreport.css")
%>

<% if (accessDenied) { %>

    <div class="mr-access-denied">
        <h2>${ ui.message("medreport.error") }</h2>
        <p>${ ui.message("medreport.accessDenied") }</p>
    </div>

<% } else { %>

<div class="mr-wrap">

    <div class="mr-header">
        <div>
            <h1>${ ui.message("medreport.settings.title") }</h1>
            <p class="mr-sub">${ ui.message("medreport.settings.service") } &middot; ${ renderBaseUrl }</p>
        </div>
        <a class="mr-btn" href="${ ui.pageLink('medreport', 'settings', [refresh: 'true']) }">
            ${ ui.message("medreport.settings.refresh") }
        </a>
    </div>

    <%-- ============ rendering service health ============ --%>
    <div class="mr-panel">
        <h2>${ ui.message("medreport.settings.service") }</h2>
        <div class="mr-panel-body">

            <% if (!tokenConfigured) { %>
                <%-- Fail loudly and specifically: an unset token is the single most likely
                     reason a fresh install cannot produce a document. --%>
                <div class="mr-note mr-note-error">${ ui.message("medreport.settings.noToken") }</div>
            <% } %>

            <% if (serviceHealth.reachable) { %>
                <div class="mr-note mr-note-ok">
                    ${ ui.message("medreport.settings.reachable") } &mdash; v${ serviceHealth.version ?: '?' }
                </div>
                <table class="mr-table">
                    <tr>
                        <th>${ ui.message("medreport.settings.pdfAvailable") }</th>
                        <td>
                            <% if (serviceHealth.pdfAvailable) { %>
                                <span class="mr-tag mr-tag-own">OK</span>
                            <% } else { %>
                                <span class="mr-tag mr-tag-removed">LibreOffice</span>
                                <span class="mr-meta">${ ui.message("medreport.wizard.pdfUnavailable") }</span>
                            <% } %>
                        </td>
                    </tr>
                    <tr>
                        <th>${ ui.message("medreport.settings.formats") }</th>
                        <td>${ serviceHealth.formats ? serviceHealth.formats.join(', ') : '-' }</td>
                    </tr>
                </table>
            <% } else { %>
                <div class="mr-note mr-note-error">
                    ${ ui.message("medreport.settings.unreachable") }
                    <% if (serviceHealth.error) { %><br/><span class="mr-meta">${ serviceHealth.error }</span><% } %>
                </div>
            <% } %>

        </div>
    </div>

    <%-- ============ templates ============ --%>
    <div class="mr-panel">
        <h2>${ ui.message("medreport.settings.templates") }</h2>
        <div class="mr-panel-body">

            <% if (!templates) { %>
                <p class="mr-empty">-</p>
            <% } else { %>
                <div class="mr-scroll">
                    <table class="mr-table">
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>${ ui.message("medreport.wizard.template") }</th>
                                <th>${ ui.message("medreport.wizard.language") }</th>
                                <th></th>
                            </tr>
                        </thead>
                        <tbody>
                        <% templates.each { template -> %>
                            <tr>
                                <td><code>${ template.id }</code></td>
                                <td>
                                    ${ template.label?.fr ?: template.id }
                                    <% if (template.description?.fr) { %>
                                        <span class="mr-node-desc">${ template.description.fr }</span>
                                    <% } %>
                                </td>
                                <td>${ template.languages ? template.languages.join(', ') : '-' }</td>
                                <td>
                                    <% if (template.builtin) { %>
                                        <span class="mr-tag">${ ui.message("medreport.settings.builtin") }</span>
                                    <% } else { %>
                                        <span class="mr-tag mr-tag-own">${ ui.message("medreport.settings.custom") }</span>
                                    <% } %>
                                </td>
                            </tr>
                        <% } %>
                        </tbody>
                    </table>
                </div>

                <p class="mr-meta" style="margin-top:.8rem">
                    Adding a template needs no code: drop a JSON style profile into the
                    rendering service's <code>templates/profiles/</code> directory (or POST it
                    to <code>/templates</code>), or upload a <code>.docx</code> letterhead to
                    <code>/templates/upload</code> for full typographic control. Both appear
                    here and in the personalisation window immediately, with no restart.
                </p>
            <% } %>

        </div>
    </div>

    <%-- ============ contributing modules ============ --%>
    <div class="mr-panel">
        <h2>${ ui.message("medreport.settings.dataSources") }</h2>
        <div class="mr-panel-body">

            <p class="mr-meta">
                Modules that announced clinical data to medreport, either through a
                <code>medreport-datasource.json</code> manifest on their classpath or by
                registering a <code>ClinicalDataContributor</code> bean. medreport has no
                compile-time dependency on any of them.
            </p>

            <% if (!dataSources) { %>
                <p class="mr-empty">-</p>
            <% } else { %>
                <table class="mr-table">
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th>${ ui.message("medreport.settings.contributedBy") }</th>
                            <th>${ ui.message("medreport.settings.sets") }</th>
                            <th>Privilege</th>
                        </tr>
                    </thead>
                    <tbody>
                    <% dataSources.each { source -> %>
                        <tr>
                            <td><code>${ source.id }</code></td>
                            <td>${ source.module ?: '-' }</td>
                            <td>${ source.sectionCount }</td>
                            <td><span class="mr-meta">${ source.requiredPrivilege ?: '-' }</span></td>
                        </tr>
                    <% } %>
                    </tbody>
                </table>
            <% } %>

        </div>
    </div>

    <%-- ============ audit trail ============ --%>
    <div class="mr-panel">
        <h2>${ ui.message("medreport.settings.auditLog") }</h2>
        <div class="mr-panel-body mr-scroll">

            <% if (!auditEntries) { %>
                <p class="mr-empty">-</p>
            <% } else { %>
                <table class="mr-table">
                    <thead>
                        <tr>
                            <th>Date</th>
                            <th>${ ui.message("medreport.history.action") }</th>
                            <th>${ ui.message("medreport.history.by") }</th>
                            <th>IP</th>
                            <th>Report</th>
                            <th>Détails</th>
                        </tr>
                    </thead>
                    <tbody>
                    <% auditEntries.each { entry -> %>
                        <tr>
                            <td>${ entry.dateCreated }</td>
                            <td>
                                <% if (entry.success) { %>
                                    <span class="mr-tag">${ entry.action }</span>
                                <% } else { %>
                                    <span class="mr-tag mr-tag-removed">${ entry.action }</span>
                                <% } %>
                            </td>
                            <td>${ entry.username ?: '-' }</td>
                            <td><span class="mr-meta">${ entry.ipAddress ?: '-' }</span></td>
                            <td><span class="mr-meta">${ entry.reportUuid ?: '-' }</span></td>
                            <td>${ entry.details ?: '' }</td>
                        </tr>
                    <% } %>
                    </tbody>
                </table>
            <% } %>

        </div>
    </div>

</div>

<% } %>
