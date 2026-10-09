/*
 * Dictation for the chat composer.
 *
 * Click the microphone to start, click again to stop. The transcript lands in the input box as an
 * editable draft - the clinician reads it, fixes anything wrong, and presses send themselves.
 *
 * Two rules that are not preferences:
 *
 *   1. THE TRANSCRIPT NEVER AUTO-SENDS. It is appended to #agentInput and nothing else happens.
 *      There is no code path here that calls agentSend().
 *   2. VOICE NEVER CONFIRMS. Confirmer/Annuler stay buttons; this file cannot reach agentConfirm().
 *
 * Speech recognition is not perfect and never will be. Those two rules are what make that a
 * usability property rather than a safety one: a mis-transcription sits visibly in a box the
 * clinician is already reading, and behind that still sit the interpreter's refusal to turn
 * descriptive phrasing into a write and the confirmation gate's explicit "oui".
 *
 * Audio is captured as 16 kHz mono 16-bit PCM and sent raw. That is not an optimisation - the
 * transcription engine rejects anything else outright. MediaRecorder would have meant WebM/Opus,
 * a container to reassemble and a codec stack on the server; this way the server has neither.
 */

var AGENT_VOICE_SAMPLE_RATE = 16000;
var AGENT_VOICE_MAX_SECONDS = 30;
/* Below this RMS nothing is sent. The server checks again and is authoritative - this only
 * saves a pointless round trip when the clinician clicked by accident. */
var AGENT_VOICE_SILENCE_RMS = 200;

var agentVoiceState = {
    recording: false,
    stream: null,
    context: null,
    processor: null,
    source: null,
    chunks: [],
    frames: 0,
    startedAt: 0,
    timer: null,
    lastInsert: null      /* {start, end} of the last transcript, for undo */
};

function agentVoiceSupported() {
    return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.AudioContext);
}

function agentVoiceSetStatus(text, cssClass) {
    var el = jQuery('#agentVoiceStatus');
    el.removeClass('agent-voice-error agent-voice-recording');
    if (cssClass) {
        el.addClass(cssClass);
    }
    el.text(text || '');
}

function agentVoiceSetButton(recording) {
    var btn = jQuery('#agentVoiceButton');
    btn.toggleClass('agent-voice-active', !!recording);
    btn.attr('title', recording ? 'Arreter la dictee' : 'Dicter');
    btn.attr('aria-pressed', recording ? 'true' : 'false');
}

/* --------------------------------------------------------------- resampling
 *
 * The browser gives whatever rate the hardware runs at - 44.1 or 48 kHz typically. The engine
 * wants exactly 16 kHz mono. Averaging each source window rather than picking one sample out of
 * three is a cheap low-pass: plain decimation aliases, and aliasing on speech sounds like a
 * lisp to the model.
 */
function agentVoiceDownsample(input, inputRate, outputRate) {
    if (outputRate === inputRate) {
        return input;
    }
    var ratio = inputRate / outputRate;
    var length = Math.round(input.length / ratio);
    var result = new Float32Array(length);
    var offset = 0;
    for (var i = 0; i < length; i++) {
        var next = Math.round((i + 1) * ratio);
        var sum = 0, count = 0;
        for (var j = offset; j < next && j < input.length; j++) {
            sum += input[j];
            count++;
        }
        result[i] = count ? sum / count : 0;
        offset = next;
    }
    return result;
}

