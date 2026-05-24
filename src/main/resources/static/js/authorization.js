// Authorization Page Script

document.addEventListener('DOMContentLoaded', function() {
    // Extract token from URL query parameter
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    
    if (!token) {
        showError('Invalid authorization URL: missing token parameter');
        document.getElementById('authForm').style.display = 'none';
        return;
    }
    
    document.getElementById('authToken').value = token;
    
    // Fetch authorization request details
    fetchAuthorizationDetails(token);
    
    // Handle form submission
    document.getElementById('authForm').addEventListener('submit', function(e) {
        e.preventDefault();
        submitAuthorization();
    });
    
    // Handle deny button
    document.getElementById('denyBtn').addEventListener('click', function() {
        denyAuthorization();
    });
});

/**
 * Fetch the authorization request details from the server
 */
function fetchAuthorizationDetails(token) {
    fetch(`/api/authorization/details?token=${encodeURIComponent(token)}`, {
        method: 'GET',
        headers: {
            'Accept': 'application/json'
        },
        credentials: 'include'
    })
    .then(response => {
        if (response.status === 404) {
            throw new Error('Authorization token not found or expired');
        }
        if (!response.ok) {
            throw new Error('Failed to fetch authorization details');
        }
        return response.json();
    })
    .then(data => {
        displayActionDetails(data);
    })
    .catch(error => {
        console.error('Error fetching details:', error);
        showError(error.message || 'Could not load authorization details');
    });
}

/**
 * Display action details in the authorization card
 */
function displayActionDetails(data) {
    const detailsDiv = document.getElementById('actionDetails');
    
    let html = `
        <h5><i class="fa-solid fa-tasks me-2"></i>Action Details</h5>
    `;
    
    if (data.toolName) {
        html += `<p><strong>Tool:</strong> ${escapeHtml(data.toolName)}</p>`;
    }
    
    if (data.description) {
        html += `<p>${escapeHtml(data.description)}</p>`;
    }
    
    if (data.arguments) {
        html += `<p><strong>Parameters:</strong> ${escapeHtml(data.arguments)}</p>`;
    }
    
    if (data.createdAt) {
        const date = new Date(data.createdAt);
        html += `<p><strong>Requested:</strong> ${date.toLocaleString()}</p>`;
    }
    
    detailsDiv.innerHTML = html;
}

/**
 * Submit authorization decision as the currently authenticated user
 */
function submitAuthorization() {
    const token = document.getElementById('authToken').value;
    
    const submitBtn = document.querySelector('button[type="submit"]');
    const originalText = submitBtn.innerHTML;
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin me-2"></i>Verifying...';
    
    fetch('/api/authorization/approve', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
            token: token
        })
    })
    .then(response => {
        if (response.status === 401) {
            redirectToLogin();
            throw new Error('Authentication required');
        }
        if (response.status === 404) {
            throw new Error('Authorization token not found or expired');
        }
        if (!response.ok) {
            return response.json().then(data => {
                throw new Error(data.message || 'Authorization failed');
            });
        }
        return response.json();
    })
    .then(data => {
        // Success - close this window or redirect
        if (data.redirectUrl) {
            window.location.href = data.redirectUrl;
        } else {
            alert('Authorization approved! You can close this window.');
            window.close();
        }
    })
    .catch(error => {
        console.error('Authorization error:', error);
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalText;
        showError(error.message || 'Authorization failed. Please try again.');
    });
}

/**
 * Deny authorization
 */
function denyAuthorization() {
    const token = document.getElementById('authToken').value;
    const denyBtn = document.getElementById('denyBtn');
    
    const originalText = denyBtn.innerHTML;
    denyBtn.disabled = true;
    denyBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin me-2"></i>Denying...';
    
    fetch('/api/authorization/deny', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify({
            token: token
        })
    })
    .then(response => {
        if (response.status === 401) {
            redirectToLogin();
            throw new Error('Authentication required');
        }
        if (!response.ok) {
            throw new Error('Failed to deny authorization');
        }
        return response.json();
    })
    .then(data => {
        alert('Authorization denied. You can close this window.');
        window.close();
    })
    .catch(error => {
        console.error('Deny error:', error);
        denyBtn.disabled = false;
        denyBtn.innerHTML = originalText;
        showError('Failed to deny authorization. Please try again.');
    });
}

/**
 * Show error message
 */
function showError(message) {
    const errorAlert = document.getElementById('errorAlert');
    const errorMessage = document.getElementById('errorMessage');
    
    errorMessage.textContent = message;
    errorAlert.classList.remove('d-none');
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text) {
    if (!text) return '';
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, m => map[m]);
}

function redirectToLogin() {
    window.location.href = window.location.pathname + window.location.search;
}
