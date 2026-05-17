/* global StompJs, SockJS, marked */

'use strict';

marked.use({ breaks: true, gfm: true });

const messagesArea   = document.getElementById('messages-area');
const typingEl       = document.getElementById('typing-indicator');
const messageInput   = document.getElementById('message-input');
const sendBtn        = document.getElementById('send-btn');
const chatForm       = document.getElementById('chat-form');
const providerSel    = document.getElementById('provider-select');
const statusDot      = document.getElementById('status-dot');
const sessionDisplay = document.getElementById('session-display');
const attachStrip    = document.getElementById('attachment-strip');
const fileInput      = document.getElementById('file-input');
const uploadFilesBtn = document.getElementById('upload-files-btn');

let sessionUuid = null;
let stomp       = null;
let waiting     = false;

// Staged attachments: [{uuid, name, mimeType, aiSupported, chipEl}]
let stagedAttachments = [];

// ── Utilities ────────────────────────────────────────────────────────────────

function escapeHtml(s) {
    return s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function setStatus(state) {
    statusDot.className = 'status-dot ms-1 ' + state;
    const labels = { connected: 'Connected', disconnected: 'Disconnected', connecting: 'Connecting\u2026' };
    statusDot.title = labels[state] || state;
}

function scrollBottom() {
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

function showTyping(on) {
    typingEl.classList.toggle('d-none', !on);
    if (on) { scrollBottom(); }
}

function setInputEnabled(on) {
    messageInput.disabled = !on;
    sendBtn.disabled = !on;
    waiting = !on;
}

// ── MIME-type helpers ─────────────────────────────────────────────────────────

function isImage(mime) {
    return mime && mime.startsWith('image/');
}

/** Returns a Font Awesome class name for a given MIME type. */
function mimeIcon(mime) {
    if (!mime) return 'fa-file';
    if (mime.startsWith('image/'))       return 'fa-file-image';
    if (mime.startsWith('audio/'))       return 'fa-file-audio';
    if (mime.startsWith('video/'))       return 'fa-file-video';
    if (mime === 'application/pdf')      return 'fa-file-pdf';
    if (mime.startsWith('text/'))        return 'fa-file-lines';
    if (mime.includes('zip') || mime.includes('compressed')) return 'fa-file-zipper';
    if (mime.includes('word') || mime.includes('document'))  return 'fa-file-word';
    if (mime.includes('sheet') || mime.includes('excel'))    return 'fa-file-excel';
    return 'fa-file';
}

// ── Render a single message bubble ───────────────────────────────────────────

function renderAttachmentsHtml(attachments) {
    if (!attachments || attachments.length === 0) return '';
    let html = '<div class="bubble-attachments">';
    for (const att of attachments) {
        if (isImage(att.mimeType)) {
            html += '<img class="bubble-img-thumb" src="/api/files/' + att.uuid + '"'
                  + ' alt="' + escapeHtml(att.name) + '"'
                  + ' data-src="/api/files/' + att.uuid + '"'
                  + ' title="' + escapeHtml(att.name) + '">';
        } else {
            html += '<a class="bubble-file-link" href="/api/files/' + att.uuid + '"'
                  + ' download="' + escapeHtml(att.name) + '" target="_blank">'
                  + '<i class="fa-solid ' + mimeIcon(att.mimeType) + '"></i>'
                  + escapeHtml(att.name)
                  + '</a>';
        }
    }
    html += '</div>';
    return html;
}

function renderMessage(msg) {
    const isUser    = msg.role === 'USER';
    const textHtml  = isUser
        ? escapeHtml(msg.content || '').replace(/\n/g, '<br>')
        : marked.parse(msg.content || '');

    const bubbleCls  = isUser ? 'user' : (msg.role === 'ERROR' ? 'error' : 'assistant');
    const avatarCls  = isUser ? 'user' : 'assistant';
    const avatarIcon = isUser
        ? '<i class="fa-solid fa-user"></i>'
        : '<i class="fa-solid fa-robot"></i>';

    const attachHtml = renderAttachmentsHtml(msg.attachments);

    const row = document.createElement('div');
    row.className = 'message-row' + (isUser ? ' user' : '');
    row.innerHTML =
        '<div class="avatar ' + avatarCls + '">' + avatarIcon + '</div>' +
        '<div class="bubble ' + bubbleCls + '">' + attachHtml + textHtml + '</div>';

    // Wire up lightbox on image thumbnails
    row.querySelectorAll('.bubble-img-thumb').forEach(function (img) {
        img.addEventListener('click', function () { openLightbox(img.dataset.src); });
    });

    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

function isUiEventFrame(obj) {
    return obj && typeof obj === 'object'
        && typeof obj.type === 'string'
        && typeof obj.intent === 'string'
        && obj.payload && typeof obj.payload === 'object';
}

function tryParseJson(text) {
    if (!text || typeof text !== 'string') return null;
    try {
        return JSON.parse(text);
    } catch (_) {
        return null;
    }
}

function renderPromptRequiredFrame(frame) {
    const payload = frame.payload || {};
    const toolName = payload.toolName || 'unknown-tool';
    const toolCallId = payload.toolCallId || '';
    const args = typeof payload.arguments === 'string' ? payload.arguments : JSON.stringify(payload.arguments || {});
    const actions = Array.isArray(payload.actions) ? payload.actions : [];

    const row = document.createElement('div');
    row.className = 'message-row';
    row.innerHTML =
        '<div class="avatar assistant"><i class="fa-solid fa-robot"></i></div>' +
        '<div class="bubble assistant prompt-required">' +
        '  <div class="prompt-title"><i class="fa-solid fa-shield-halved"></i> Authorization Required</div>' +
        '  <div class="prompt-meta"><strong>Tool:</strong> ' + escapeHtml(toolName) + '</div>' +
        '  <div class="prompt-meta"><strong>Call ID:</strong> ' + escapeHtml(toolCallId) + '</div>' +
        '  <pre class="prompt-args">' + escapeHtml(args) + '</pre>' +
        '  <div class="prompt-actions"></div>' +
        '</div>';

    const actionsEl = row.querySelector('.prompt-actions');
    actions.forEach(function (action) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm prompt-action-btn';
        btn.dataset.action = action;
        btn.textContent = action;

        if (action === 'DENIED') {
            btn.classList.add('btn-outline-danger');
        } else {
            btn.classList.add('btn-outline-primary');
        }

        btn.addEventListener('click', function () {
            sendAuthorizationAction(frame.eventId, action, {});
            row.querySelectorAll('.prompt-action-btn').forEach(function (b) { b.disabled = true; });
        });
        actionsEl.appendChild(btn);
    });

    messagesArea.insertBefore(row, typingEl);
    scrollBottom();
}

function handleIncomingUiFrame(frame) {
    switch (frame.type) {
        case 'PROMPT_REQUIRED':
            renderPromptRequiredFrame(frame);
            setInputEnabled(true);
            showTyping(false);
            return;

        case 'TEXT_RESPONSE':
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
                return;
            }
            if (frame.payload && typeof frame.payload.content === 'string') {
                renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
                return;
            }
            renderMessage({ role: 'ASSISTANT', content: '' });
            return;

        case 'ERROR':
            renderMessage({
                role: 'ERROR',
                content: (frame.payload && frame.payload.message) ? String(frame.payload.message) : 'Unknown error'
            });
            return;

        default:
            if (frame.payload && frame.payload.message && typeof frame.payload.message === 'object') {
                renderMessage(frame.payload.message);
            }
    }
}

function renderSessionRecord(msg) {
    if (msg.role === 'PROMPT_REQUIRED') {
        const frame = tryParseJson(msg.content);
        if (frame && isUiEventFrame(frame)) {
            renderPromptRequiredFrame(frame);
            return;
        }
    }

    if (msg.role === 'TEXT_RESPONSE') {
        const frame = tryParseJson(msg.content);
        if (frame && frame.payload && typeof frame.payload.content === 'string') {
            renderMessage({ role: 'ASSISTANT', content: frame.payload.content });
            return;
        }
    }

    // Hide raw tool payload records from the visible transcript.
    if (msg.role === 'TOOL') {
        return;
    }

    renderMessage(msg);
}

function sendAuthorizationAction(eventId, action, fields) {
    if (!sessionUuid) return;

    showTyping(true);
    setInputEnabled(false);

    fetch('/api/chat/respond/' + sessionUuid, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            eventId: eventId,
            action: action,
            fields: fields || {}
        })
    })
        .then(function (resp) {
            if (!resp.ok) {
                return resp.text().then(function (body) {
                    throw new Error(body || ('HTTP ' + resp.status));
                });
            }
            // Response is acknowledged here; rendering is driven by websocket
            // broadcast to avoid duplicate message rendering.
            return resp.text();
        })
        .then(function () {
            // Keep waiting state until websocket event arrives.
        })
        .catch(function (err) {
            showTyping(false);
            setInputEnabled(true);
            renderMessage({ role: 'ERROR', content: 'Failed to submit authorization response: ' + err.message });
        });
}

