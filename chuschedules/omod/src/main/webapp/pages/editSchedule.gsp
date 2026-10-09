<%
    ui.decorateWith("appui", "standardEmrPage", [ title: "Horaire récurrent" ])
    ui.includeCss("chuschedules", "chuschedules.css")

    def days = [ [1,"Dimanche"], [2,"Lundi"], [3,"Mardi"], [4,"Mercredi"], [5,"Jeudi"], [6,"Vendredi"], [7,"Samedi"] ]

    // The rotation is one dropdown per row rather than three interdependent fields; the
    // value encodes the rule so the server reads back exactly what the user chose.
    def recurrences = [
        [ "WEEKLY:1",   "chaque semaine" ],
        [ "WEEKLY:2",   "une semaine sur deux" ],
        [ "WEEKLY:3",   "une semaine sur trois" ],
        [ "WEEKLY:4",   "une semaine sur quatre" ],
        [ "MONTHLY:1",  "le 1er du mois" ],
        [ "MONTHLY:2",  "le 2e du mois" ],
        [ "MONTHLY:3",  "le 3e du mois" ],
        [ "MONTHLY:4",  "le 4e du mois" ],
        [ "MONTHLY:-1", "le dernier du mois" ]
    ]

    def tokenFor = { r ->
        if (r == null) return "WEEKLY:1"
        if (r.recurrenceType?.name() == "MONTHLY_NTH") return "MONTHLY:" + r.monthOrdinal
        return "WEEKLY:" + (r.weekInterval ?: 1)
    }
    def hhmm = { t -> t == null ? "" : t.toString().substring(0, 5) }

    def rowCount = Math.max(ranges.size() + 3, 6)
%>

<div class="chu-schedules">

    <h2>${ template ? 'Modifier un horaire récurrent' : 'Nouvel horaire récurrent' }</h2>

    <% if (error) { %>
        <div class="chu-error">${ ui.encodeHtml(error) }</div>
    <% } %>

    <form method="post" class="chu-form chu-form-block">
        <% if (template) { %>
            <input type="hidden" name="templateId" value="${ template.templateId }"/>
        <% } %>

        <div class="chu-field">
            <label>Nom</label>
            <input type="text" name="name" size="45" required
                   placeholder="Consultation Neurochirurgie &mdash; Dr ..."
                   value="${ template ? ui.encodeHtmlAttribute(template.name) : '' }"/>
        </div>

        <div class="chu-field">
            <label>Prestataire</label>
            <select name="providerId" required>
                <option value="">&mdash;</option>
                <% providers.each { p -> %>
                    <option value="${ p.providerId }"
                        ${ template?.provider?.providerId == p.providerId ? 'selected' : '' }>
                        ${ ui.encodeHtml(p.name ?: p.identifier ?: ('#' + p.providerId)) }
                    </option>
                <% } %>
            </select>
        </div>

        <div class="chu-field">
            <label>Lieu</label>
            <select name="locationId" required>
                <option value="">&mdash;</option>
                <% locations.each { l -> %>
                    <option value="${ l.locationId }"
                        ${ template?.location?.locationId == l.locationId ? 'selected' : '' }>
                        ${ ui.encodeHtml(l.name) }
                    </option>
                <% } %>
            </select>
        </div>

        <div class="chu-field">
            <label>Services proposés</label>
            <select name="appointmentTypeIds" multiple size="6">
                <% appointmentTypes.each { t -> %>
                    <option value="${ t.appointmentTypeId }"
                        ${ template?.types?.any { it.appointmentTypeId == t.appointmentTypeId } ? 'selected' : '' }>
                        ${ ui.encodeHtml(t.name) }
                    </option>
                <% } %>
            </select>
            <span class="chu-hint">Maintenez Ctrl pour en choisir plusieurs.</span>
        </div>

        <div class="chu-field">
            <label>Valide du</label>
            <input type="date" name="validFrom" required
                   value="${ template ? ui.formatDate(template.validFrom, 'yyyy-MM-dd') : '' }"/>
            <label class="chu-inline">au</label>
            <input type="date" name="validTo"
                   value="${ template?.validTo ? ui.formatDate(template.validTo, 'yyyy-MM-dd') : '' }"/>
            <span class="chu-hint">Laissez vide pour un horaire sans date de fin.</span>
        </div>

        <div class="chu-field">
            <label>
                <input type="checkbox" name="active" value="true"
                    ${ !template || template.active ? 'checked' : '' }/>
                Actif
            </label>
            <span class="chu-hint">Un horaire inactif ne génère plus rien, sans rien supprimer.</span>
        </div>

        <h3>Séances</h3>
        <p class="chu-hint">
            Une ligne par séance. Une matinée et une après-midi le même jour sont deux
            lignes. Laissez les heures vides pour ignorer une ligne.
        </p>

        <table class="chu-table chu-compact">
            <thead>
                <tr><th>Jour</th><th>Début</th><th>Fin</th><th>Répétition</th></tr>
            </thead>
            <tbody>
            <% (0..<rowCount).each { i -> %>
                <% def r = i < ranges.size() ? ranges[i] : null %>
                <tr>
                    <td>
                        <select name="rangeDayOfWeek">
                            <% days.each { d -> %>
                                <option value="${ d[0] }" ${ r?.dayOfWeek == d[0] ? 'selected' : '' }>${ d[1] }</option>
                            <% } %>
                        </select>
                    </td>
                    <td><input type="time" name="rangeStartTime" value="${ hhmm(r?.startTime) }"/></td>
                    <td><input type="time" name="rangeEndTime" value="${ hhmm(r?.endTime) }"/></td>
                    <td>
                        <select name="rangeRecurrence">
                            <% def tok = tokenFor(r) %>
                            <% recurrences.each { rec -> %>
                                <option value="${ rec[0] }" ${ tok == rec[0] ? 'selected' : '' }>${ rec[1] }</option>
                            <% } %>
                        </select>
                    </td>
                </tr>
            <% } %>
            </tbody>
        </table>

        <p>
            <button type="submit" class="confirm">Enregistrer</button>
            <a class="button" href="${ ui.pageLink('chuschedules', 'manageSchedules') }">Annuler</a>
        </p>
    </form>

</div>