function agentVoiceToInt16(float32) {
    var out = new Int16Array(float32.length);
    for (var i = 0; i < float32.length; i++) {
        var s = Math.max(-1, Math.min(1, float32[i]));
        out[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
    return out;
}

function agentVoiceRms(int16) {
    if (!int16.length) {
        return 0;
    }
    var sum = 0;
    for (var i = 0; i < int16.length; i++) {
        sum += int16[i] * int16[i];
    }
    return Math.sqrt(sum / int16.length);
}

/* --------------------------------------------------------------- capture */

function agentVoiceStart() {
    if (agentVoiceState.recording) {
        return;
    }
    if (!agentVoiceSupported()) {
        agentVoiceSetStatus('La dictee n\'est pas disponible dans ce navigateur.', 'agent-voice-error');
        return;
    }

    navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }
    }).then(function (stream) {
        var ctx = new (window.AudioContext || window.webkitAudioContext)();
        var source = ctx.createMediaStreamSource(stream);
        /*
         * ScriptProcessorNode is deprecated in favour of AudioWorklet, and is used anyway: an
         * AudioWorklet needs its processor loaded from a separate URL, which inside OpenMRS's UI
         * Framework means another resource route and another thing to get wrong on deployment.
         * This runs in every browser the department uses, and one 30-second utterance is not a
         * workload where the main-thread cost matters.
         */
        var processor = ctx.createScriptProcessor(4096, 1, 1);

        agentVoiceState.chunks = [];
        agentVoiceState.frames = 0;

        processor.onaudioprocess = function (event) {
            if (!agentVoiceState.recording) {
                return;
            }
            var input = event.inputBuffer.getChannelData(0);
            var down = agentVoiceDownsample(input, ctx.sampleRate, AGENT_VOICE_SAMPLE_RATE);
            agentVoiceState.chunks.push(agentVoiceToInt16(down));
            agentVoiceState.frames += down.length;

            if (agentVoiceState.frames >= AGENT_VOICE_SAMPLE_RATE * AGENT_VOICE_MAX_SECONDS) {
                /* The cap exists so a forgotten click costs one decode, not a runaway. */
                agentVoiceStop();
            }
        };

        source.connect(processor);
        processor.connect(ctx.destination);

        agentVoiceState.stream = stream;
        agentVoiceState.context = ctx;
        agentVoiceState.source = source;
        agentVoiceState.processor = processor;
        agentVoiceState.recording = true;
        agentVoiceState.startedAt = Date.now();

        agentVoiceSetButton(true);
        /*
         * With click-to-toggle there is no held button telling the clinician they are still
         * recording, so the elapsed counter is doing real work, not decoration.
         */
        agentVoiceState.timer = setInterval(function () {
            var secs = Math.floor((Date.now() - agentVoiceState.startedAt) / 1000);
            agentVoiceSetStatus('Enregistrement… ' + secs + 's (cliquez pour arreter)',
                'agent-voice-recording');
        }, 250);
        agentVoiceSetStatus('Enregistrement… 0s (cliquez pour arreter)', 'agent-voice-recording');
    }).catch(function (err) {
        var message = 'Microphone indisponible.';
        if (err && (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError')) {
            message = 'Acces au microphone refuse.';
        } else if (err && err.name === 'NotFoundError') {
            message = 'Aucun microphone detecte.';
        }
        agentVoiceSetStatus(message, 'agent-voice-error');
        agentVoiceSetButton(false);
    });
}

function agentVoiceTeardown() {
    if (agentVoiceState.processor) {
        agentVoiceState.processor.disconnect();
        agentVoiceState.processor.onaudioprocess = null;
    }
    if (agentVoiceState.source) {
        agentVoiceState.source.disconnect();
    }
    if (agentVoiceState.context) {
        agentVoiceState.context.close();
    }
    if (agentVoiceState.stream) {
        /* Releases the microphone, and with it the browser's recording indicator. Leaving this
         * out leaves a tab that looks like it is still listening. */
        agentVoiceState.stream.getTracks().forEach(function (t) { t.stop(); });
    }
    if (agentVoiceState.timer) {
        clearInterval(agentVoiceState.timer);
    }
    agentVoiceState.processor = null;
    agentVoiceState.source = null;
    agentVoiceState.context = null;
    agentVoiceState.stream = null;
    agentVoiceState.timer = null;
    agentVoiceState.recording = false;
}

function agentVoiceStop() {
    if (!agentVoiceState.recording) {
        return;
    }
    var chunks = agentVoiceState.chunks;
    agentVoiceTeardown();
    agentVoiceSetButton(false);

    var total = 0, i;
    for (i = 0; i < chunks.length; i++) {
        total += chunks[i].length;
    }
    if (total < AGENT_VOICE_SAMPLE_RATE * 0.3) {
        agentVoiceSetStatus('');
        return;
    }

    var merged = new Int16Array(total);
    var offset = 0;
    for (i = 0; i < chunks.length; i++) {
        merged.set(chunks[i], offset);
        offset += chunks[i].length;
    }
    agentVoiceState.chunks = [];

    if (agentVoiceRms(merged) < AGENT_VOICE_SILENCE_RMS) {
        /* Nothing was said. Say nothing: an error message here would be noise, and the server
         * would refuse it anyway. */
        agentVoiceSetStatus('');
        return;
    }

    agentVoiceSend(merged);
}