// ── Lightbox ──────────────────────────────────────────────────────────────────

function openLightbox(src) {
    let lb = document.getElementById('lightbox');
    if (!lb) {
        lb = document.createElement('div');
        lb.id = 'lightbox';
        lb.addEventListener('click', function () { lb.remove(); });
        document.body.appendChild(lb);
    }
    lb.innerHTML = '<img src="' + src + '" alt="attachment">';
    document.body.appendChild(lb);
}

// ── Auto-resize textarea ─────────────────────────────────────────────────────

messageInput.addEventListener('input', function () {
    this.style.height = 'auto';
    this.style.height = Math.min(this.scrollHeight, 140) + 'px';
});

messageInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        chatForm.dispatchEvent(new Event('submit', { cancelable: true }));
    }
});

// ── Attachment strip helpers ──────────────────────────────────────────────────

function syncStripVisibility() {
    attachStrip.classList.toggle('d-none', attachStrip.children.length === 0);
}

function removeAttachment(uuid) {
    const idx = stagedAttachments.findIndex(function (a) { return a.uuid === uuid; });
    if (idx === -1) return;
    stagedAttachments[idx].chipEl.remove();
    stagedAttachments.splice(idx, 1);
    syncStripVisibility();
}

/**
 * Creates an attachment chip and appends it to the strip.
 * Returns the chip element so it can be updated (spinner → final state).
 */
