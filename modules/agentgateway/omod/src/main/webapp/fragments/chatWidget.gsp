<%
    ui.includeCss("agentgateway", "agentgateway.css")
    ui.includeJavascript("agentgateway", "agent-chat.js")
    if (canDictate) {
        ui.includeJavascript("agentgateway", "agent-voice.js")
    }
%>

<script type="text/javascript">
    // Rendered through explicit fallbacks: an interpolation that came out null would emit
    // "var agentCanWrite = ;" and take the whole page's JavaScript down with it, on a screen
    // clinicians use for everything else.
    var agentPatientUuid = "${ patientUuid ?: '' }";
    var agentCanWrite = ${ canWrite ? 'true' : 'false' };
    var agentAutoFocus = false;
</script>

<div class="info-section agent-widget">
    <div class="info-header">
        <i class="icon-comments"></i>
        <h3>ASSISTANT CLINIQUE</h3>
        <i class="icon-share-alt edit-action right" title="Ouvrir en plein ecran"
           onclick="location.href='${ ui.pageLink("agentgateway", "chat", [patientId: patientUuid]) }';"></i>
    </div>
    <div class="info-body">
        <% if (!canUse) { %>
            <div class="agent-notice">
                Vous n'avez pas l'autorisation d'utiliser l'assistant clinique.
            </div>
        <% } else { %>

        <div id="agentMessages" class="agent-messages agent-messages-compact" aria-live="polite">
            <div class="agent-message agent-message-bot">
                Ce patient est deja selectionne. Demandez-moi son dossier, ou dictez ce qu'il
                faut enregistrer.
            </div>
        </div>

        <div id="agentPending" class="agent-pending" style="display:none;">
            <div class="agent-pending-title">Confirmation requise</div>
            <div id="agentPendingSummary" class="agent-pending-summary"></div>
            <div class="agent-pending-actions">
                <button type="button" class="agent-btn agent-btn-primary" onclick="agentConfirm()">Confirmer</button>
                <button type="button" class="agent-btn" onclick="agentCancel()">Annuler</button>
            </div>
        </div>

        <% if (canDictate) { %>
        <div class="agent-voice-controls">
            <button type="button" id="agentVoiceButton" class="agent-voice-btn"
                    onclick="agentVoiceToggle()" title="Dicter" aria-pressed="false">
                <svg class="agent-voice-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="9" y="2" width="6" height="11" rx="3"/><path d="M5 10v1a7 7 0 0 0 14 0v-1"/><line x1="12" y1="18" x2="12" y2="22"/><line x1="8" y1="22" x2="16" y2="22"/></svg>
            </button>
            <span id="agentVoiceStatus" class="agent-voice-status" aria-live="polite"></span>
            <button type="button" id="agentVoiceUndo" class="agent-voice-undo"
                    onclick="agentVoiceUndo()" title="Annuler la derniere dictee">annuler la dictee</button>
        </div>
        <% } %>

        <form class="agent-composer" onsubmit="return agentSend(event)">
            <input type="text" id="agentInput" autocomplete="off" placeholder="Votre demande&hellip;"/>
            <button type="submit" class="agent-btn agent-btn-primary" id="agentSendButton">&rsaquo;</button>
        </form>

        <% } %>
    </div>
</div>
