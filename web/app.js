/* Smart Banking - single page front end (plain JavaScript, no build step).
 * Talks to the JSON API in ApiServer.java. Every value shown from the server is HTML-escaped. */
(() => {
  'use strict';

  const TOKEN_KEY = 'smartbank.token';
  const TABS = {
    CUSTOMER: [
      { id: 'overview', label: 'Overview' },
      { id: 'transfer', label: 'Transfer' },
      { id: 'loans', label: 'Loans' },
      { id: 'fd', label: 'Fixed deposits' },
      { id: 'profile', label: 'Profile & KYC' },
    ],
    CASHIER: [
      { id: 'desk', label: 'Customer desk' },
      { id: 'open', label: 'Open account' },
    ],
    ADMIN: [
      { id: 'dashboard', label: 'Dashboard' },
      { id: 'customers', label: 'Customers & KYC' },
      { id: 'loanApprovals', label: 'Loan approvals' },
      { id: 'users', label: 'Users' },
      { id: 'audit', label: 'Audit log' },
    ],
  };

  const state = { token: null, user: null, tab: null, desk: null, deskResult: null, onboarded: null, auditLimit: 100, renderId: 0 };

  // ---------- helpers ----------
  const app = () => document.getElementById('app');
  const $ = (sel, root = document) => root.querySelector(sel);

  function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, ch => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }
  const money = v => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(Number(v || 0));
  function parseDate(v) {
    if (!v) return null;
    const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(v));
    if (dateOnly) return new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]));
    const serverDate = /^(\d{1,2}) ([A-Za-z]{3}) (\d{4}), (\d{2}):(\d{2})(?::(\d{2}))?$/.exec(String(v));
    if (serverDate) {
      const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      const month = months.findIndex(m => m.toLowerCase() === serverDate[2].toLowerCase());
      if (month >= 0) return new Date(Number(serverDate[3]), month, Number(serverDate[1]), Number(serverDate[4]), Number(serverDate[5]), Number(serverDate[6] || 0));
    }
    const parsed = new Date(v);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }
  const when = v => {
    if (!v) return '—';
    const d = parseDate(v);
    return d ? d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : String(v);
  };
  const day = v => {
    if (!v) return '—';
    const d = parseDate(v);
    return d ? d.toLocaleDateString('en-IN', { dateStyle: 'medium' }) : String(v);
  };
  const shortRef = r => `<span class="ref" title="${esc(r)}">${esc(String(r ?? '').slice(0, 8))}</span>`;
  const pill = s => `<span class="pill ${esc(s)}">${esc(String(s ?? '').replace(/_/g, ' '))}</span>`;
  const empty = text => `<p class="empty muted">${esc(text)}</p>`;
  const cardHead = (title, extra = '') => `<div class="row between"><h3>${esc(title)}</h3>${extra}</div>`;

  function table(headers, rows, emptyText = 'No records found.') {
    if (!rows.length) return empty(emptyText);
    return `<div class="table-wrap"><table>
      <thead><tr>${headers.map(h => `<th>${esc(h)}</th>`).join('')}</tr></thead>
      <tbody>${rows.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody>
    </table></div>`;
  }

  function toast(message, isError = false) {
    const el = document.createElement('div');
    el.className = 'toast' + (isError ? ' err' : '');
    el.textContent = message;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), 4500);
  }

  // ---------- session and API ----------
  function saveToken(t) {
    state.token = t;
    try { localStorage.setItem(TOKEN_KEY, t); } catch (_) { /* private mode: keep it in memory only */ }
  }
  function forgetSession() {
    state.token = null;
    state.user = null;
    try { localStorage.removeItem(TOKEN_KEY); } catch (_) { /* ignore */ }
  }
  function loadToken() {
    try { return localStorage.getItem(TOKEN_KEY); } catch (_) { return null; }
  }

  async function api(method, path, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (state.token) headers.Authorization = 'Bearer ' + state.token;
    const res = await fetch(path, {
      method, headers, cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let data = {};
    try { data = await res.json(); } catch (_) { /* empty body */ }
    if (res.status === 401 && state.token) {
      forgetSession();
      showLogin('Your session has ended. Please log in again.');
      throw new Error('Session ended');
    }
    if (!res.ok) throw new Error(data.error || ('Request failed (' + res.status + ')'));
    return data;
  }

  // ---------- screens ----------
  function showLogin(notice = '') {
    app().innerHTML = `
      <div class="login-wrap">
        <div class="login-layout">
          <aside class="login-aside">
            <div class="brand"><span class="brand-mark">SB</span><strong>Smart Banking</strong></div>
            <div class="login-message">
              <span class="eyebrow">SMART BANKING</span>
              <h1>Your banking,<br>made simple.</h1>
              <p>View your accounts, send money and manage your finances in one place.</p>
            </div>
            <div class="login-footnote"><span class="status-dot"></span> Secure account access</div>
          </aside>
          <div class="login-card card">
          <div class="login-heading"><span class="eyebrow">WELCOME BACK</span><h2>Log in to your account</h2><p class="muted small">Enter your account details to continue.</p></div>
          <form class="stack" data-action="login">
            <label>Username, mobile number or email
              <input name="identifier" required autocomplete="username" autofocus></label>
            <label>Password
              <input name="password" type="password" required autocomplete="current-password"></label>
            ${notice ? `<p class="error">${esc(notice)}</p>` : ''}
            <button class="btn primary block" type="submit">Log in</button>
          </form>
          </div>
        </div>
      </div>`;
  }

  function renderShell() {
    const u = state.user;
    const tabs = TABS[u.role] || [];
    if (!tabs.some(t => t.id === state.tab)) state.tab = tabs[0]?.id ?? null;
    app().innerHTML = `
      <header class="topbar">
        <div class="brand"><span class="brand-mark sm">SB</span><strong>Smart Banking</strong></div>
        <div class="who">
          <span>${esc(u.name)}</span>
          <span class="pill">${esc(u.roleLabel)}</span>
          <button class="btn ghost small-btn" id="logout" type="button">Log out</button>
        </div>
      </header>
      <nav class="tabs" aria-label="Main navigation">
        ${tabs.map(t => `<button type="button" class="tab ${t.id === state.tab ? 'active' : ''}" aria-current="${t.id === state.tab ? 'page' : 'false'}" data-go="${t.id}">${esc(t.label)}</button>`).join('')}
      </nav>
      <main id="main" class="container"></main>`;
  }

  const VIEWS = {
    overview: viewCustomerOverview, transfer: viewTransfer, loans: viewLoans, fd: viewFixedDeposits, profile: viewProfile,
    desk: viewDesk, open: viewOpenAccount,
    dashboard: viewDashboard, customers: viewCustomers, loanApprovals: viewLoanApprovals,
    users: viewUsers, audit: viewAudit,
  };

  async function renderMain() {
    const main = $('#main');
    if (!main || !state.tab) return;
    const renderId = ++state.renderId;
    const tab = state.tab;
    main.innerHTML = '<p class="muted center pad">Loading…</p>';
    try {
      const content = await VIEWS[tab]();
      if (renderId === state.renderId && state.tab === tab && main.isConnected) main.innerHTML = content;
    } catch (err) {
      if (renderId === state.renderId && state.tab === tab && main.isConnected && err.message !== 'Session ended') {
        main.innerHTML = `<div class="card"><p class="error">${esc(err.message)}</p></div>`;
      }
    }
  }

  function go(tab) {
    state.tab = tab;
    renderShell();
    renderMain();
  }

  function enter() {
    state.tab = null;
    renderShell();
    renderMain();
  }

  // ---------- customer views ----------
  const kycNotice = p => (p.kycStatus === 'VERIFIED' ? '' : `
    <div class="notice">
      KYC status: <strong>${esc(p.kycStatus)}</strong>. Loan applications require verified KYC. Your daily transfer limit is
      Rs 50,000 until the bank verifies your documents. Visit your branch with PAN and Aadhaar if this is still pending.
    </div>`);

  async function viewCustomerOverview() {
    const d = await api('GET', '/api/customer/overview');
    const p = d.profile;
    const total = d.accounts.reduce((sum, a) => sum + Number(a.balance), 0);
    const primary = d.accounts[0];
    const txRows = d.transactions.slice(0, 15).map(t => [
      esc(day(t.date)), shortRef(t.reference), esc(t.narration),
      `<span class="${t.direction === 'CREDIT' ? 'amt-in' : 'amt-out'}">${t.direction === 'CREDIT' ? '+' : '−'} ${money(t.amount)}</span>`,
      esc(money(t.balanceAfter)),
    ]);
    return `
      ${kycNotice(p)}
      <section class="hero">
        <div class="small">Total balance</div>
        <div class="balance">${money(total)}</div>
        <div class="small">CIF ${esc(p.cifNo)}${primary ? ' · Account ' + esc(primary.accountNo) : ''} · KYC ${pill(p.kycStatus)}</div>
      </section>
      <section class="actions">
        <button type="button" class="action" data-go="transfer"><span class="ico">⇄</span><strong>Transfer</strong><span class="small">Send money to an account</span></button>
        <button type="button" class="action" data-go="loans"><span class="ico">₹</span><strong>Apply for loan</strong><span class="small">Personal, home or car</span></button>
        <button type="button" class="action" data-go="fd"><span class="ico">%</span><strong>Fixed deposit</strong><span class="small">Earn a fixed return</span></button>
        <button type="button" class="action" data-go="profile"><span class="ico">✓</span><strong>My profile</strong><span class="small">KYC and address</span></button>
      </section>
      <div class="grid-2">
        <div class="card">
          ${cardHead('Accounts')}
          ${table(['Account no.', 'Type', 'Balance', 'Status'],
            d.accounts.map(a => [`<code>${esc(a.accountNo)}</code>`, esc(a.type), esc(money(a.balance)), pill(a.status)]),
            'No accounts found.')}
        </div>
        <div class="card">
          ${cardHead('Recent transactions')}
          ${table(['Date', 'Reference', 'Narration', 'Amount', 'Balance'], txRows, 'No transactions to show.')}
        </div>
      </div>`;
  }

  async function viewTransfer() {
    const d = await api('GET', '/api/customer/overview');
    const p = d.profile;
    const limit = p.kycStatus === 'VERIFIED' ? 'Rs 2,00,000' : 'Rs 50,000';
    const from = d.accounts[0];
    return `
      ${kycNotice(p)}
      <div class="card narrow">
        ${cardHead('Transfer money')}
        <p class="muted small">From ${esc(from ? from.accountNo : '—')} · Daily outgoing limit ${limit} · Per transaction up to Rs 10,00,000</p>
        <p class="muted small">Your UPI ID: <strong>${esc(from ? `${from.accountNo}@smartbank` : 'unavailable')}</strong></p>
        <p class="muted small">Pay using a UPI ID, account number or registered mobile number.</p>
        <form class="stack" data-action="upi">
          <label>Pay to
            <input id="upiId" name="upiId" maxlength="60" required placeholder="UPI ID, 12-digit account or mobile number"></label>
          <p id="recipient-name" class="small muted" aria-live="polite">Enter recipient details to see the account name.</p>
          <label>Amount (Rs)
            <input name="amount" type="number" min="1" step="0.01" required></label>
          <label>Note (optional)
            <input name="note" maxlength="60" placeholder="e.g. rent share"></label>
          <button class="btn primary" type="submit">Send money</button>
        </form>
      </div>`;
  }

  async function viewLoans() {
    const d = await api('GET', '/api/customer/overview');
    const p = d.profile;
    const verified = p.kycStatus === 'VERIFIED';
    return `
      ${kycNotice(p)}
      <div class="grid-2">
        <div class="card">
          ${cardHead('Apply for a loan')}
          <form class="stack" data-action="loan">
            <label>Loan type
              <select name="type">
                <option value="PERSONAL">Personal loan (12.50% p.a.)</option>
                <option value="HOME">Home loan (8.50% p.a.)</option>
                <option value="CAR">Car loan (9.50% p.a.)</option>
              </select></label>
            <label>Amount (Rs)
              <input name="amount" type="number" min="1000" max="5000000" step="1" required></label>
            <label>Tenure (months, 6 to 360)
              <input name="tenureMonths" type="number" min="6" max="360" step="1" required></label>
            <button class="btn primary" type="submit" ${verified ? '' : 'disabled'}>Apply</button>
            ${verified ? '' : '<p class="small muted">Complete KYC verification to apply.</p>'}
          </form>
        </div>
        <div class="card">
          ${cardHead('My loans')}
          ${table(['Type', 'Amount', 'Tenure', 'EMI', 'Status', 'Repayment'],
            d.loans.map(l => [esc(l.type), esc(money(l.amount)), `${esc(l.tenureMonths)} m`, esc(money(l.emi)), pill(l.status),
              l.status === 'APPROVED' && Number(l.outstanding) > 0
                ? `<span class="small">Due ${esc(money(l.outstanding))}</span> <button class="btn small-btn primary" data-act="repay" data-id="${esc(l.id)}">Pay EMI</button>` : '—']),
            'No loans found.')}
          <p class="small muted">Each payment covers one EMI.</p>
        </div>
      </div>`;
  }

  async function viewFixedDeposits() {
    const d = await api('GET', '/api/customer/overview');
    return `
      <div class="grid-2">
        <div class="card">
          ${cardHead('Open a fixed deposit')}
          <form class="stack" data-action="fd">
            <label>Amount (Rs, minimum 1,000)
              <input name="amount" type="number" min="1000" step="1" required></label>
            <label>Tenure (months, 1 to 120)
              <input name="tenureMonths" type="number" min="1" max="120" step="1" required></label>
            <button class="btn primary" type="submit">Open deposit</button>
          </form>
          <p class="small muted">Rates: up to 12 months 6.50%, up to 36 months 7.00%, above 36 months 7.50% p.a.</p>
        </div>
        <div class="card">
          ${cardHead('My fixed deposits')}
          ${table(['Principal', 'Rate', 'Tenure', 'Maturity amount', 'Matures on', 'Status'],
            d.fixedDeposits.map(f => [esc(money(f.principal)), `${esc(f.annualRate)}%`, `${esc(f.tenureMonths)} m`,
              esc(money(f.maturityAmount)), esc(day(f.maturityDate)), pill(f.status)]),
            'No fixed deposits found.')}
        </div>
      </div>`;
  }

  async function viewProfile() {
    const d = await api('GET', '/api/customer/overview');
    const p = d.profile;
    const a = p.address;
    return `
      ${kycNotice(p)}
      <div class="grid-2">
        <div class="card">
          ${cardHead('Personal details')}
          <dl class="kv">
            <dt>Name</dt><dd>${esc(p.name)}</dd>
            <dt>CIF number</dt><dd>${esc(p.cifNo)}</dd>
            <dt>Date of birth</dt><dd>${esc(day(p.dateOfBirth))}</dd>
            <dt>Gender</dt><dd>${esc(p.gender)}</dd>
            <dt>Mobile</dt><dd>${esc(p.mobile)}</dd>
            <dt>Email</dt><dd>${esc(p.email)}</dd>
          </dl>
        </div>
        <div class="card">
          ${cardHead('KYC and address')}
          <dl class="kv">
            <dt>KYC status</dt><dd>${pill(p.kycStatus)}</dd>
            <dt>PAN</dt><dd><code>${esc(p.panMasked)}</code></dd>
            <dt>Aadhaar</dt><dd><code>${esc(p.aadhaarMasked)}</code></dd>
            <dt>Permanent address</dt><dd>${a ? esc([a.line1, a.line2, a.city, a.state, a.pincode].filter(Boolean).join(', ')) : '—'}</dd>
          </dl>
          <p class="small muted">For security, only the masked PAN and last 4 digits of Aadhaar are shown.</p>
        </div>
      </div>`;
  }

  // ---------- cashier views ----------
  async function viewDesk() {
    let result = null;
    if (state.desk) {
      result = await api('GET', '/api/staff/lookup?q=' + encodeURIComponent(state.desk));
    }
    const search = `
      <div class="card">
        ${cardHead('Find a customer')}
        <form class="row" data-action="lookup">
          <input name="q" required placeholder="CIF number, mobile number or account number" value="${esc(state.desk || '')}">
          <button class="btn primary" type="submit">Search</button>
        </form>
      </div>`;
    if (!result) return search;

    const c = result.customer;
    const activeAccounts = result.accounts.filter(a => a.status === 'ACTIVE');
    const accountOptions = result.accounts.map(a =>
      `<option value="${esc(a.accountNo)}" ${a.status !== 'ACTIVE' ? 'disabled' : ''}>${esc(a.accountNo)} · ${esc(a.type)} · ${esc(money(a.balance))}${a.status !== 'ACTIVE' ? ` · ${esc(a.status)}` : ''}</option>`).join('');
    return `
      ${search}
      <div class="grid-2">
        <div class="card">
          ${cardHead(c.name, pill(c.kycStatus))}
          <dl class="kv">
            <dt>CIF</dt><dd>${esc(c.cifNo)}</dd>
            <dt>Mobile</dt><dd>${esc(c.mobile)}</dd>
            <dt>Email</dt><dd>${esc(c.email)}</dd>
            <dt>PAN</dt><dd><code>${esc(c.panMasked)}</code></dd>
            <dt>Aadhaar</dt><dd><code>${esc(c.aadhaarMasked)}</code></dd>
          </dl>
          ${table(['Account no.', 'Type', 'Balance', 'Status'],
            result.accounts.map(a => [`<code>${esc(a.accountNo)}</code>`, esc(a.type), esc(money(a.balance)), pill(a.status)]),
            'No accounts.')}
        </div>
        <div class="card">
          ${cardHead('Cash counter')}
          ${activeAccounts.length ? `
          <form class="stack" data-action="deposit">
            <label>Account<select name="account">${accountOptions}</select></label>
            <label>Amount (Rs)<input name="amount" type="number" min="1" step="0.01" required></label>
            <button class="btn primary" type="submit">Deposit cash</button>
          </form>
          <form class="stack" data-action="withdraw">
            <label>Account<select name="account">${accountOptions}</select></label>
            <label>Amount (Rs)<input name="amount" type="number" min="1" step="0.01" required></label>
            <button class="btn danger" type="submit">Withdraw cash</button>
          </form>` : empty(result.accounts.length ? 'No active accounts are available for cash transactions.' : 'Open an account first.')}
        </div>
      </div>
      <div class="card">
        ${cardHead('Recent transactions (main account)')}
        ${table(['Date', 'Reference', 'Type', 'Narration', 'Amount', 'Balance'],
          result.recent.map(t => [esc(when(t.date)), shortRef(t.reference), esc(t.type),
            esc(t.narration), `<span class="${t.direction === 'CREDIT' ? 'amt-in' : 'amt-out'}">${money(t.amount)}</span>`,
            esc(money(t.balanceAfter))]), 'No transactions to show.')}
      </div>`;
  }

  function viewOpenAccount() {
    const done = state.onboarded;
    const banner = done ? `
      <div class="notice success">
        Account opened for <strong>${esc(done.username)}</strong> · CIF ${esc(done.cifNo)} · KYC ${pill(done.kycStatus)}.
      </div>` : '';
    const field = (label, input) => `<label>${label}${input}</label>`;
    return `
      ${banner}
      <div class="card">
        ${cardHead('New customer onboarding')}
        <p class="small muted">Verify the original documents before saving. Full Aadhaar is not stored; only the last 4 digits and a secure hash are kept.</p>
        <form class="stack" data-action="onboard">
          <h4>Personal details</h4>
          <div class="grid-2">
            ${field('Full name (as on PAN)', '<input name="fullName" required maxlength="80">')}
            ${field('Date of birth (18+)', '<input name="dateOfBirth" type="date" required>')}
            ${field('Gender', '<select name="gender"><option>FEMALE</option><option>MALE</option><option>OTHER</option></select>')}
            ${field('Mobile number (10 digits)', '<input name="mobile" inputmode="numeric" pattern="[6-9][0-9]{9}" maxlength="10" required>')}
            ${field('Email', '<input name="email" type="email" required>')}
          </div>
          <h4>KYC documents</h4>
          <div class="grid-2">
            ${field('PAN (individual, e.g. ABCPE1234F)', '<input name="pan" required maxlength="10" style="text-transform:uppercase">')}
            ${field('Aadhaar (12 digits)', '<input name="aadhaar" inputmode="numeric" maxlength="14" required>')}
          </div>
          <h4>Permanent address</h4>
          <div class="grid-2">
            ${field('Address line 1', '<input name="line1" required maxlength="120">')}
            ${field('Address line 2', '<input name="line2" maxlength="120">')}
            ${field('City', '<input name="city" required maxlength="60">')}
            ${field('State', '<input name="state" required maxlength="60">')}
            ${field('Pincode (6 digits)', '<input name="pincode" inputmode="numeric" pattern="[1-9][0-9]{5}" maxlength="6" required>')}
          </div>
          <h4>Account</h4>
          <div class="grid-2">
            ${field('Account type', '<select name="accountType"><option>SAVINGS</option><option>CURRENT</option></select>')}
            ${field('Opening deposit (Rs)', '<input name="openingDeposit" type="number" min="0" step="0.01" required>')}
            ${field('Initial password (min 8 characters)', '<input name="password" type="password" minlength="8" required autocomplete="new-password">')}
          </div>
          <button class="btn primary" type="submit">Create customer</button>
        </form>
      </div>`;
  }

  // ---------- admin views ----------
  async function viewDashboard() {
    const d = await api('GET', '/api/admin/overview');
    const s = d.stats;
    const pendingKyc = d.customers.filter(c => c.kycStatus === 'PENDING').length;
    return `
      <div class="grid-4">
        <div class="card stat"><div class="label">Customers</div><div class="value">${esc(s.customers)}</div></div>
        <div class="card stat"><div class="label">Customer deposits</div><div class="value">${esc(money(s.customerDeposits))}</div></div>
        <div class="card stat"><div class="label">Pending loans</div><div class="value">${esc(s.pendingLoans)}</div></div>
        <div class="card stat"><div class="label">KYC pending</div><div class="value">${esc(pendingKyc)}</div></div>
      </div>
      <div class="card">
        ${cardHead('Ledger health', s.ledgerBalanced ? pill('ACTIVE') : pill('LOCKED'))}
        <p class="muted">Ledger status:
          ${s.ledgerBalanced ? '<strong>Balanced ✓</strong>' : '<strong class="error">Out of balance — please investigate</strong>'}
          (current value ${esc(money(s.trialBalance))}).</p>
      </div>`;
  }

  async function viewCustomers() {
    const d = await api('GET', '/api/admin/overview');
    const rows = d.customers.map(c => {
      const actions = [];
      if (c.kycStatus !== 'VERIFIED') actions.push(`<button class="btn small-btn primary" data-act="kyc" data-id="${esc(c.customerId)}" data-status="VERIFIED">Verify KYC</button>`);
      if (c.kycStatus !== 'REJECTED') actions.push(`<button class="btn small-btn danger" data-act="kyc" data-id="${esc(c.customerId)}" data-status="REJECTED">Reject</button>`);
      return [
        esc(c.cifNo), esc(c.name), esc(c.mobile), `<code>${esc(c.panMasked)}</code>`,
        `<code>${esc(c.aadhaarMasked)}</code>`, pill(c.kycStatus), actions.join(' ') || '<span class="muted small">Done</span>',
      ];
    });
    return `
      <div class="card">
        ${cardHead('Customers and KYC verification')}
        <p class="small muted">Review customer documents and update their KYC status.</p>
        ${table(['CIF', 'Name', 'Mobile', 'PAN', 'Aadhaar', 'KYC', 'Action'], rows, 'No customers found.')}
      </div>`;
  }

  async function viewLoanApprovals() {
    const d = await api('GET', '/api/admin/overview');
    const rows = d.loans.map(l => [
      esc(l.id), esc(l.customerName), esc(l.type), esc(money(l.amount)), `${esc(l.tenureMonths)} m`,
      esc(money(l.emi)), pill(l.status),
      l.status === 'PENDING'
        ? `<button class="btn small-btn primary" data-act="loan" data-id="${esc(l.id)}" data-approve="true">Approve</button>
           <button class="btn small-btn danger" data-act="loan" data-id="${esc(l.id)}" data-approve="false">Reject</button>`
        : '<span class="muted small">Decided</span>',
    ]);
    return `
      <div class="card">
        ${cardHead('Loan applications')}
        ${table(['#', 'Customer', 'Type', 'Amount', 'Tenure', 'EMI', 'Status', 'Decision'], rows, 'No loan applications.')}
      </div>`;
  }

  async function viewUsers() {
    const d = await api('GET', '/api/admin/overview');
    const rows = d.users.map(u => {
      let action = '<span class="muted small">—</span>';
      if (u.id !== state.user.id) {
        if (u.status === 'ACTIVE') action = `<button class="btn small-btn danger" data-act="user" data-id="${esc(u.id)}" data-status="DISABLED">Disable</button>`;
        else if (u.status === 'LOCKED') action = `<button class="btn small-btn" data-act="user" data-id="${esc(u.id)}" data-status="ACTIVE">Unlock</button>`;
        else action = `<button class="btn small-btn primary" data-act="user" data-id="${esc(u.id)}" data-status="ACTIVE">Enable</button>`;
      }
      return [esc(u.username), esc(u.name), esc(u.roleLabel), pill(u.status), esc(u.failedAttempts), action];
    });
    return `
      <div class="card">
        ${cardHead('Add a cashier')}
        <p class="small muted">Create a login for a new cashier.</p>
        <form class="grid-2" data-action="cashier">
          <label>Full name<input name="fullName" required maxlength="120" autocomplete="name"></label>
          <label>Username<input name="username" required minlength="3" maxlength="30" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,29}" autocomplete="username"></label>
          <label>Initial password<input name="password" type="password" required minlength="8" autocomplete="new-password"></label>
          <div class="row end"><button class="btn primary" type="submit">Create cashier</button></div>
        </form>
      </div>
      <div class="card">
        ${cardHead('Staff and customer logins')}
        ${table(['Username', 'Name', 'Role', 'Status', 'Failed logins', 'Action'], rows, 'No users.')}
      </div>`;
  }

  async function viewAudit() {
    const rows = await api('GET', '/api/admin/audit?limit=' + state.auditLimit);
    return `
      <div class="card">
        ${cardHead('Audit trail', `<label class="inline">Show
          <select data-audit-limit>
            ${[100, 300, 1000].map(n => `<option value="${n}" ${n === state.auditLimit ? 'selected' : ''}>${n}</option>`).join('')}
          </select></label>`)}
        ${table(['Time', 'User', 'Action', 'Entity', 'Details'],
          rows.map(a => [esc(when(a.time)), esc(a.user), esc(a.action), esc(`${a.entity ?? ''} ${a.entityId ?? ''}`.trim()), esc(a.details)]),
          'No audit entries found.')}
      </div>`;
  }

  // ---------- form actions (submit events) ----------
  const FORMS = {
    async login(f) {
      const r = await api('POST', '/api/login', { identifier: f.identifier, password: f.password });
      saveToken(r.token);
      state.user = r.user;
      enter();
    },
    async transfer(f, form) {
      const r = await api('POST', '/api/customer/transfer', { toAccount: f.toAccount, amount: Number(f.amount), note: f.note });
      toast('Money sent. Reference ' + r.reference);
      form.reset();
      renderMain();
    },
    async upi(f, form) {
      const recipient = await api('GET', '/api/customer/upi/recipient?to=' + encodeURIComponent(f.upiId));
      if (!window.confirm(`Send ${money(f.amount)} to ${recipient.name}?`)) return;
      const r = await api('POST', '/api/customer/upi', { upiId: f.upiId, amount: Number(f.amount), note: f.note });
      toast('UPI payment sent. Reference ' + r.reference);
      form.reset();
      renderMain();
    },
    async loan(f, form) {
      const r = await api('POST', '/api/customer/loan', { type: f.type, amount: Number(f.amount), tenureMonths: Number(f.tenureMonths) });
      toast(`Loan applied. EMI ${money(r.emi)} per month, waiting for approval.`);
      form.reset();
      renderMain();
    },
    async fd(f, form) {
      const r = await api('POST', '/api/customer/fd', { amount: Number(f.amount), tenureMonths: Number(f.tenureMonths) });
      toast(`Fixed deposit opened. Maturity ${money(r.maturityAmount)} on ${day(r.maturityDate)}.`);
      form.reset();
      renderMain();
    },
    async lookup(f) {
      state.desk = f.q;
      renderMain();
    },
    async deposit(f, form) {
      const r = await api('POST', '/api/cashier/deposit', { account: f.account, amount: Number(f.amount) });
      toast('Deposit posted. Reference ' + r.reference);
      form.reset();
      renderMain();
    },
    async withdraw(f, form) {
      const r = await api('POST', '/api/cashier/withdraw', { account: f.account, amount: Number(f.amount) });
      toast('Withdrawal posted. Reference ' + r.reference);
      form.reset();
      renderMain();
    },
    async onboard(f, form) {
      const body = { ...f, openingDeposit: f.openingDeposit, pan: f.pan.toUpperCase() };
      const r = await api('POST', '/api/staff/customers', body);
      state.onboarded = r;
      toast('Customer created: ' + r.username);
      form.reset();
      renderMain();
    },
    async cashier(f, form) {
      const r = await api('POST', '/api/admin/users/cashier', f);
      toast(`Cashier created: ${r.username}`);
      form.reset();
      renderMain();
    },
  };

  // ---------- button actions (click events with data-act) ----------
  const BUTTONS = {
    async kyc(ds) {
      await api('POST', '/api/admin/kyc', { customerId: Number(ds.id), status: ds.status });
      toast('KYC updated to ' + ds.status);
    },
    async loan(ds) {
      await api('POST', '/api/admin/loans/decide', { loanId: Number(ds.id), approve: ds.approve === 'true' });
      toast(ds.approve === 'true' ? 'Loan approved and disbursed.' : 'Loan rejected.');
    },
    async repay(ds) {
      const r = await api('POST', '/api/customer/loan/repay', { loanId: Number(ds.id) });
      toast('EMI paid. Reference ' + r.reference);
    },
    async user(ds) {
      await api('POST', '/api/admin/users/status', { userId: Number(ds.id), status: ds.status });
      toast('User status set to ' + ds.status);
    },
  };

  async function logout() {
    try { await api('POST', '/api/logout', {}); } catch (_) { /* session may already be gone */ }
    forgetSession();
    state.desk = null;
    state.deskResult = null;
    state.onboarded = null;
    showLogin();
  }

  // ---------- event wiring (one listener each, works after re-render) ----------
  function wire() {
    app().addEventListener('focusout', async (e) => {
      const input = e.target;
      if (!input.matches('#upiId')) return;
      const box = $('#recipient-name');
      const value = input.value.trim();
      if (!box || !value) return;
      box.textContent = 'Checking recipient…';
      try {
        const recipient = await api('GET', '/api/customer/upi/recipient?to=' + encodeURIComponent(value));
        if (input.isConnected && input.value.trim() === value) box.textContent = 'Paying to: ' + recipient.name;
      } catch (err) {
        if (input.isConnected && input.value.trim() === value) box.textContent = err.message;
      }
    });
    app().addEventListener('input', (e) => {
      if (e.target.matches('#upiId')) {
        const box = $('#recipient-name');
        if (box) box.textContent = 'Enter recipient details to see the account name.';
      }
    });
    app().addEventListener('click', async (e) => {
      const el = e.target.closest('button');
      if (!el || el.disabled) return;

      if (el.id === 'logout') return logout();
      if (el.dataset.go) return go(el.dataset.go);

      const act = el.dataset.act;
      if (act && BUTTONS[act]) {
        el.disabled = true;
        try {
          await BUTTONS[act](el.dataset);
          renderMain();
        } catch (err) {
          if (err.message !== 'Session ended') toast(err.message, true);
          el.disabled = false;
        }
      }
    });

    app().addEventListener('change', (e) => {
      if (e.target.matches('[data-audit-limit]')) {
        state.auditLimit = Number(e.target.value);
        renderMain();
      }
    });

    app().addEventListener('submit', async (e) => {
      const form = e.target;
      const name = form.dataset.action;
      if (!name || !FORMS[name]) return;
      e.preventDefault();
      const submit = form.querySelector('[type="submit"]');
      const values = {};
      for (const [k, v] of new FormData(form)) values[k] = typeof v === 'string' ? v.trim() : v;
      if (submit) submit.disabled = true;
      try {
        await FORMS[name](values, form);
      } catch (err) {
        if (err.message !== 'Session ended') toast(err.message, true);
      } finally {
        if (submit && submit.isConnected) submit.disabled = false;
      }
    });
  }

  // ---------- start ----------
  async function boot() {
    wire();
    state.token = loadToken();
    if (state.token) {
      try {
        state.user = await api('GET', '/api/session');
      } catch (_) {
        forgetSession();
      }
    }
    if (state.user) enter();
    else showLogin();
  }

  boot();
})();