function agentVoiceToggle() {
    if (agentVoiceState.recording) {
        agentVoiceStop();
    } else {
        agentVoiceStart();
    }
}

function agentVoiceCancel() {
    if (!agentVoiceState.recording) {
        return;
    }
    agentVoiceState.chunks = [];
    agentVoiceTeardown();
    agentVoiceSetButton(false);
    agentVoiceSetStatus('');
}

/* --------------------------------------------------------------- transport */

function agentVoiceSend(int16) {
    agentVoiceSetStatus('Transcription…');
    jQuery('#agentVoiceButton').prop('disabled', true);

    jQuery.ajax({
        url: '/' + OPENMRS_CONTEXT_PATH + '/module/agentgateway/transcribe.form',
        type: 'POST',
        data: int16.buffer,
        processData: false,
        contentType: 'application/octet-stream'
    }).done(function (response) {
        if (!response || response.success !== true) {
            var reason = response && response.reason;
            agentVoiceSetStatus(
                reason === 'busy' ? 'Une dictee est deja en cours.'
                    : reason === 'not_configured' ? 'La dictee n\'est pas configuree.'
                    : reason === 'too_long' ? 'Dictee trop longue (30s maximum).'
                    : 'Dictee momentanement indisponible.',
                'agent-voice-error');
            return;
        }
        var text = jQuery.trim(response.text || '');
        if (!text) {
            /* silence or too_short - the server said nothing was said. Not an error. */
            agentVoiceSetStatus('');
            return;
        }
        agentVoiceInsert(text);
        agentVoiceSetStatus('');
    }).fail(function (xhr) {
        agentVoiceSetStatus(xhr.status === 403
            ? 'Vous n\'avez pas l\'autorisation d\'utiliser la dictee.'
            : 'Dictee momentanement indisponible.', 'agent-voice-error');
    }).always(function () {
        jQuery('#agentVoiceButton').prop('disabled', false);
    });
}

/*
 * Append, never replace, and leave the cursor at the end - so dictating, typing, and dictating
 * again all compose into one sentence instead of overwriting each other.
 *
 * This is the ONLY thing done with a transcript. It is not sent.
 */
function agentVoiceInsert(text) {
    var input = jQuery('#agentInput');
    var existing = input.val() || '';
    var separator = (existing && !/\s$/.test(existing)) ? ' ' : '';
    var start = existing.length + separator.length;

    input.val(existing + separator + text);
    agentVoiceState.lastInsert = { start: start, end: start + text.length };

    var el = input.get(0);
    if (el && el.setSelectionRange) {
        el.focus();
        el.setSelectionRange(el.value.length, el.value.length);
    } else {
        input.focus();
    }
    jQuery('#agentVoiceUndo').show();
}

/* Removes only what the last dictation added, not the whole box. */
function agentVoiceUndo() {
    var mark = agentVoiceState.lastInsert;
    if (!mark) {
        return;
    }
    var input = jQuery('#agentInput');
    var value = input.val() || '';
    if (mark.end <= value.length) {
        var before = value.slice(0, mark.start);
        input.val((before.replace(/\s+$/, '') + value.slice(mark.end)).replace(/^\s+/, ''));
    }
    agentVoiceState.lastInsert = null;
    jQuery('#agentVoiceUndo').hide();
    input.focus();
}

jQuery(function () {
    if (!agentVoiceSupported()) {
        /* Hide rather than show-and-fail: a button that does nothing is worse than no button. */
        jQuery('.agent-voice-controls').hide();
        return;
    }
    jQuery('#agentVoiceUndo').hide();
    jQuery(document).on('keydown', function (e) {
        if (e.key === 'Escape' && agentVoiceState.recording) {
            agentVoiceCancel();
        }
    });
});
