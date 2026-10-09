<%
    ui.decorateWith("appui", "standardEmrPage", [ title: "Jours fériés et congés" ])
    ui.includeCss("chuschedules", "chuschedules.css")
%>

<div class="chu-schedules">

    <h2>Jours fériés et congés</h2>
    <p class="chu-intro">
        Aucune tranche n'est générée sur ces dates. Sans prestataire, l'exclusion vaut pour
        tout le service. Les fêtes lunaires se déplaçant chaque année, elles sont saisies
        ici année par année.
    </p>

    <% if (error) { %>
        <div class="chu-error">${ ui.encodeHtml(error) }</div>
    <% } %>

    <form method="post" class="chu-form">
        <label>Date <input type="date" name="exceptionDate" required/></label>
        <label>Motif <input type="text" name="reason" size="30" required placeholder="Aïd el-Fitr, congé annuel..."/></label>
        <label>Prestataire
            <select name="providerId">
                <option value="">Tout le service</option>
                <% providers.each { p -> %>
                    <option value="${ p.providerId }">${ ui.encodeHtml(p.name ?: p.identifier ?: ('#' + p.providerId)) }</option>
                <% } %>
            </select>
        </label>
        <button type="submit" class="confirm">Ajouter</button>
    </form>

    <% if (exceptions.isEmpty()) { %>
        <div class="chu-empty">Aucune date exclue.</div>
    <% } else { %>
        <table class="chu-table">
            <thead><tr><th>Date</th><th>Motif</th><th>Portée</th><th></th></tr></thead>
            <tbody>
            <% exceptions.each { e -> %>
                <tr>
                    <td>${ ui.formatDatePretty(e.exceptionDate) }</td>
                    <td>${ ui.encodeHtml(e.reason) }</td>
                    <td>
                        <% if (e.provider == null) { %>
                            <span class="chu-badge">tout le service</span>
                        <% } else { %>
                            ${ ui.encodeHtml(e.provider.name ?: '') }
                        <% } %>
                    </td>
                    <td>
                        <form method="post" class="chu-inline-form">
                            <input type="hidden" name="action" value="delete"/>
                            <input type="hidden" name="exceptionId" value="${ e.exceptionId }"/>
                            <button type="submit" class="chu-link-button">Supprimer</button>
                        </form>
                    </td>
                </tr>
            <% } %>
            </tbody>
        </table>
    <% } %>

    <p><a href="${ ui.pageLink('chuschedules', 'manageSchedules') }">&larr; Retour aux horaires</a></p>

</div>
