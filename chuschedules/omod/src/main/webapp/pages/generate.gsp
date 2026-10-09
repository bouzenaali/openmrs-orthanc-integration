<%
    ui.decorateWith("appui", "standardEmrPage", [ title: "Générer les tranches" ])
    ui.includeCss("chuschedules", "chuschedules.css")

    def reasonLabels = [
        EXCEPTION         : "Jour férié ou congé",
        OVERLAPS_EXISTING : "Chevauche une tranche existante"
    ]

    // One sentence per bulk reason, naming the cause, instead of one row per date.
    // A year's generation can produce hundreds of identical rows that say nothing new.
    def validityText = template.validTo
        ? "le modèle est valable du " + ui.formatDatePretty(template.validFrom) + " au " + ui.formatDatePretty(template.validTo)
        : "le modèle commence le " + ui.formatDatePretty(template.validFrom)
    def bulkText = [
        PAST              : { n -> "${n} date(s) déjà passée(s) — la génération commence demain" },
        TEMPLATE_INACTIVE : { n -> "${n} date(s) ignorée(s) : l'horaire est inactif" },
        OUTSIDE_VALIDITY  : { n -> "${n} date(s) hors période de validité — ${validityText}" },
        ALREADY_GENERATED : { n -> "${n} date(s) déjà générée(s) précédemment" }
    ]

    def MAX_ROWS = 50
%>

<div class="chu-schedules">

    <h2>Générer les tranches &mdash; ${ ui.encodeHtml(template.name) }</h2>
    <p class="chu-pattern">${ ui.encodeHtml(summary) }</p>
    <p class="chu-muted">
        ${ ui.encodeHtml(template.provider?.name ?: '') } &middot; ${ ui.encodeHtml(template.location?.name ?: '') }
    </p>

    <% if (error) { %>
        <div class="chu-error">${ ui.encodeHtml(error) }</div>
    <% } %>

    <form method="post" class="chu-form">
        <input type="hidden" name="templateId" value="${ template.templateId }"/>
        <label>Du <input type="date" name="from" value="${ from }" required/></label>
        <label>au <input type="date" name="to" value="${ to }" required/></label>
        <button type="submit" name="action" value="preview" class="confirm">Aperçu</button>
    </form>

    <% if (report) { %>
        <div class="chu-report ${ report.dryRun ? 'chu-dryrun' : 'chu-written' }">
            <% if (report.dryRun) { %>
                <strong>Aperçu &mdash; rien n'a été enregistré.</strong>
            <% } else { %>
                <strong>${ report.createdCount } tranche(s) créée(s).</strong>
            <% } %>
            <div>
                ${ report.dryRun ? report.createdCount + ' tranche(s) seraient créées' : '' }
                <% if (report.skippedCount > 0) { %>
                    &middot; ${ report.skippedCount } ignorée(s)
                <% } %>
            </div>
        </div>

        <% def created = report.created %>
        <% if (!created.isEmpty()) { %>
            <h3>À créer (${ created.size() })</h3>
            <% if (created.size() > MAX_ROWS) { %>
                <p class="chu-hint">
                    Les ${ MAX_ROWS } premières sur ${ created.size() } sont affichées ci-dessous.
                    Toutes seront créées.
                </p>
            <% } %>
            <table class="chu-table chu-compact">
                <thead><tr><th>Date</th><th>Séance</th></tr></thead>
                <tbody>
                <% created.take(MAX_ROWS).each { occ -> %>
                    <tr>
                        <td>${ ui.formatDatePretty(java.sql.Date.valueOf(occ.date)) }</td>
                        <td>${ occ.range.startTime } &ndash; ${ occ.range.endTime }</td>
                    </tr>
                <% } %>
                </tbody>
            </table>
        <% } %>

        <% def bulk = report.bulkSkipCounts %>
        <% if (!bulk.isEmpty()) { %>
            <h3>Ignorées</h3>
            <ul class="chu-skip-summary">
            <% bulk.each { reason, count -> %>
                <li>${ bulkText[reason.name()] ? bulkText[reason.name()](count) : (reason.toString() + ' : ' + count) }</li>
            <% } %>
            </ul>
        <% } %>

        <% def attention = report.skippedNeedingAttention %>
        <% if (!attention.isEmpty()) { %>
            <h3>À vérifier (${ attention.size() })</h3>
            <p class="chu-hint">
                Ces dates concernent une journée précise et peuvent demander une décision.
            </p>
            <div class="chu-table-scroll">
            <table class="chu-table chu-compact">
                <thead><tr><th>Date</th><th>Raison</th><th>Détail</th></tr></thead>
                <tbody>
                <% attention.each { occ -> %>
                    <tr>
                        <td>${ ui.formatDatePretty(java.sql.Date.valueOf(occ.date)) }</td>
                        <td>${ reasonLabels[occ.skipReason.name()] ?: occ.skipReason }</td>
                        <td class="chu-muted">${ ui.encodeHtml(occ.detail ?: '') }</td>
                    </tr>
                <% } %>
                </tbody>
            </table>
            </div>
        <% } %>

        <% if (report.dryRun && report.createdCount > 0) { %>
            <form method="post" class="chu-confirm">
                <input type="hidden" name="templateId" value="${ template.templateId }"/>
                <input type="hidden" name="from" value="${ from }"/>
                <input type="hidden" name="to" value="${ to }"/>
                <p>
                    Vérifiez la liste ci-dessus avant de confirmer. Les tranches déjà
                    présentes dans le calendrier ne seront jamais modifiées.
                </p>
                <button type="submit" name="action" value="generate" class="confirm">
                    Confirmer et créer ${ report.createdCount } tranche(s)
                </button>
            </form>
        <% } %>
    <% } %>

    <p><a href="${ ui.pageLink('chuschedules', 'manageSchedules') }">&larr; Retour aux horaires</a></p>

</div>
