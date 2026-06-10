/**
 * VorkModal — lightweight Bootstrap-compatible modal without Bootstrap JS.
 *
 * API mirrors bootstrap.Modal:
 *   const m = new VorkModal(document.getElementById('myModal'));
 *   m.show();
 *   m.hide();
 *   element.addEventListener('hidden.bs.modal', handler);
 *
 * Also handles:
 *   data-bs-dismiss="modal"  — inside modal or alert elements
 *   data-bs-dismiss="alert"  — dismisses the closest .alert ancestor
 */

class VorkModal {
    constructor(element) {
        this._el = element;
        this._backdrop = null;
        this._onKeyDown = (e) => { if (e.key === 'Escape') this.hide(); };

        // Delegate clicks on dismiss triggers and backdrop
        this._el.addEventListener('click', (e) => {
            if (e.target === this._el) {
                // Click on the outer modal backdrop overlay
                this.hide();
            } else if (e.target.closest('[data-bs-dismiss="modal"]')) {
                this.hide();
            }
        });
    }

    show() {
        if (this._backdrop) return; // already open
        this._backdrop = document.createElement('div');
        this._backdrop.className = 'modal-backdrop';
        document.body.appendChild(this._backdrop);
        this._el.classList.add('show');
        document.body.style.overflow = 'hidden';
        document.addEventListener('keydown', this._onKeyDown);
    }

    hide() {
        this._el.classList.remove('show');
        if (this._backdrop) {
            this._backdrop.remove();
            this._backdrop = null;
        }
        document.body.style.overflow = '';
        document.removeEventListener('keydown', this._onKeyDown);
        this._el.dispatchEvent(new Event('hidden.bs.modal'));
    }
}

// ── Global alert dismiss (data-bs-dismiss="alert") ────────────────────────
document.addEventListener('click', function (e) {
    const trigger = e.target.closest('[data-bs-dismiss="alert"]');
    if (!trigger) return;
    const alert = trigger.closest('.alert');
    if (alert) alert.remove();
});
