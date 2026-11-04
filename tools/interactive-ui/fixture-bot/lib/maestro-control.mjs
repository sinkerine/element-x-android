// Copyright 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
// Maestro's JavaScript runtime (not imported by Node). Token is forwarded from the owner-only host file.
function ctl(args) {
    var response = http.post('http://127.0.0.1:18029/ctl', {
        headers: { Authorization: 'Bearer ' + MAESTRO_INTERACTIVE_UI_BOT_TOKEN, 'Content-Type': 'application/json' },
        body: JSON.stringify({ args: args })
    });
    if (response.status !== 200) throw new Error('Fixture control request failed');
    return JSON.parse(response.body);
}
if (PHASE === 'send') {
    output.uiEvent = ctl(['send', 'knowledge-map.en']).event_id;
    output.botMark = ctl(['mark']).mark;
} else if (PHASE === 'local') {
    if (ctl(['count-events', '--since', output.botMark, '--sender', 'alice']).count !== 0) throw new Error('Local controls sent events');
} else if (PHASE === 'action') {
    var actions = ctl(['actions', '--since', output.botMark, '--sender', 'alice']).actions;
    if (actions.length !== 1) throw new Error('Expected exactly one decrypted action');
    var received = actions[0];
    var action = received.action;
    if (!received.encrypted || received.wire_type !== 'm.room.encrypted' || received.decrypted_type !== 'm.room.message') throw new Error('Action encryption not observed');
    if (action.version !== 1 || action.action_id !== 'explain_node' || action.revision !== 0 || action.ui_event_id !== output.uiEvent) throw new Error('Action identity mismatch');
    if (JSON.stringify(action.values) !== JSON.stringify({ focus: 'maillard' })) throw new Error('Action values mismatch');
    if (!received.nonce_base64url_32 || received.nonce_length !== 32) throw new Error('Nonce format mismatch');
    if (JSON.stringify(received.mentions.user_ids) !== JSON.stringify(['@uibot:interactive-ui.test'])) throw new Error('Mention mismatch');
    if (received.relation['m.in_reply_to'].event_id !== output.uiEvent) throw new Error('Reply relation mismatch');
} else throw new Error('Unknown Maestro control phase');
