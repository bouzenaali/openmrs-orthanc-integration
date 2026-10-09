<%
    ui.decorateWith("appui", "standardEmrPage")
%>

${ ui.includeCss("agentgateway", "agentgateway.css") }
${ ui.includeJavascript("agentgateway", "agent-log.js") }

<script type="text/javascript">
    var breadcrumbs = [
        { icon: "icon-home", link: '/' + OPENMRS_CONTEXT_PATH + '/index.htm' },
        { label: "Operations de l'assistant" }
    ];
</script>

<div class="agent-layout">
    <% if (accessDenied) { %>
        <div class="agent-panel">
            <div class="agent-panel-content">
                <div class="agent-notice">
                    Vous n'avez pas l'autorisation de consulter le journal de l'assistant.
                </div>
            </div>
        </div>
    <% } else { %>

    <div class="agent-panel">
        <div class="agent-panel-header">
            <h2>Operations de l'assistant</h2>
            <label style="font-weight:normal;font-size:0.9em;">
                <input type="checkbox" id="agentOnlyReversible" onchange="agentLoadLog()"/>
                Uniquement les operations reversibles
            </label>
        </div>
        <div class="agent-panel-content">
            <p class="agent-log-intro">
                Chaque appel effectu&eacute; par l'assistant est enregistr&eacute; ici, qu'il ait
                r&eacute;ussi ou non. Une annulation n'efface rien&nbsp;: elle ajoute l'op&eacute;ration
                inverse au journal.
            </p>
            <table class="agent-log-table">
                <thead>
                <tr>
                    <th>#</th>
                    <th>Date</th>
                    <th>Utilisateur</th>
                    <th>T&acirc;che</th>
                    <th>Appel</th>
                    <th>Statut</th>
                    <th>Reversible</th>
                    <th></th>
                </tr>
                </thead>
                <tbody id="agentLogRows">
                <tr><td colspan="8">Chargement&hellip;</td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <div class="agent-panel" id="agentLogDetailPanel" style="display:none;">
        <div class="agent-panel-header">
            <h2>D&eacute;tail de l'op&eacute;ration <span id="agentLogDetailId"></span></h2>
        </div>
        <div class="agent-panel-content">
            <div id="agentLogDetail"></div>
        </div>
    </div>

    <% } %>
</div>
