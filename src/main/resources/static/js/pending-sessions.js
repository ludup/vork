// ── Pending Sessions page script ─────────────────────────────────────────────

'use strict';

(function () {

    var loadingEl   = document.getElementById('loading-state');
    var emptyEl     = document.getElementById('empty-state');
    var containerEl = document.getElementById('sessions-container');

    // ── Bootstrap ──────────────────────────────────────────────────────────────

    function load() {
        fetch('/api/chat/sessions/pending-input')
            .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
            .then(function (sessions) {
                loadingEl.classList.add('d-none');
                if (!sessions || sessions.length === 0) {
                    emptyEl.classList.remove('d-none');
                    return;
                }
                sessions.forEach(function (s) { containerEl.appendChild(buildCard(s)); });
            })
            .catch(function (err) {
                loadingEl.classList.add('d-none');
                containerEl.innerHTML =
                    '<div class="alert alert-danger">Failed to load pending sessions: ' + escapeHtml(String(err)) + '</div>';
            });
    }

    // ── Card builder ───────────────────────────────────────────────────────────

    function buildCard(session) {
        var card = document.createElement('div');
        card.className = 'session-card';
        card.id = 'card-' + session.sessionUuid;

        // ── Header ──────────────────────────────────────────────────────────────
        var header = document.createElement('div');
        header.className = 'session-card-header';

        var left = document.createElement('div');
        left.className = 'session-card-header-left';

        var badge = document.createElement('span');
        badge.className = 'session-origin-badge origin-' + (session.originMode || '');
        badge.textContent = session.originMode === 'TELEGRAM'
            ? '\u2708 Telegram'
            : '\u25CE Background';

        var name = document.createElement('span');
        name.className = 'session-name';
        name.title = session.sessionUuid;
        name.textContent = session.sessionName || 'Untitled';

        var tool = document.createElement('span');
        tool.className = 'session-tool-name';
        tool.textContent = session.toolName || '';

        left.appendChild(badge);
        left.appendChild(name);
        left.appendChild(tool);
        header.appendChild(left);
        card.appendChild(header);

        // ── Body ────────────────────────────────────────────────────────────────
        var body = document.createElement('div');
        body.className = 'session-card-body';

        if (session.reasoning && session.reasoning.trim()) {
            var reasoning = document.createElement('div');
            reasoning.className = 'reasoning-block';
            reasoning.textContent = session.reasoning;
            body.appendChild(reasoning);
        }

        var fields = session.formSchema && Array.isArray(session.formSchema.fields)
            ? session.formSchema.fields.filter(function (f) {
                return f && f.name && f.type !== 'hidden' && f.type !== 'HIDDEN';
            })
            : [];

        if (fields.length > 0) {
            var fieldContainer = document.createElement('div');
            fieldContainer.className = 'session-form-fields';
            fields.forEach(function (f) {
                fieldContainer.appendChild(buildField(f, session.sessionUuid));
            });
            body.appendChild(fieldContainer);
        }

        card.appendChild(body);

        // ── Actions ─────────────────────────────────────────────────────────────
        var actions = session.formSchema && Array.isArray(session.formSchema.actions)
            ? session.formSchema.actions
            : [{ name: 'ONCE', label: 'Approve', style: 'success' }];

        var actionsRow = document.createElement('div');
        actionsRow.className = 'session-card-actions';

        actions.forEach(function (action) {
            var btn = document.createElement('button');
            btn.type = 'button';
            var style = (action.style || '').toLowerCase();
            btn.className = 'btn btn-sm btn-' + (style || 'primary');
            btn.textContent = action.label || action.name;
            btn.addEventListener('click', function () {
                submitSession(session, action.name, collectFields(session.sessionUuid, fields), card);
            });
            actionsRow.appendChild(btn);
        });

        card.appendChild(actionsRow);

        // ── Result area ─────────────────────────────────────────────────────────
        var result = document.createElement('div');
        result.className = 'session-result';
        result.id = 'result-' + session.sessionUuid;
        card.appendChild(result);

        return card;
    }

    // ── Field builder ──────────────────────────────────────────────────────────

    function buildField(field, sessionUuid) {
        var wrapper = document.createElement('div');
        var inputId = 'field-' + sessionUuid + '-' + field.name;

        var label = document.createElement('label');
        label.className = 'form-label mb-1';
        label.htmlFor = inputId;
        label.textContent = field.label || field.name;
        wrapper.appendChild(label);

        var type = (field.type || 'text').toLowerCase();

        if (type === 'select' && Array.isArray(field.options)) {
            var sel = document.createElement('select');
            sel.className = 'form-select form-select-sm';
            sel.id = inputId;
            sel.dataset.fieldName = field.name;
            if (field.required) sel.required = true;
            field.options.forEach(function (opt) {
                var option = document.createElement('option');
                option.value = opt.value;
                option.textContent = opt.label;
                sel.appendChild(option);
            });
            wrapper.appendChild(sel);
        } else {
            var input = document.createElement('input');
            input.className = 'form-control form-control-sm';
            input.type = type === 'password' ? 'password' : 'text';
            input.id = inputId;
            input.dataset.fieldName = field.name;
            if (field.placeholder) input.placeholder = field.placeholder;
            if (field.defaultValue) input.value = field.defaultValue;
            if (field.required) input.required = true;
            wrapper.appendChild(input);
        }

        return wrapper;
    }

    // ── Form collection ────────────────────────────────────────────────────────

    function collectFields(sessionUuid, fields) {
        var values = {};
        fields.forEach(function (f) {
            var el = document.querySelector('[data-field-name="' + f.name + '"]');
            if (el) {
                values[f.name] = el.value || '';
            }
        });
        return values;
    }

    // ── Submission ─────────────────────────────────────────────────────────────

    function submitSession(session, action, fields, card) {
        var buttons = card.querySelectorAll('.session-card-actions button');
        buttons.forEach(function (b) { b.disabled = true; });

        var resultEl = document.getElementById('result-' + session.sessionUuid);

        fetch('/api/chat/respond/' + session.sessionUuid, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                eventId: session.eventId,
                intent: 'AUTHORIZE_TOOL',
                action: action,
                fields: fields
            })
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                resultEl.style.display = 'block';
                if (data.status === 'BACKGROUND_RESUMED') {
                    resultEl.className = 'session-result success';
                    resultEl.innerHTML =
                        '<i class="fa-solid fa-circle-check me-2"></i>Input received — background task is resuming.';
                } else if (data.status === 'WEB_RESUMED') {
                    resultEl.className = 'session-result success';
                    resultEl.innerHTML =
                        '<i class="fa-solid fa-circle-check me-2"></i>'
                        + (action === 'DENIED' || action === 'DENY'
                            ? 'Denied — the session will continue.'
                            : 'Input submitted — the session is continuing.');
                } else if (data.status === 'AWAITING_INPUT') {
                    // Another prompt immediately required — reload the page so the new prompt appears
                    resultEl.className = 'session-result success';
                    resultEl.innerHTML =
                        '<i class="fa-solid fa-circle-info me-2"></i>More input required — reloading…';
                    setTimeout(function () { window.location.reload(); }, 1200);
                } else {
                    resultEl.className = 'session-result error';
                    resultEl.innerHTML =
                        '<i class="fa-solid fa-triangle-exclamation me-2"></i>'
                        + escapeHtml(data.message || data.status || 'Unexpected response');
                    buttons.forEach(function (b) { b.disabled = false; });
                }
            })
            .catch(function (err) {
                resultEl.style.display = 'block';
                resultEl.className = 'session-result error';
                resultEl.innerHTML =
                    '<i class="fa-solid fa-triangle-exclamation me-2"></i>'
                    + 'Request failed: ' + escapeHtml(String(err));
                buttons.forEach(function (b) { b.disabled = false; });
            });
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ── Entry point ────────────────────────────────────────────────────────────

    load();

}());