function createChip(tempId, filename) {
    const chip = document.createElement('div');
    chip.className = 'attach-chip';
    chip.dataset.tempId = tempId;

    // Spinner overlay (shown while uploading)
    chip.innerHTML =
        '<div class="chip-spinner">' +
        '  <div class="spinner-border spinner-border-sm text-light" role="status"></div>' +
        '</div>' +
        '<span class="chip-label">' + escapeHtml(filename) + '</span>';

    attachStrip.appendChild(chip);
    syncStripVisibility();
    return chip;
}

function finaliseChip(chip, attachment) {
    // Remove spinner
    const spinner = chip.querySelector('.chip-spinner');
    if (spinner) spinner.remove();

    if (isImage(attachment.mimeType)) {
        const img = document.createElement('img');
        img.className = 'chip-thumb';
        img.src = attachment.url;
        img.alt = attachment.name;
        chip.insertBefore(img, chip.firstChild);
    } else {
        const icon = document.createElement('i');
        icon.className = 'fa-solid ' + mimeIcon(attachment.mimeType) + ' chip-icon';
        chip.insertBefore(icon, chip.firstChild);
    }

    // Label
    let label = chip.querySelector('.chip-label');
    if (!label) {
        label = document.createElement('span');
        label.className = 'chip-label';
        chip.appendChild(label);
    }
    label.textContent = attachment.name;

    // Unsupported-by-AI warning
    if (!attachment.aiSupported) {
        const warn = document.createElement('i');
        warn.className = 'fa-solid fa-triangle-exclamation chip-warn';
        warn.title = 'This file type cannot be processed by the AI';
        chip.appendChild(warn);
    }

    // Remove button
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'chip-remove';
    btn.innerHTML = '<i class="fa-solid fa-xmark"></i>';
    btn.title = 'Remove';
    btn.addEventListener('click', function (e) {
        e.stopPropagation();
        removeAttachment(attachment.uuid);
    });
    chip.appendChild(btn);
}

function markChipError(chip, filename) {
    chip.querySelector('.chip-spinner')?.remove();
    chip.innerHTML =
        '<i class="fa-solid fa-circle-exclamation chip-icon" style="color:var(--bs-danger)"></i>' +
        '<span class="chip-label">' + escapeHtml(filename) + '</span>';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'chip-remove';
    btn.innerHTML = '<i class="fa-solid fa-xmark"></i>';
    btn.title = 'Dismiss';
    btn.addEventListener('click', function () { chip.remove(); syncStripVisibility(); });
    chip.appendChild(btn);
}

// ── File upload ───────────────────────────────────────────────────────────────

uploadFilesBtn.addEventListener('click', function (e) {
    e.preventDefault();
    fileInput.value = '';          // reset so the same file can be re-selected
    fileInput.click();
});

fileInput.addEventListener('change', function () {
    const files = Array.from(fileInput.files || []);
    files.forEach(uploadFile);
});

