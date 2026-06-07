// ── Pending Sessions page script ─────────────────────────────────────────────

'use strict';

(function () {

    var loadingEl      = document.getElementById('loading-state');
    var emptyEl        = document.getElementById('empty-state');
    var tableWrapper   = document.getElementById('sessions-table-wrapper');
    var tbody          = document.getElementById('sessions-tbody');
    var modalEl        = document.getElementById('input-modal');
    var modalLabel     = document.getElementById('input-modal-label');
    var modalBody      = document.getElementById('input-modal-body');
    var modalFooter    = document.getElementById('input-modal-footer');

    var bsModal   = new bootstrap.Modal(modalEl);
    var sessions  = [];   // cached list from the API

    // ── Load ──────────────────────────────────────────────────────────────────

    function load() {
        fetch('/api/chat/sessions/pending-input')
            .then(function (r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
            .then(function (data) {
                loadingEl.classList.add('d-none');
                sessions = data || [];
                if (sessions.length === 0) {
                    emptyEl.classList.remove('d-none');
                    return;
                }
                renderTable(sessions);
                tableWrapper.classList.remove('d-none');
            })
            .catch(function (err) {
                loadingEl.classList.add('d-none');
                tableWrapper.innerHTML =
                    '<div class="alert alert-danger">Failed to load pending sessions: ' + escapeHtml(String(err)) + '</div>';
                tableWrapper.classList.remove('d-none');
            });
    }

    // ── Table ─────────────────────────────────────────────────────────────────

    function renderTable(list) {
        tbody.innerHTML = '';
        list.forEach(function (session) {
            tbody.appendChild(buildRow(session));
        });
    }

    function buildRow(session) {
        var tr = document.createElement('tr');
        tr.id = 'row-' + session.sessionUuid;

        // Session name
        var tdName = document.createElement('td');
        tdName.className = 'fw-semibold';
        tdName.textContent = session.sessionName || 'Untitled';
        tdName.title = session.sessionUuid;
        tr.appendChild(tdName);

        // Origin badge
        var tdOrigin = document.createElement('td');
        var badge = document.createElement('span');
        badge.className = 'origin-badge origin-' + (session.originMode || '');
        badge.textContent = session.originMode === 'TELEGRAM' ? '\u2708 Telegram' : '\u25CE Background';
        tdOrigin.appendChild(badge);
        tr.appendChild(tdOrigin);

        // Tool name
        var tdTool = document.createElement('td');
        tdTool.className = 'pending-tool-name';
        tdTool.textContent = session.toolName || '\u2014';
        tr.appendChild(tdTool);

        // Waiting since
        var tdAge = document.createElement('td');
        tdAge.className = 'text-muted small';
        tdAge.textContent = formatAge(session.createdAt);
        tr.appendChild(tdAge);

        // Action
        var tdAction = document.createElement('td');
        tdAction.className = 'text-end';
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm btn-primary';
        btn.innerHTML = '<i class="fa-solid fa-keyboard me-1"></i>Provide Input';
        btn.addEventListener('click', function () { openModal(session, tr); });
        tdAction.appendChild(btn);
        tr.appendChild(tdAction);

        return tr;
    }

    // ── Modal ─────────────────────────────────────────────────────────────────

    function openModal(session, tr) {
        // Title
        modalLabel.textContent = (session.sessionName || 'Session') +
            (session.toolName ? ' \u2014 ' + session.toolName : '');

        // Body
        modalBody.innerHTML = '';

        if (session.reasoning && session.reasoning.trim()) {
            var reasoningEl = document.createElement('div');
            reasoningEl.className = 'modal-reasoning';
            reasoningEl.textContent = session.reasoning;
            modalBody.appendChild(reasoningEl);
        }

        var fields = session.formSchema && Array.isArray(session.formSchema.fields)
            ? session.formSchema.fields.filter(function (f) {
                return f && f.name && (f.type || '').toLowerCase() !== 'hidden';
            })
            : [];

        var fieldContainer = document.createElement('div');
        fieldContainer.className = 'vstack gap-3';
        fields.forEach(function (f) {
            fieldContainer.appendChild(buildField(f, session.sessionUuid));
        });
        modalBody.appendChild(fieldContainer);

        // Footer: error span + cancel + action buttons
        modalFooter.innerHTML = '';

        var errorSpan = document.createElement('span');
        errorSpan.className = 'modal-error d-none';
        modalFooter.appendChild(errorSpan);

        var cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'btn btn-secondary';
        cancelBtn.setAttribute('data-bs-dismiss', 'modal');
        cancelBtn.textContent = 'Cancel';
        modalFooter.appendChild(cancelBtn);

        var actions = session.formSchema && Array.isArray(session.formSchema.actions)
            ? session.formSchema.actions
            : [{ name: 'ONCE', label: 'Approve', style: 'success' }];

        actions.forEach(function (action) {
            var btn = document.createElement('button');
            btn.type = 'button';
            var style = (action.style || '').toLowerCase();
            btn.className = 'btn btn-' + (style || 'primary');
            btn.textContent = action.label || action.name;
            btn.addEventListener('click', function () {
                submitSession(session, action.name, collectFields(fields), tr, errorSpan);
            });
            modalFooter.appendChild(btn);
        });

        bsModal.show();
    }

    // ── Field builder ─────────────────────────────────────────────────────────

    function buildField(field, sessionUuid) {
        var wrapper = document.createElement('div');
        var inputId = 'modal-field-' + sessionUuid + '-' + field.name;

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
        } else if (type === 'textarea') {
            var ta = document.createElement('textarea');
            ta.className = 'form-control form-control-sm';
            ta.id = inputId;
            ta.dataset.fieldName = field.name;
            ta.rows = 4;
            if (field.placeholder) ta.placeholder = field.placeholder;
            if (field.required) ta.required = true;
            wrapper.appendChild(ta);
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

    // ── Field collection ──────────────────────────────────────────────────────

    function collectFields(fields) {
        var values = {};
        fields.forEach(function (f) {
            var el = modalBody.querySelector('[data-field-name="' + f.name + '"]');
            if (el) values[f.name] = el.value || '';
        });
        return values;
    }

    // ── Submission ────────────────────────────────────────────────────────────

    function submitSession(session, action, fields, tr, errorSpan) {
        errorSpan.classList.add('d-none');
        var actionBtns = modalFooter.querySelectorAll('button:not([data-bs-dismiss])');
        actionBtns.forEach(function (b) { b.disabled = true; });

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
                if (data.status === 'BACKGROUND_RESUMED' || data.status === 'WEB_RESUMED') {
                    bsModal.hide();
                    if (tr && tr.parentNode) tr.parentNode.removeChild(tr);
                    if (tbody.querySelectorAll('tr').length === 0) {
                        tableWrapper.classList.add('d-none');
                        emptyEl.classList.remove('d-none');
                    }
                } else if (data.status === 'AWAITING_INPUT') {
                    bsModal.hide();
                    setTimeout(function () { window.location.reload(); }, 400);
                } else {
                    errorSpan.textContent = data.message || data.status || 'Unexpected response';
                    errorSpan.classList.remove('d-none');
                    actionBtns.forEach(function (b) { b.disabled = false; });
                }
            })
            .catch(function (err) {
                errorSpan.textContent = 'Request failed: ' + escapeHtml(String(err));
                errorSpan.classList.remove('d-none');
                actionBtns.forEach(function (b) { b.disabled = false; });
            });
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    function formatAge(epochMs) {
        if (!epochMs) return '';
        var diff = Date.now() - epochMs;
        var mins = Math.floor(diff / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return mins + 'm ago';
        var hrs = Math.floor(mins / 60);
        if (hrs < 24) return hrs + 'h ago';
        return Math.floor(hrs / 24) + 'd ago';
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    load();

}());
