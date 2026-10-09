/*
 * Chat panel behaviour.
 *
 * Deliberately thin: it posts the clinician's words to OpenMRS, renders what comes back, and
 * shows a confirmation step when the assistant says one is required. It holds no knowledge of
 * tasks, FHIR, or endpoints - all of that lives on the agent service, and the browser never talks
 * to it directly (see ChatRelayController).
 *
 * The confirmation gate is enforced on the server, not here: this UI only surfaces it. Bypassing
 * this button changes nothing, because the pending action lives in the agent's conversation
 * buffer and is only executed when a confirming turn arrives.
 */
/*
 * The conversation id survives a page reload.
 *
 * It used to be a plain variable, so refreshing the page - or following a link and coming back -
 * silently started a new conversation. Everything the assistant had established went with it: the
 * patient, the field being changed, a half-finished create. The clinician sees the same chat panel
 * and has no way to know it has forgotten them.
 *
 * sessionStorage rather than localStorage on purpose: the id is scoped to this tab and this
 * browsing session, which is the same lifetime the agent's own conversation buffer has (it expires
 * after CONVERSATION_TTL_SECONDS). A localStorage id would outlive the state it refers to and come
 * back pointing at nothing.
 */
var AGENT_CONVERSATION_KEY = 'agentgateway.conversationId';

function agentReadConversationId() {
    try {
        return window.sessionStorage.getItem(AGENT_CONVERSATION_KEY);
    } catch (e) {
        // Private browsing modes and locked-down policies can refuse storage entirely. A
        // conversation that does not persist is the old behaviour, not a broken page.
        return null;
    }
}

function agentRememberConversationId(id) {
    if (!id) {
        return;
    }
    try {
        window.sessionStorage.setItem(AGENT_CONVERSATION_KEY, id);
    } catch (e) {
        /* see agentReadConversationId */
    }
}

var agentConversationId = agentReadConversationId();
var agentBusy = false;

/*
 * The full-screen page sets this true; the dashboard widget leaves it false. Focusing an input
 * inside a widget on page load scrolls the browser down to it, which would drag the clinician
 * away from the patient summary they actually opened the page to read.
 */
var agentAutoFocus = (typeof agentAutoFocus === 'undefined') ? false : agentAutoFocus;

function agentEndpoint(name) {
    return '/' + OPENMRS_CONTEXT_PATH + '/module/agentgateway/' + name;
}

function agentEscape(text) {
    return jQuery('<div/>').text(text === null || text === undefined ? '' : text).html();
}

function agentAppend(text, cssClass) {
    var messages = jQuery('#agentMessages');
    messages.append('<div class="agent-message ' + cssClass + '">' + agentEscape(text).replace(/\n/g, '<br/>') + '</div>');
    messages.scrollTop(messages[0].scrollHeight);
}

/*
 * A waiting indicator, not token streaming.
 *
 * Streaming was the obvious ask and is the wrong trade here. The reply is one short paragraph, not
 * an essay, so there is little to stream; and the path is browser -> OpenMRS -> agent, where the
 * middle hop is HttpJsonClient reading the whole body before it returns. Streaming would mean
 * reworking the Java relay into a chunked proxy for a payload measured in sentences.
 *
 * What actually hurt was the silence: the input greyed out for up to the model's full timeout with
 * nothing on screen. This says the assistant is working, which is the entire felt difference, in
 * fifteen lines that cannot break the relay.
 */
function agentShowThinking() {
    if (jQuery('#agentThinking').length) {
        return;
    }
    jQuery('#agentMessages').append(
        '<div class="agent-message agent-message-bot agent-message-thinking" id="agentThinking">'
        + '<span class="agent-dot"></span><span class="agent-dot"></span><span class="agent-dot"></span>'
        + '</div>');
    var messages = jQuery('#agentMessages');
    messages.scrollTop(messages[0].scrollHeight);
}

function agentHideThinking() {
    jQuery('#agentThinking').remove();
}

function agentSetBusy(busy) {
    agentBusy = busy;
    jQuery('#agentSendButton').prop('disabled', busy);
    jQuery('#agentInput').prop('disabled', busy);
    if (busy) {
        agentShowThinking();
    } else {
        agentHideThinking();
    }
}

function agentShowPending(pendingAction) {
    if (!pendingAction) {
        jQuery('#agentPending').hide();
        jQuery('#agentPendingSummary').empty();
        return;
    }

    var html = '<p>' + agentEscape(pendingAction.summary) + '</p>';
    var operations = pendingAction.operations || [];
    if (operations.length) {
        html += '<ul class="agent-pending-ops">';
        for (var i = 0; i < operations.length; i++) {
            html += '<li>' + agentEscape(operations[i].summary || (operations[i].method + ' ' + operations[i].path)) + '</li>';
        }
        html += '</ul>';
    }
    jQuery('#agentPendingSummary').html(html);
    jQuery('#agentPending').show();
}

function agentPost(message, echo) {
    if (agentBusy) {
        return;
    }
    if (echo) {
        agentAppend(message, 'agent-message-user');
    }
    agentSetBusy(true);
    agentShowPending(null);

    jQuery.post(agentEndpoint('chat.form'), {
        message: message,
        conversationId: agentConversationId,
        patientUuid: agentPatientUuid
    }).done(function (response) {
        agentConversationId = response.conversationId || agentConversationId;
        agentRememberConversationId(agentConversationId);
        agentAppend(response.reply || "L'assistant n'a rien renvoye.", 'agent-message-bot');
        if (response.state === 'awaiting_confirmation') {
            agentShowPending(response.pending_action);
        }
    }).fail(function (xhr) {
        var reply = "L'assistant est momentanement indisponible.";
        if (xhr.status === 403) {
            reply = "Vous n'avez pas l'autorisation d'effectuer cette action.";
        }
        agentAppend(reply, 'agent-message-bot agent-message-error');
    }).always(function () {
        agentSetBusy(false);
        jQuery('#agentInput').focus();
    });
}

function agentSend(event) {
    event.preventDefault();
    var input = jQuery('#agentInput');
    var message = jQuery.trim(input.val());
    if (!message) {
        return false;
    }
    input.val('');
    agentPost(message, true);
    return false;
}

/*
 * "Confirm" and "cancel" are ordinary conversation turns rather than a separate endpoint, so
 * there is exactly one path into the agent and one place where a write can be authorised.
 */
function agentConfirm() {
    agentShowPending(null);
    agentAppend('Confirmer', 'agent-message-user');
    agentPost('oui, je confirme', false);
}

function agentCancel() {
    agentShowPending(null);
    agentAppend('Annuler', 'agent-message-user');
    agentPost('non, annuler', false);
}

jQuery(function () {
    // Read the flag here rather than at load time: this file is included in the page head, so
    // the inline script that sets it has not run yet when the file itself is evaluated.
    if (agentAutoFocus) {
        jQuery('#agentInput').focus();
    }
});