function uploadFile(file) {
    const tempId = 'tmp-' + Math.random().toString(36).slice(2);
    const chip   = createChip(tempId, file.name);

    const formData = new FormData();
    formData.append('file', file);

    fetch('/api/files', { method: 'POST', body: formData })
        .then(function (resp) {
            if (!resp.ok) { return resp.json().then(function (b) { throw new Error(b.message || resp.statusText); }); }
            return resp.json();
        })
        .then(function (data) {
            const attachment = {
                uuid:        data.uuid,
                name:        data.name,
                mimeType:    data.mimeType,
                url:         data.url,
                aiSupported: data.aiSupported,
                chipEl:      chip
            };
            stagedAttachments.push(attachment);
            finaliseChip(chip, attachment);
        })
        .catch(function (err) {
            console.error('File upload error:', err);
            markChipError(chip, file.name);
        });
}

// ── WebSocket / STOMP ────────────────────────────────────────────────────────

function connectWebSocket() {
    setStatus('connecting');
    stomp = new StompJs.Client({
        webSocketFactory: function () { return new SockJS('/ws'); },
        reconnectDelay: 5000,
        onConnect: function () {
            setStatus('connected');
            stomp.subscribe('/topic/chat/' + sessionUuid, function (frame) {
                const msg = JSON.parse(frame.body);
                showTyping(false);
                setInputEnabled(true);
                if (isUiEventFrame(msg)) {
                    handleIncomingUiFrame(msg);
                } else {
                    renderMessage(msg);
                }
            });
        },
        onDisconnect: function () { setStatus('disconnected'); },
        onStompError: function () { setStatus('disconnected'); }
    });
    stomp.activate();
}

// ── Session init ─────────────────────────────────────────────────────────────

function initSession() {
    fetch('/api/chat/session?provider=' + providerSel.value)
        .then(function (resp) {
            if (!resp.ok) { throw new Error('HTTP ' + resp.status + ' \u2014 ' + resp.statusText); }
            return resp.json();
        })
        .then(function (data) {
            sessionUuid = data.sessionUuid;
            sessionDisplay.textContent = sessionUuid.substring(0, 8) + '\u2026';
            (data.messages || []).forEach(renderSessionRecord);
            connectWebSocket();
            messageInput.focus();
        })
        .catch(function (err) {
            renderMessage({ role: 'ERROR', content: '**Failed to initialise session:** ' + err.message });
            setStatus('disconnected');
            setInputEnabled(false);
        });
}

// ── Form submit ──────────────────────────────────────────────────────────────

chatForm.addEventListener('submit', function (e) {
    e.preventDefault();
    const content = messageInput.value.trim();
    const hasAttachments = stagedAttachments.length > 0;

    if (!content && !hasAttachments) return;
    if (waiting || !stomp || !stomp.connected) return;

    // Block if any attachment is not AI-supported
    const unsupported = stagedAttachments.filter(function (a) { return !a.aiSupported; });
    if (unsupported.length > 0) {
        const names = unsupported.map(function (a) { return a.name; }).join(', ');
        renderMessage({
            role: 'ERROR',
            content: 'The following file(s) cannot be processed by the AI and must be removed before sending: **' + names + '**'
        });
        return;
    }

    // Render user message immediately (with attachments)
    const attachmentSnapshot = stagedAttachments.slice();
    renderMessage({
        role: 'USER',
        content: content,
        attachments: attachmentSnapshot.map(function (a) {
            return { uuid: a.uuid, name: a.name, mimeType: a.mimeType };
        })
    });

    const attachmentUuids = attachmentSnapshot.map(function (a) { return a.uuid; });

    // Clear input + staged attachments
    messageInput.value = '';
    messageInput.style.height = 'auto';
    stagedAttachments = [];
    attachStrip.innerHTML = '';
    syncStripVisibility();

    showTyping(true);
    setInputEnabled(false);

    stomp.publish({
        destination: '/app/chat.send',
        body: JSON.stringify({
            sessionUuid:     sessionUuid,
            content:         content,
            provider:        providerSel.value,
            attachmentUuids: attachmentUuids
        })
    });
});

// ── Splash ───────────────────────────────────────────────────────────────────

(function runSplash() {
    const splash     = document.getElementById('splash');
    const chatLayout = document.querySelector('.chat-layout');

    setTimeout(function () {
        chatLayout.classList.add('visible');
        splash.classList.add('fade-out');
        splash.addEventListener('transitionend', function () { splash.remove(); }, { once: true });
    }, 5000);
}());

// ── Boot ─────────────────────────────────────────────────────────────────────

initSession();
