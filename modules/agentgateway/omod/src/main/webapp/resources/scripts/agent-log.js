/*
 * Administrator's view of the assistant's operation log.
 *
 * A rollback is always a two-step affair here: the dry-run check reports what would happen and
 * why, and only then is the real thing offered. That is deliberate - "manual intervention
 * required" is a normal and frequent answer, and finding that out should not require attempting
 * the change first.
 */
function agentLogEndpoint(name) {
    return '/' + OPENMRS_CONTEXT_PATH + '/module/agentgateway/' + name;
}

function agentLogEscape(text) {
    return jQuery('<div/>').text(text === null || text === undefined ? '' : text).html();
}

function agentReversibilityCell(row) {
    if (row.dateRolledBack) {
        return '<span class="agent-log-rolled-back">Annulee par ' + agentLogEscape(row.rolledBackBy) + '</span>';
    }
    if (row.reversible) {
        return '<span class="agent-log-reversible">oui</span>';
    }
    return '<span class="agent-log-not-reversible" title="' + agentLogEscape(row.reversibilityNote) + '">non</span>';
}

function agentLoadLog() {
    var onlyReversible = jQuery('#agentOnlyReversible').is(':checked');
    jQuery.get(agentLogEndpoint('log.form'), { onlyReversible: onlyReversible, limit: 100 })
        .done(function (response) {
            var rows = (response && response.results) || [];
            if (!rows.length) {
                jQuery('#agentLogRows').html('<tr><td colspan="8">Aucune operation enregistree.</td></tr>');
                return;
            }
            var html = '';
            for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                html += '<tr>'
                    + '<td>' + agentLogEscape(row.id) + '</td>'
                    + '<td>' + agentLogEscape(row.dateCreated) + '</td>'
                    + '<td>' + agentLogEscape(row.actingUser) + '</td>'
                    + '<td>' + agentLogEscape(row.taskType) + '</td>'
                    + '<td><code>' + agentLogEscape(row.targetEndpoint) + '</code></td>'
                    + '<td>' + agentLogEscape(row.responseStatus) + '</td>'
                    + '<td>' + agentReversibilityCell(row) + '</td>'
                    + '<td><button type="button" class="agent-btn" onclick="agentShowDetail(' + row.id + ')">Detail</button></td>'
                    + '</tr>';
            }
            jQuery('#agentLogRows').html(html);
        })
        .fail(function () {
            jQuery('#agentLogRows').html('<tr><td colspan="8">Le journal n\'a pas pu etre charge.</td></tr>');
        });
}

function agentShowDetail(logId) {
    jQuery.get(agentLogEndpoint('logEntry.form'), { logId: logId }).done(function (response) {
        if (!response || !response.success) {
            return;
        }
        var op = response.operation;
        var html = '<dl>'
            + '<dt>Demande de l\'utilisateur</dt><dd>' + agentLogEscape(op.rawPrompt) + '</dd>'
            + '<dt>Appel</dt><dd><code>' + agentLogEscape(op.targetEndpoint) + '</code></dd>'
            + '<dt>Corps envoye</dt><dd><pre>' + agentLogEscape(op.requestBody) + '</pre></dd>'
            + '<dt>Etat precedent</dt><dd><pre>' + agentLogEscape(op.previousState) + '</pre></dd>'
            + '<dt>Reponse</dt><dd><pre>' + agentLogEscape(op.responseBody) + '</pre></dd>'
            + '</dl>'
            + '<div id="agentRollbackVerdict"></div>'
            + '<button type="button" class="agent-btn" onclick="agentCheckRollback(' + logId + ')">Verifier si annulable</button> '
            + '<button type="button" class="agent-btn agent-btn-primary" id="agentRollbackButton" style="display:none;" onclick="agentDoRollback(' + logId + ')">Annuler cette operation</button>';

        jQuery('#agentLogDetailId').text('#' + logId);
        jQuery('#agentLogDetail').html(html);
        jQuery('#agentLogDetailPanel').show();
    });
}

function agentCheckRollback(logId) {
    jQuery.get(agentLogEndpoint('rollbackCheck.form'), { logId: logId }).done(function (response) {
        jQuery('#agentRollbackVerdict').html('<div class="agent-notice agent-notice-info">'
            + agentLogEscape(response.outcome) + ' &mdash; ' + agentLogEscape(response.reason) + '</div>');
        // Only an operation the coherence checks actually cleared gets a button.
        jQuery('#agentRollbackButton').toggle(response.outcome === 'REVERSIBLE');
    });
}

function agentDoRollback(logId) {
    jQuery.post(agentLogEndpoint('rollback.form'), { logId: logId }).done(function (response) {
        jQuery('#agentRollbackVerdict').html('<div class="agent-notice agent-notice-info">'
            + agentLogEscape(response.outcome) + ' &mdash; ' + agentLogEscape(response.reason) + '</div>');
        jQuery('#agentRollbackButton').hide();
        agentLoadLog();
    });
}

jQuery(function () {
    agentLoadLog();
});
