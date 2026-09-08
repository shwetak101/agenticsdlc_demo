(() => {
  "use strict";

  const $ = (selector, scope = document) => scope.querySelector(selector);
  const root = $("#root");
  const dialog = $("#dialog");
  const reasons = {
    RETURNED_ITEM: "Returned item",
    DAMAGED_ITEM: "Damaged item",
    DUPLICATE_CHARGE: "Duplicate charge",
    CUSTOMER_REQUEST: "Customer request",
    OTHER: "Other"
  };
  const paths = {
    grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
    box: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="M3 8v9l9 5 9-5V8M12 13v9M7 5.8l9 5"/>',
    ledger: '<rect x="3" y="5" width="18" height="15" rx="2"/><path d="M3 10h18M7 15h4M16 15h1"/>',
    activity: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    diagonal: '<path d="M6 18 18 6M6 6h12v12"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9S4 17 4 12V6l8-3Z"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
    bolt: '<path d="m13 2-9 12h7l-1 8 10-13h-7l0-7Z"/>',
    reset: '<path d="M3 10a9 9 0 1 1 2 8M3 4v6h6"/>',
    logout: '<path d="M9 4H4v16h5M13 8l4 4-4 4M8 12h13"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5"/>',
    moon: '<path d="M20.5 13A9 9 0 0 1 11 3.5 9 9 0 1 0 20.5 13Z"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7v.1"/>',
    warning: '<path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v5M12 17v.1"/>',
    eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4M12 14v3"/>',
    receipt: '<path d="M5 3v18l3-2 4 2 4-2 3 2V3l-3 2-4-2-4 2-3-2Z"/><path d="M9 9h6M9 13h6M9 17h3"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    code: '<path d="m8 7-5 5 5 5m8-10 5 5-5 5m-3-14-4 18"/>'
  };
  const state = {
    session: null, data: null, view: "overview", orderSearch: "", orderStatus: "all",
    orderValue: "all", orderCategory: "all", paymentSearch: "", paymentValue: "all",
    eventSearch: "", pageError: "", refreshedAt: null, refreshing: false,
    pending: null, modal: null, busy: false, focusReturn: null, sessionGeneration: 0
  };
  let toastTimer;
  const escape = value => String(value ?? "").replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
  const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.info}</svg>`;
  const money = value => new Intl.NumberFormat("en-IN", {
    style: "currency", currency: "INR", maximumFractionDigits: 2, minimumFractionDigits: 0
  }).format(Number(value) || 0);
  const number = value => new Intl.NumberFormat("en-IN").format(value);
  const sum = rows => rows.reduce((total, row) => total + Number(row.amount || 0), 0);
  const newest = (rows, key) => [...rows].sort((a, b) => new Date(b[key]) - new Date(a[key]));
  const initials = name => String(name || "?").trim().split(/\s+/).slice(0, 2).map(part => part[0]).join("").toUpperCase();
  const date = (value, withTime = false) => {
    if (!value || Number.isNaN(new Date(value).getTime())) return "Not recorded";
    return new Intl.DateTimeFormat("en-IN", {
      day: "2-digit", month: "short", year: "numeric",
      ...(withTime ? { hour: "2-digit", minute: "2-digit" } : {})
    }).format(new Date(value));
  };
  const time = value => new Intl.DateTimeFormat("en-IN", { hour: "2-digit", minute: "2-digit" }).format(new Date(value));
  const eligible = () => state.data ? state.data.orders.filter(order => order.status === "PAID") : [];
  const sentPayments = () => state.data ? state.data.payments.filter(payment => payment.status === "SENT_TO_PROVIDER") : [];
  const roles = user => (user.roles || []).map(role => role === "REQUESTOR" ? "Requestor" : role === "APPROVER" ? "Approver" : role).join(" + ");
  const hasRequestRole = () => state.session?.user?.roles?.includes("REQUESTOR");
  const avatar = (name, text, rose = false) => `<span class="avatar${rose ? " rose" : ""}" aria-hidden="true">${escape(text || initials(name))}</span>`;
  const brand = () => '<div class="brand"><div class="brand-mark" aria-hidden="true">R<span>↗</span></div><div><div class="brand-name">RefundOps</div><div class="brand-caption">Operations, in view</div></div></div>';
  const status = sent => `<span class="status-pill${sent ? " sent" : ""}">${sent ? "Sent to provider" : "Eligible"}</span>`;
  const providerName = value => value === "MockPay" ? "Payment gateway" : value;
  const announce = message => { $("#announcer").textContent = message; };
  const toast = message => {
    clearTimeout(toastTimer);
    $("#toast").textContent = message;
    $("#toast").hidden = false;
    toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 7000);
  };
  const themeButton = () => `<button class="icon-button" data-action="theme" aria-label="Switch color theme" title="Switch color theme">${icon(document.documentElement.dataset.theme === "dark" ? "sun" : "moon")}</button>`;
  const workflow = () => `<div class="workflow" aria-label="Select an order, request a refund, send to the payment gateway">
    <div class="flow-step"><div class="flow-icon">${icon("box")}</div><strong>Select order</strong><small>Full-order amount</small></div>
    <svg class="flow-arrow" viewBox="0 0 32 16" aria-hidden="true"><path d="M1 8h28m-5-5 5 5-5 5"/></svg>
    <div class="flow-step"><div class="flow-icon">${icon("receipt")}</div><strong>Request refund</strong><small>Your request, recorded</small></div>
    <svg class="flow-arrow" viewBox="0 0 32 16" aria-hidden="true"><path d="M1 8h28m-5-5 5 5-5 5"/></svg>
    <div class="flow-step"><div class="flow-icon">${icon("diagonal")}</div><strong>Send payment</strong><small>Automatic processing</small></div>
  </div>`;

  class ApiError extends Error {
    constructor(message, statusCode = 0, code = "") {
      super(message);
      this.status = statusCode;
      this.code = code;
    }
  }

  async function request(url, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 25000);
    try {
      const response = await fetch(url, {
        ...options, credentials: "same-origin", cache: "no-store",
        headers: { Accept: "application/json", ...options.headers }, signal: controller.signal
      });
      let result;
      try { result = await response.json(); } catch {
        throw new ApiError("The server returned an unexpected response. Check that the RefundOps backend is available, then retry.", response.status);
      }
      if (!response.ok) throw new ApiError(result.message || `Request failed (${response.status}). Please retry.`, response.status, result.code);
      return result;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(error.name === "AbortError"
        ? "The server took too long to respond. The outcome is not yet known. Check the live records or explicitly retry the same request."
        : "Unable to connect. Check your connection and try again.");
    } finally { clearTimeout(timeout); }
  }

  async function session() {
    const result = await request("/api/session");
    if (typeof result.authenticated !== "boolean" || !result.csrf?.token || !result.csrf?.headerName || !result.csrf?.parameterName) {
      throw new ApiError("Session information is incomplete. Check the backend and retry.");
    }
    state.session = result;
    return result;
  }

  function csrfHeaders() {
    if (!state.session?.csrf) throw new ApiError("Refresh your session before continuing.");
    return { [state.session.csrf.headerName]: state.session.csrf.token };
  }

  function expireSession() {
    state.sessionGeneration++;
    state.data = null;
    state.pending = null;
    state.session = null;
    clearFilters();
    state.modal = null;
    state.busy = false;
    if (dialog.open) dialog.close();
    renderLogin("Your session has ended. Sign in again to continue. No request will be retried automatically.");
  }

  async function api(url, options = {}) {
    try { return await request(url, options); } catch (error) {
      if (error.status === 401) {
        expireSession();
      } else if (error.status === 403) {
        try {
          const updated = await session();
          if (!updated.authenticated) { expireSession(); throw new ApiError("Please sign in again.", 401); }
        } catch (refreshError) {
          if (refreshError.status === 401) throw refreshError;
          throw new ApiError("The security check failed and the session could not be refreshed. Check the connection, then explicitly retry.", 403);
        }
        throw new ApiError("The security check blocked this request. Your session has been refreshed; review and explicitly retry. Nothing was automatically resubmitted.", 403);
      }
      throw error;
    }
  }

  function useDashboard(data) {
    if (!data.application || !["orders", "refunds", "payments", "events"].every(key => Array.isArray(data[key]))) {
      throw new ApiError("The dashboard response is incomplete. Please refresh; no substitute data is being shown.");
    }
    state.data = data;
    state.refreshedAt = new Date();
    state.pageError = "";
  }

  async function refreshDashboard() {
    const generation = state.sessionGeneration;
    const data = await api("/api/dashboard");
    if (generation !== state.sessionGeneration || !state.session?.authenticated) return;
    useDashboard(data);
  }

  async function boot() {
    root.innerHTML = `<main id="main" class="boot-screen" tabindex="-1">${brand()}<p class="muted">Opening your workspace…</p><span class="spinner" aria-label="Loading"></span></main>`;
    try {
      const current = await session();
      if (!current.authenticated) { renderLogin(); return; }
      try { await refreshDashboard(); } catch (error) {
        if (error.status === 401) return;
        state.pageError = error.message;
      }
      if (state.session?.authenticated) renderApp();
    } catch (error) {
      root.innerHTML = `<main id="main" class="boot-screen" tabindex="-1">${brand()}<h1>Let’s reconnect.</h1><p class="muted">${escape(error.message)}</p><button class="btn primary" data-action="boot">${icon("reset")}Retry connection</button><p class="technical-note">If the problem continues, contact your workspace administrator.</p></main>`;
    }
  }

  function renderLogin(message = "") {
    state.view = "overview";
    state.pageError = "";
    root.innerHTML = `<main id="main" class="login-shell" tabindex="-1">
      <section class="login-story">
        ${brand()}
        <div class="story-body">
          <span class="badge accent">REFUND OPERATIONS</span>
          <h1>Every refund.<br>A clear <em>line<br>of sight.</em></h1>
          <p>From the original order to the provider instruction. One workspace for the people behind every request.</p>
          <div class="story-workflow">${workflow()}</div>
        </div>
        <div class="story-footer"><span>Orders. Requests. Payments.</span><span>One connected workspace.</span></div>
      </section>
      <section class="login-side" aria-label="Sign in">
        <div class="login-top"><span class="badge neutral">TEAM WORKSPACE</span>${themeButton()}</div>
        <div class="login-card">
          <div class="eyebrow">Your operations workspace</div>
          <h2>Welcome to RefundOps.</h2>
          <p class="muted">Sign in to see the full picture.</p>
          <div class="field-label">Choose your account</div>
          <div class="account-cards">
            <button type="button" class="account-card" data-action="account" data-username="dahnesh" aria-label="Fill username for Dahnesh">${avatar("Dahnesh", "D", true)}<span><strong>Dahnesh</strong><small>Requestor + Approver</small></span></button>
            <button type="button" class="account-card" data-action="account" data-username="shweta" aria-label="Fill username for Shweta">${avatar("Shweta", "S")}<span><strong>Shweta</strong><small>Requestor</small></span></button>
          </div>
          <form id="login-form">
            <div id="login-error" class="error-message" role="alert" ${message ? "" : "hidden"}>${escape(message)}</div>
            <div class="field"><label for="username">Username</label><input id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="Enter your username" required maxlength="100"></div>
            <div class="field"><label for="password">Password</label><div class="password-field"><input id="password" name="password" type="password" autocomplete="current-password" placeholder="Enter your password" required><button type="button" class="icon-button" data-action="password" aria-label="Show password" aria-pressed="false">${icon("eye")}</button></div></div>
            <button type="submit" class="btn primary full-width" id="login-submit">Sign in to workspace ${icon("arrow")}</button>
          </form>
          <div class="login-note">${icon("lock")}<span>Sign in with your account to access refund operations.</span></div>
        </div>
        <div class="login-bottom">RefundOps · Built around your operations.</div>
      </section>
    </main>`;
    if (message) $("#username").focus();
  }

  async function login(form) {
    if (state.busy) return;
    const username = $("#username").value.trim();
    const passwordField = $("#password");
    const password = passwordField.value;
    const button = $("#login-submit");
    state.busy = true;
    button.disabled = true;
    button.textContent = "Authenticating…";
    $("#login-error").hidden = true;
    try {
      if (!state.session?.csrf) await session();
      const body = new URLSearchParams({ username, password });
      body.set(state.session.csrf.parameterName, state.session.csrf.token);
      await request("/login", {
        method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded", ...csrfHeaders() }, body
      });
      passwordField.value = "";
      const current = await session();
      if (!current.authenticated) throw new ApiError("Sign-in could not be verified. Please try again.");
      state.sessionGeneration++;
      state.data = null;
      state.pageError = "";
      try { await refreshDashboard(); } catch (error) {
        if (error.status === 401) return;
        state.pageError = error.message;
      }
      if (state.session?.authenticated) {
        renderApp();
        $("#main").focus();
        announce(`Signed in as ${current.user.displayName}.`);
      }
    } catch (error) {
      if (error.status === 403) {
        try { await session(); } catch { state.session = null; }
      }
      const errorElement = $("#login-error");
      if (errorElement) {
        errorElement.textContent = error.status === 401 ? "That username and password didn’t match. Please try again."
          : error.status === 403 ? "Your sign-in security token changed. Please enter your password and sign in again." : error.message;
        errorElement.hidden = false;
        if ($("#password")) { $("#password").value = ""; $("#password").focus(); }
      }
    } finally {
      state.busy = false;
      if (form.isConnected) { button.disabled = false; button.innerHTML = `Sign in to workspace ${icon("arrow")}`; }
    }
  }

  const navNames = { overview: "Overview", orders: "Orders", payments: "Payment ledger", activity: "Activity" };
  function renderApp() {
    if (!state.session?.authenticated) return;
    const user = state.session.user;
    const counts = state.data ? { orders: state.data.orders.length, payments: state.data.payments.length, activity: state.data.events.length } : {};
    root.innerHTML = `<button class="mobile-shade" data-action="menu-close" aria-label="Close navigation" tabindex="-1"></button>
      <aside class="sidebar" id="sidebar" aria-label="Workspace navigation">
        <div class="sidebar-brand">${brand()}<button class="icon-button sidebar-close" data-action="menu-close" aria-label="Close navigation">${icon("close")}</button></div>
        <div class="workspace"><div class="workspace-top"><span>Refund operations</span></div><small>Customer service · India</small></div>
        <div class="nav-label">Workspace</div>
        <nav class="navigation" aria-label="Main navigation">${Object.entries(navNames).map(([view, label]) => `<button class="nav-link${state.view === view ? " active" : ""}" data-action="navigate" data-view="${view}" ${state.view === view ? 'aria-current="page"' : ""}>${icon({ overview: "grid", orders: "box", payments: "ledger", activity: "activity" }[view])}<span>${label}</span>${counts[view] !== undefined ? `<span class="nav-count">${number(counts[view])}</span>` : ""}</button>`).join("")}</nav>
        <div class="sidebar-bottom">
          <div class="mode-note"><h3>${icon("bolt")}Automatic processing</h3><p>Eligible refund requests are sent directly to the payment gateway.</p></div>
          <button class="btn subtle sidebar-reset" data-action="info">${icon("info")}Workspace details</button>
          <div class="identity">${avatar(user.displayName, null, true)}<div class="identity-copy"><strong>${escape(user.displayName)}</strong><small>${escape(roles(user))}</small><small>@${escape(user.username)}</small></div><button class="icon-button" data-action="logout" aria-label="Sign out" title="Sign out">${icon("logout")}</button></div>
        </div>
      </aside>
      <div class="app-area">
        <header class="topbar"><div class="breadcrumbs"><button class="icon-button menu-button" data-action="menu" aria-label="Open navigation" aria-controls="sidebar" aria-expanded="false">${icon("menu")}</button><span>Workspace</span>${icon("chevron")}<b>${navNames[state.view]}</b></div><div class="top-actions"><span class="sync-label">${state.data ? `<span class="sync-dot"></span>Fetched ${escape(time(state.refreshedAt))}` : "Waiting for records"}</span><div class="top-identity" aria-label="Active authenticated identity">${avatar(user.displayName, null, true)}<span><strong>${escape(user.displayName)}</strong><small>${escape(roles(user))}</small></span></div>${themeButton()}</div></header>
        <main class="main-content" id="main" tabindex="-1">${renderPage()}</main>
      </div>`;
  }

  function pageHeading() {
    const headings = {
      overview: ["YOUR WORKSPACE", "Refund operations", "Select an order below to request a refund."],
      orders: ["CUSTOMER ORDERS", "Find an order", "Search by order number, customer or product, then select Request refund."],
      payments: ["PAYMENTS", "Payment ledger", "Track refund instructions sent to the payment gateway."],
      activity: ["WORKSPACE ACTIVITY", "Every action, recorded", "Follow refund requests and the people behind them."]
    };
    const [eyebrow, title, subtitle] = headings[state.view];
    return `<div class="page-heading"><div><div class="eyebrow">${eyebrow}</div><h1>${title}</h1><p>${subtitle}</p></div><div class="heading-actions"><button class="icon-button" data-action="refresh" aria-label="Refresh live records" title="Refresh live records" ${state.refreshing ? "disabled" : ""}>${icon("reset")}</button><button class="btn primary" data-action="new-refund" ${!hasRequestRole() || !state.data || (!eligible().length && !state.pending) ? "disabled" : ""}>${icon("plus")}${state.pending?.attempted ? "Resume request" : "Request refund"}</button></div></div>`;
  }

  function baselineBanner() {
    return `<div class="baseline-banner">${icon("bolt")}<div><strong>Automatic processing</strong><p>Eligible refunds of any amount are sent directly to the payment gateway.</p></div><span class="badge accent">ENABLED</span></div>`;
  }

  function metadata() {
    return `<footer class="metadata"><span class="metadata-copy">${icon("shield")}RefundOps · Customer operations</span><button class="text-button" data-action="info">Workspace details ${icon("diagonal")}</button></footer>`;
  }

  function renderPage() {
    let html = pageHeading();
    if (state.pageError) html += `<div class="error-message page-error" role="alert"><span>${escape(state.pageError)}${state.data ? " Previously fetched records remain visible." : ""}</span><button class="btn small" data-action="refresh" ${state.refreshing ? "disabled" : ""}>Retry</button></div>`;
    if (!state.data) return html + `<section class="panel">${empty("No records loaded yet", "Check that the backend is running, then refresh. Metrics will appear only after live data is available.", "ledger")}</section>` + metadata();
    if (state.view === "overview") html += metrics() + baselineBanner() + ordersPanel() + insights();
    if (state.view === "orders") html += baselineBanner() + ordersPanel();
    if (state.view === "payments") html += ledgerPanel();
    if (state.view === "activity") html += activityPanel();
    return html + metadata();
  }

  function metrics() {
    const payments = sentPayments();
    const refunds = state.data.refunds.filter(refund => refund.status === "SENT_TO_PROVIDER");
    const highPayments = payments.filter(payment => payment.amount > 10000);
    const metricsData = [
      ["Eligible orders", number(eligible().length), `of ${number(state.data.orders.length)} orders in this portfolio`, "box", false],
      ["Refunds sent", number(refunds.length), "Automatically processed", "receipt", false],
      ["Amount sent", money(sum(payments)), "Sent to the payment gateway", "diagonal", true],
      ["High-value payments", number(highPayments.length), `Above ₹10,000 · ${money(sum(highPayments))} sent`, "shield", false]
    ];
    return `<section class="metrics" aria-label="Live portfolio metrics">${metricsData.map(([label, value, note, glyph, featured]) => `<article class="metric${featured ? " featured" : ""}"><div class="metric-top"><span>${label}</span><span class="metric-icon">${icon(glyph)}</span></div><div class="metric-value">${value}</div><p>${escape(note)}</p></article>`).join("")}</section>`;
  }

  function insights() {
    const high = eligible().filter(order => order.amount > 10000);
    return `<div class="insights-grid">
      <section class="panel exposure-panel"><div class="exposure-heading"><h2>High-value orders</h2>${icon("diagonal")}</div><div class="exposure-amount"><strong>${money(sum(high))}</strong><span>across ${number(high.length)} eligible ${high.length === 1 ? "order" : "orders"} above ₹10,000</span></div><p>${high.length ? "These orders are eligible for full refunds through automatic processing." : "There are no remaining eligible orders above ₹10,000."}</p><div class="exposure-bottom"><div class="mini-workflow">${icon("box")}Order ${icon("arrow")}${icon("user")}Request ${icon("arrow")}${icon("ledger")}Payment</div><button class="text-button" data-action="high-orders">View orders ${icon("arrow")}</button></div></section>
      <section class="panel"><div class="panel-head"><div><h2>Recent activity</h2><p>The latest updates from your workspace</p></div><button class="text-button" data-action="navigate" data-view="activity">View all ${icon("arrow")}</button></div><div class="recent-activity">${state.data.events.length ? newest(state.data.events, "occurredAt").slice(0, 2).map(event => eventRow(event, true)).join("") : empty("You're up to date", "Your refund requests will appear here.", "activity", true)}</div></section>
    </div>`;
  }

  function empty(title, copy, glyph = "box", compact = false, action = "") {
    return `<div class="empty-state${compact ? " compact" : ""}"><div class="empty-icon">${icon(glyph)}</div><h3>${escape(title)}</h3><p>${escape(copy)}</p>${action}</div>`;
  }

  function orderMatches() {
    const query = state.orderSearch.trim().toLowerCase();
    return state.data.orders.filter(order => {
      const matchesText = [order.id, order.customerName, order.customerEmail, order.product, order.category].join(" ").toLowerCase().includes(query);
      return matchesText && (state.orderStatus === "all" || order.status === state.orderStatus)
        && (state.orderValue === "all" || (state.orderValue === "high" ? order.amount > 10000 : order.amount <= 10000))
        && (state.orderCategory === "all" || order.category === state.orderCategory);
    });
  }

  function ordersPanel() {
    const categories = [...new Set(state.data.orders.map(order => order.category).filter(Boolean))].sort();
    return `<section class="panel orders-panel" aria-labelledby="orders-title"><div class="panel-head"><div><h2 id="orders-title">Customer orders <span class="badge neutral">${number(state.data.orders.length)}</span></h2><p>Choose an order to get started</p></div><span class="badge neutral">INR</span></div>
      <div class="toolbar"><div class="search-box">${icon("search")}<label class="sr-only" for="order-search">Search orders, customers or products</label><input type="search" id="order-search" placeholder="Search order, customer, or product…" value="${escape(state.orderSearch)}"></div><div class="filter-controls"><label class="sr-only" for="order-status">Filter order status</label><select id="order-status"><option value="all">All statuses</option><option value="PAID" ${state.orderStatus === "PAID" ? "selected" : ""}>Eligible orders</option><option value="REFUND_SENT" ${state.orderStatus === "REFUND_SENT" ? "selected" : ""}>Sent to provider</option></select><label class="sr-only" for="order-value">Filter order amount</label><select id="order-value"><option value="all">All amounts</option><option value="high" ${state.orderValue === "high" ? "selected" : ""}>Above ₹10,000</option><option value="standard" ${state.orderValue === "standard" ? "selected" : ""}>₹10,000 or less</option></select><label class="sr-only" for="order-category">Filter category</label><select id="order-category"><option value="all">All categories</option>${categories.map(category => `<option value="${escape(category)}" ${state.orderCategory === category ? "selected" : ""}>${escape(category)}</option>`).join("")}</select></div></div>
      <div class="table-scroll" tabindex="0" role="region" aria-label="Orders table, scroll horizontally on smaller screens"><table><caption class="sr-only">Live orders and refund eligibility</caption><thead><tr><th scope="col">Customer / order</th><th scope="col">Product</th><th scope="col">Order amount</th><th scope="col">Purchased</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead><tbody id="orders-body">${orderRows()}</tbody></table></div>
      <div class="table-footer"><span id="order-count">${orderCount()}</span><span>Eligible order value: ${money(sum(eligible()))}</span></div></section>`;
  }

  function orderCount() { return `${number(orderMatches().length)} of ${number(state.data.orders.length)} orders · ${number(eligible().length)} eligible`; }
  function orderRows() {
    const orders = orderMatches();
    if (!orders.length) return `<tr><td colspan="6">${empty(state.data.orders.length ? "No matching orders" : "No orders available", state.data.orders.length ? "Try a different search or clear the filters to see all orders." : "Refresh to check for available orders.", "box", false, state.data.orders.length ? '<button class="btn small" data-action="clear-orders">Clear filters</button>' : "")}</td></tr>`;
    return orders.map(order => `<tr><td><div class="customer-cell">${avatar(order.customerName, order.customerInitials)}<div><span class="cell-primary">${escape(order.customerName)}</span><span class="cell-secondary mono">${escape(order.id)}</span></div></div></td><td><span class="cell-primary">${escape(order.product)}</span><span class="cell-secondary">${escape(order.category)}</span></td><td class="amount-cell">${money(order.amount)}${order.amount > 10000 ? "<small>High value</small>" : ""}</td><td><span>${escape(date(order.purchasedAt))}</span><span class="cell-secondary">${escape(order.paymentMethod)}</span></td><td>${status(order.status === "REFUND_SENT")}</td><td>${order.status === "PAID" ? `<button class="table-action" data-action="order-refund" data-id="${escape(order.id)}" aria-label="Request refund for ${escape(order.customerName)}, ${escape(order.id)}" ${hasRequestRole() ? "" : "disabled"}>Request refund ${icon("arrow")}</button>` : `<button class="text-button" data-action="order-payment" data-id="${escape(order.id)}">View payment ${icon("diagonal")}</button>`}</td></tr>`).join("");
  }

  function paymentMatches() {
    const query = state.paymentSearch.trim().toLowerCase();
    return newest(state.data.payments, "sentAt").filter(payment => [payment.id, payment.orderId, payment.refundId, payment.requesterName, payment.provider].join(" ").toLowerCase().includes(query)
      && (state.paymentValue === "all" || payment.amount > 10000));
  }

  function ledgerPanel() {
    const payments = sentPayments();
    return `<div class="ledger-summary"><div class="summary-icon">${icon("ledger")}</div><div><p>Total amount sent</p><strong>${money(sum(payments))}</strong></div><div class="summary-aside"><strong>${number(payments.length)} instructions</strong><p>Sent to the payment gateway</p></div></div>
      <section class="panel"><div class="panel-head"><div><h2>Payment ledger</h2><p>Your refund payment instructions</p></div><span class="badge accent">PAYMENTS</span></div>
      <div class="toolbar"><div class="search-box">${icon("search")}<label class="sr-only" for="payment-search">Search payment, order, refund or actor</label><input type="search" id="payment-search" placeholder="Search payment, order, or actor…" value="${escape(state.paymentSearch)}"></div><div class="filter-controls"><label class="sr-only" for="payment-value">Filter payment amount</label><select id="payment-value"><option value="all">All amounts</option><option value="high" ${state.paymentValue === "high" ? "selected" : ""}>Above ₹10,000</option></select></div></div>
      <div class="table-scroll" tabindex="0" role="region" aria-label="Payment ledger table"><table><caption class="sr-only">Provider instructions</caption><thead><tr><th scope="col">Payment / order</th><th scope="col">Requested by</th><th scope="col">Amount sent</th><th scope="col">Sent at</th><th scope="col">Status</th><th scope="col">Details</th></tr></thead><tbody id="payments-body">${paymentRows()}</tbody></table></div><div class="table-footer"><span id="payment-count">${number(paymentMatches().length)} of ${number(state.data.payments.length)} payments</span><span>Currency: INR</span></div></section>`;
  }

  function paymentRows() {
    const payments = paymentMatches();
    if (!payments.length) return `<tr><td colspan="6">${empty(state.data.payments.length ? "No matching payments" : "No payments yet", state.data.payments.length ? "Change your search or amount filter to find a payment." : "Request a refund from an eligible order. Its payment instruction will appear here.", "ledger", false, state.data.payments.length ? '<button class="btn small" data-action="clear-payments">Clear filters</button>' : `<button class="btn small" data-action="navigate" data-view="orders">View orders ${icon("arrow")}</button>`)}</td></tr>`;
    return payments.map(payment => `<tr><td><span class="cell-primary mono">${escape(payment.id)}</span><span class="cell-secondary mono">${escape(payment.orderId)}</span></td><td><div class="customer-cell">${avatar(payment.requesterName, null, true)}<span>${escape(payment.requesterName)}</span></div></td><td class="amount-cell">${money(payment.amount)}${payment.amount > 10000 ? "<small>High value · no approval gate</small>" : ""}</td><td>${escape(date(payment.sentAt, true))}</td><td>${status(true)}</td><td><button class="table-action" data-action="payment" data-id="${escape(payment.id)}" aria-label="View details for payment ${escape(payment.id)}">Details ${icon("diagonal")}</button></td></tr>`).join("");
  }

  function eventRow(event, compact = false) {
    const refund = state.data.refunds.find(item => item.id === event.refundId);
    const title = event.type === "BASELINE_READY" ? "Order portfolio available" : event.type === "REFUND_SENT" ? "Refund sent to provider" : event.title;
    const detail = event.type === "BASELINE_READY" ? "Customer orders are available for refund requests."
      : event.type === "REFUND_SENT" && refund ? `${refund.orderId} · ${money(refund.amount)} · ${reasons[refund.reason] || refund.reason}` : event.detail;
    return `<article class="activity-item"><div class="activity-glyph">${icon(event.refundId ? "diagonal" : "activity")}</div><div class="activity-copy"><strong>${escape(title)}</strong><p>${escape(detail)}</p><small>${escape(event.actorDisplayName || "System")}${!compact ? ` · ${escape(date(event.occurredAt, true))}` : ""}</small>${event.refundId && !compact ? `<button class="text-button" data-action="refund-payment" data-id="${escape(event.refundId)}">View payment ${icon("diagonal")}</button>` : ""}</div>${compact ? `<time class="activity-time" datetime="${escape(event.occurredAt)}" title="${escape(date(event.occurredAt, true))}">${escape(date(event.occurredAt))}</time>` : ""}</article>`;
  }

  function eventMatches() {
    const query = state.eventSearch.trim().toLowerCase();
    return newest(state.data.events, "occurredAt").filter(event => [event.title, event.detail, event.actorDisplayName, event.type, event.refundId].join(" ").toLowerCase().includes(query));
  }

  function activityRows() {
    const events = eventMatches();
    return events.length ? events.map(event => eventRow(event)).join("") : empty(state.data.events.length ? "No matching activity" : "The trail starts with an action.", state.data.events.length ? "Try another actor, order, or event keyword." : "No events have been recorded. Activity is populated only from the backend event log.", "activity");
  }

  function activityPanel() {
    return `<section class="panel"><div class="panel-head"><div><h2>Recorded activity</h2><p>Newest first · timestamps displayed in your local time zone</p></div><span class="badge neutral">${number(state.data.events.length)} EVENTS</span></div><div class="toolbar"><div class="search-box">${icon("search")}<label class="sr-only" for="event-search">Search activity by actor or event</label><input type="search" id="event-search" placeholder="Search person, order, or event…" value="${escape(state.eventSearch)}"></div></div><div class="timeline" id="activity-rows">${activityRows()}</div><div class="table-footer"><span id="event-count">${number(eventMatches().length)} recorded events shown</span><span>Team workspace</span></div></section>`;
  }

  function modalHeading(eyebrow, title, subtitle = "") {
    return `<div class="dialog-heading"><div><div class="eyebrow">${eyebrow}</div><h2 id="dialog-title" tabindex="-1">${title}</h2>${subtitle ? `<p>${subtitle}</p>` : ""}</div><button class="icon-button" data-action="dialog-close" aria-label="Close dialog" ${state.busy ? "disabled" : ""}>${icon("close")}</button></div>`;
  }

  function openDialog(kind, html, drawer = false) {
    if (!dialog.open) state.focusReturn = document.activeElement;
    state.modal = kind;
    dialog.classList.toggle("drawer", drawer);
    dialog.innerHTML = html;
    if (!dialog.open) dialog.showModal();
    $("#dialog-title", dialog)?.focus();
  }

  function closeDialog() {
    if (state.busy) return;
    if (state.modal === "refund" && state.pending && !state.pending.attempted) state.pending = null;
    state.modal = null;
    dialog.close();
    const target = state.focusReturn?.isConnected ? state.focusReturn : $('[data-action="new-refund"]') || $("#main");
    target?.focus();
  }

  function uuid() {
    if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(byte => byte.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  function startRefund(orderId = "") {
    if (!state.data || !hasRequestRole()) return;
    if (state.pending?.attempted) {
      state.pending.confirmed = false;
      renderReview();
      return;
    }
    state.pending = {
      orderId: eligible().some(order => order.id === orderId) ? orderId : "",
      reason: "", notes: "", idempotencyKey: uuid(), actor: state.session.user.username,
      attempted: false, confirmed: false, error: "", lastStatus: 0, payload: null, orderSnapshot: null
    };
    renderCompose();
  }

  function stepLabel(review) {
    return `<div class="step-label"><span class="${review ? "" : "current"}"><b>1</b>Request details</span><i class="step-line"></i><span class="${review ? "current" : ""}"><b>2</b>Review &amp; send</span></div>`;
  }

  function optionRows(query = "") {
    const list = eligible().filter(order => [order.id, order.customerName, order.product].join(" ").toLowerCase().includes(query.trim().toLowerCase()));
    return list.length ? list.map(order => `<label class="order-option"><input type="radio" name="refund-order" value="${escape(order.id)}" ${state.pending.orderId === order.id ? "checked" : ""}><span class="order-option-copy"><strong>${escape(order.customerName)}</strong><small>${escape(order.id)} · ${escape(order.product)}</small></span><strong>${money(order.amount)}</strong></label>`).join("")
      : empty("No eligible orders match", "Try a customer name, order ID, or product.", "search", true);
  }

  function selectedAmount() {
    const order = state.data.orders.find(item => item.id === state.pending.orderId);
    return `<div><span>Refund amount ${icon("lock")}</span><small>${order ? `${escape(order.id)} · full order value` : "Choose an order to see its amount"}</small></div><strong>${order ? money(order.amount) : "—"}</strong>`;
  }

  function actorLine() {
    const user = state.session.user;
    return `<div class="actor-line">${avatar(user.displayName, null, true)}<span>Acting as <strong>${escape(user.displayName)}</strong> · ${escape(roles(user))}<br><span class="mono">@${escape(user.username)}</span></span></div>`;
  }

  function renderCompose() {
    const draft = state.pending;
    openDialog("refund", `${modalHeading("NEW REFUND", "Select an order", "Choose the purchase you would like to refund.")}
      <form id="refund-form"><div class="dialog-body">${stepLabel(false)}<div id="refund-error" class="error-message" role="alert" hidden></div>
      <div class="field-label" id="order-picker-label" style="margin-bottom:8px">Eligible order <span class="soft">· ${number(eligible().length)} available</span></div>
      <div class="order-selector"><div class="search-box">${icon("search")}<label class="sr-only" for="refund-order-search">Search eligible orders</label><input id="refund-order-search" type="search" placeholder="Find an order or customer…"></div><div class="order-options" id="order-options" role="radiogroup" aria-labelledby="order-picker-label">${optionRows()}</div></div>
      <div class="selected-amount" id="selected-amount" aria-live="polite">${selectedAmount()}</div>
      <div class="field"><label for="refund-reason">Reason</label><select id="refund-reason" required><option value="">Select a reason</option>${Object.entries(reasons).map(([code, label]) => `<option value="${code}" ${draft.reason === code ? "selected" : ""}>${label}</option>`).join("")}</select></div>
      <div class="field"><label for="refund-notes">Notes <span>· optional</span></label><textarea id="refund-notes" maxlength="500" rows="3" placeholder="Add context for the audit trail…" aria-describedby="notes-count">${escape(draft.notes)}</textarea><small id="notes-count">${draft.notes.length} / 500 characters</small></div>
      ${actorLine()}</div><div class="dialog-footer"><button type="button" class="btn" data-action="dialog-close">Cancel</button><button class="btn primary" type="submit">Review request ${icon("arrow")}</button></div></form>`);
  }

  function reviewRefund() {
    const draft = state.pending;
    const order = eligible().find(item => item.id === draft.orderId);
    if (!order) {
      $("#refund-error").textContent = "Choose an eligible order before continuing.";
      $("#refund-error").hidden = false;
      $("#refund-order-search").focus();
      return;
    }
    draft.reason = $("#refund-reason").value;
    draft.notes = $("#refund-notes").value;
    if (!reasons[draft.reason] || draft.notes.length > 500) return;
    draft.orderSnapshot = { ...order };
    renderReview();
  }

  function detailList(items) {
    return `<dl class="detail-list">${items.map(([key, value, mono]) => `<div><dt>${escape(key)}</dt><dd${mono ? ' class="mono"' : ""}>${escape(value)}</dd></div>`).join("")}</dl>`;
  }

  function renderReview() {
    const draft = state.pending;
    const order = draft.orderSnapshot;
    if (!order) { renderCompose(); return; }
    openDialog("refund", `${modalHeading("REVIEW REFUND", draft.attempted ? "Review your retry" : "Ready to send?", "Confirm the details before sending your refund request.")}
      <div class="dialog-body">${stepLabel(true)}
      ${draft.error ? `<div class="error-message" role="alert">${escape(draft.error)}</div>` : ""}
      <div class="detail-card"><div class="detail-hero"><small>Refund amount · INR</small><strong>${money(order.amount)}</strong><span class="badge accent">AUTOMATIC${order.amount > 10000 ? " · HIGH VALUE" : ""}</span></div>${detailList([["Customer", order.customerName], ["Order", order.id, true], ["Product", order.product], ["Reason", reasons[draft.reason]], ["Provider", "Payment gateway"]])}</div>
      ${draft.notes ? `<div class="notes-block"><h3>Request notes</h3><p>${escape(draft.notes)}</p></div>` : ""}
      <div class="warning-box">${icon("bolt")}<div><h3>Automatic processing</h3><p>This sends ${money(order.amount)} directly to the payment gateway. No additional approval is required.</p></div></div>
      ${draft.attempted ? `<p class="technical-note" style="margin-bottom:16px">Retrying uses the original request. If it was already processed, you will receive its existing receipt.</p>` : ""}
      <label class="consent"><input type="checkbox" id="send-consent" ${draft.confirmed ? "checked" : ""}><span>I confirm the details and want to send this refund.</span></label>
      ${actorLine()}</div><div class="dialog-footer"><button class="btn" data-action="${[400, 409].includes(draft.lastStatus) ? "discard-request" : draft.attempted ? "dialog-close" : "refund-back"}">${[400, 409].includes(draft.lastStatus) ? "Close rejected request" : draft.attempted ? "Close for now" : "Back"}</button><button class="btn primary" data-action="send-refund" id="send-refund" ${draft.confirmed ? "" : "disabled"}>${icon("diagonal")}${draft.attempted ? "Retry same request" : "Send refund"}</button></div>`);
  }

  async function sendRefund() {
    const draft = state.pending;
    if (state.busy || !draft?.confirmed || !draft.orderSnapshot || draft.actor !== state.session?.user?.username) return;
    if (!draft.payload) {
      draft.payload = Object.freeze({ orderId: draft.orderId, reason: draft.reason, notes: draft.notes, idempotencyKey: draft.idempotencyKey });
    }
    draft.attempted = true;
    draft.error = "";
    state.busy = true;
    openDialog("refund", `${modalHeading("PROCESSING", "Sending your refund")}
      <div class="dialog-body"><div class="sending" role="status" aria-live="polite"><span class="spinner"></span><h3>${money(draft.orderSnapshot.amount)} · ${escape(draft.orderId)}</h3><p>Sending your request to the payment gateway.<br>Please keep this window open.</p></div>${actorLine()}</div>`);
    try {
      const result = await api("/api/refunds", { method: "POST", headers: { ...csrfHeaders(), "Content-Type": "application/json" }, body: JSON.stringify(draft.payload) });
      if (!result.refund || !result.payment || result.payment.status !== "SENT_TO_PROVIDER") {
        throw new ApiError("The response did not include a valid provider receipt. Check the live records or explicitly retry this same request.");
      }
      state.pending = null;
      try { await refreshDashboard(); } catch (error) {
        if (error.status === 401) return;
        state.pageError = "The provider returned a receipt, but refreshing the dashboard failed. " + error.message;
      }
      if (!state.session?.authenticated) return;
      state.busy = false;
      renderApp();
      renderReceipt(result);
      announce(`Sent to provider: ${money(result.payment.amount)}. ${result.payment.id}.`);
    } catch (error) {
      if (error.status === 401) return;
      draft.lastStatus = error.status;
      draft.error = error.message + (error.status === 409 ? " The shared order state may have changed. Close this dialog and refresh to inspect the recorded payment." : "");
      draft.confirmed = false;
      state.busy = false;
      renderReview();
    } finally { state.busy = false; }
  }

  function renderReceipt(result) {
    const { refund, payment } = result;
    openDialog("receipt", `${modalHeading("REFUND RECEIPT", "Sent to provider", result.replayed ? "This request was already recorded. Here is the original receipt." : "Your refund instruction has been sent.")}
      <div class="dialog-body"><div class="receipt-heading"><div class="receipt-icon">${icon("check")}</div><h3>${money(payment.amount)}</h3><p>Payment gateway · Automatically processed</p></div>
      ${state.pageError ? `<div class="error-message" role="alert">${escape(state.pageError)}</div>` : ""}
      <div class="detail-card">${detailList([["Payment ID", payment.id, true], ["Refund ID", refund.id, true], ["Order", payment.orderId, true], ["Requested by", refund.requesterName], ["Account", refund.requesterUsername, true], ["Sent at", date(payment.sentAt, true)], ["Status", "Sent to provider"]])}</div><p class="technical-note">Sent to provider does not confirm bank settlement.</p></div>
      <div class="dialog-footer"><button class="btn" data-action="dialog-close">Done</button><button class="btn primary" data-action="receipt-ledger">View payment ledger ${icon("arrow")}</button></div>`);
  }

  function showPayment(paymentId) {
    const payment = state.data?.payments.find(item => item.id === paymentId);
    if (!payment) { toast("That payment is not in the fetched records. Refresh the dashboard to check the shared state."); return; }
    const refund = state.data.refunds.find(item => item.id === payment.refundId);
    const order = state.data.orders.find(item => item.id === payment.orderId);
    openDialog("payment", `${modalHeading("PAYMENT DETAIL · AUDIT CONTEXT", "The provider instruction.", escape(payment.id))}
      <div class="dialog-body"><div class="detail-card"><div class="detail-hero"><small>Amount sent · INR</small><strong>${money(payment.amount)}</strong>${status(true)}</div>${detailList([["Provider", providerName(payment.provider)], ["Payment ID", payment.id, true], ["Refund ID", payment.refundId, true], ["Order", payment.orderId, true], ["Customer", order?.customerName || refund?.customerName || "Not recorded"], ["Customer email", order?.customerEmail || "Not recorded"], ["Product", order?.product || "Not recorded"], ["Requested by", refund?.requesterName || payment.requesterName], ["Account", refund?.requesterUsername || "Not recorded", true], ["Reason", reasons[refund?.reason] || refund?.reason || "Not recorded"], ["Requested at", date(refund?.requestedAt, true)], ["Sent at", date(payment.sentAt, true)]])}</div>
      ${refund?.notes ? `<div class="notes-block"><h3>Request notes</h3><p>${escape(refund.notes)}</p></div>` : ""}
      <div class="warning-box">${icon("bolt")}<div><h3>Automatically processed</h3><p>This refund was sent directly to the payment gateway. Sent to provider does not confirm bank settlement.</p></div></div></div><div class="dialog-footer"><button class="btn" data-action="dialog-close">Close details</button></div>`, true);
  }

  function showInfo() {
    const app = state.data?.application;
    openDialog("info", `${modalHeading("SANDBOX & TECHNOLOGY", "An honest legacy baseline.", "A working application, with synthetic provider instructions.")}
      <div class="dialog-body"><div class="warning-box">${icon("info")}<div><h3>What this demo does — and does not — do</h3><p>Authenticated users can send full-order refunds to MockPay. All valid requests are immediately processed without approval. No real funds move; payment records represent instructions, not settlements.</p></div></div>
      ${app ? `<div class="detail-card">${detailList([["Application", app.name], ["Version", app.version], ["Processing mode", app.mode], ["Provider", app.provider], ["Storage", app.storage], ["Java source baseline", app.javaBaseline], ["Spring Boot", app.springBootVersion]])}</div>` : '<p class="technical-note">Live application metadata is unavailable until the dashboard loads.</p>'}
      <p class="technical-note">Java source baseline 11 describes source compatibility, not the running JDK. Orders, payments, and activity are shared across demo accounts. An in-memory reset affects everyone.</p></div><div class="dialog-footer"><button class="btn danger" data-action="reset" ${state.data ? "" : "disabled"}>${icon("reset")}Reset demo data</button><button class="btn primary" data-action="dialog-close">Close</button></div>`);
  }

  function showReset() {
    openDialog("reset", `${modalHeading("SHARED SANDBOX", "Reset the demo?", "Restore the starting portfolio and clear the synthetic payment history.")}
      <div class="dialog-body"><div class="warning-box">${icon("warning")}<div><h3>This affects every signed-in demo user.</h3><p>The backend’s shared demo state will be reset, including refund requests, payment instructions, and activity. This is not a payment reversal. Open the starting portfolio only when everyone is ready.</p></div></div>
      <div id="reset-error" class="error-message" role="alert" hidden></div><label class="consent"><input type="checkbox" id="reset-consent"><span>I understand this resets shared demo data for Dahnesh and Shweta.</span></label>${actorLine()}</div><div class="dialog-footer"><button class="btn" data-action="dialog-close">Keep current data</button><button class="btn danger" data-action="reset-confirm" id="reset-confirm" disabled>${icon("reset")}Reset shared demo</button></div>`);
  }

  async function resetDemo() {
    if (state.busy || !$("#reset-consent")?.checked) return;
    state.busy = true;
    dialog.querySelectorAll("button, input").forEach(element => { element.disabled = true; });
    $("#reset-confirm").textContent = "Resetting…";
    try {
      const data = await api("/api/demo/reset", { method: "POST", headers: csrfHeaders() });
      useDashboard(data);
      state.pending = null;
      clearFilters();
      state.view = "overview";
      state.busy = false;
      closeDialog();
      renderApp();
      $("#main").focus();
      toast("Shared demo reset. The dashboard now shows the backend’s starting state.");
    } catch (error) {
      if (error.status === 401) return;
      $("#reset-error").textContent = error.message;
      $("#reset-error").hidden = false;
      dialog.querySelectorAll("button, input").forEach(element => { element.disabled = false; });
      $("#reset-consent").checked = false;
      $("#reset-confirm").disabled = true;
      $("#reset-confirm").innerHTML = `${icon("reset")}Retry shared reset`;
    } finally { state.busy = false; }
  }

  async function logout() {
    if (state.busy) return;
    state.busy = true;
    const button = $('[data-action="logout"]');
    if (button) button.disabled = true;
    try {
      await api("/logout", { method: "POST", headers: csrfHeaders() });
      state.sessionGeneration++;
      state.data = null;
      state.pending = null;
      state.session = null;
      clearFilters();
      await session();
      renderLogin("You’re signed out. Choose an account and authenticate to continue.");
    } catch (error) {
      if (error.status === 401) return;
      if (!state.session?.authenticated) {
        renderLogin("Signed out, but the fresh sign-in session could not be loaded. Submit sign-in to reconnect.");
      } else { toast("Could not sign out. " + error.message); }
    } finally {
      state.busy = false;
      if (button?.isConnected) button.disabled = false;
    }
  }

  function clearFilters() {
    Object.assign(state, { orderSearch: "", orderStatus: "all", orderValue: "all", orderCategory: "all", paymentSearch: "", paymentValue: "all", eventSearch: "" });
  }

  function navigate(view) {
    if (!navNames[view]) return;
    state.view = view;
    renderApp();
    $("#main")?.focus();
    window.scrollTo({ top: 0, behavior: "instant" });
  }

  async function refresh() {
    if (state.refreshing) return;
    state.refreshing = true;
    document.querySelectorAll('[data-action="refresh"]').forEach(button => { button.disabled = true; });
    try {
      await refreshDashboard();
      if (state.session?.authenticated) toast("Live records refreshed.");
    } catch (error) {
      if (error.status !== 401) state.pageError = error.message;
    } finally {
      state.refreshing = false;
      if (state.session?.authenticated) renderApp();
    }
  }

  function updateOrderResults() {
    $("#orders-body").innerHTML = orderRows();
    $("#order-count").textContent = orderCount();
    announce(`${orderMatches().length} matching orders.`);
  }

  function updatePaymentResults() {
    $("#payments-body").innerHTML = paymentRows();
    $("#payment-count").textContent = `${paymentMatches().length} of ${state.data.payments.length} payments`;
    announce(`${paymentMatches().length} matching payments.`);
  }

  function toggleMenu(open) {
    const sidebar = $("#sidebar");
    if (!sidebar) return;
    sidebar.classList.toggle("open", open);
    $(".mobile-shade").classList.toggle("visible", open);
    $('[data-action="menu"]').setAttribute("aria-expanded", String(open));
    if (open) $(".nav-link.active", sidebar)?.focus();
    else $('[data-action="menu"]').focus();
  }

  document.addEventListener("submit", event => {
    if (event.target.id === "login-form") { event.preventDefault(); void login(event.target); }
    if (event.target.id === "refund-form") { event.preventDefault(); reviewRefund(); }
  });

  document.addEventListener("click", event => {
    const button = event.target.closest("[data-action]");
    if (!button || button.disabled) return;
    const action = button.dataset.action;
    if (action === "theme") {
      const theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
      document.documentElement.dataset.theme = theme;
      document.querySelectorAll('[data-action="theme"]').forEach(element => { element.innerHTML = icon(theme === "dark" ? "sun" : "moon"); });
      announce(`${theme === "dark" ? "Dark" : "Light"} theme enabled.`);
    } else if (action === "boot") void boot();
    else if (action === "account") {
      $("#username").value = button.dataset.username;
      document.querySelectorAll(".account-card").forEach(card => card.classList.toggle("selected", card === button));
      $("#password").value = "";
      $("#password").focus();
    } else if (action === "password") {
      const field = $("#password");
      const show = field.type === "password";
      field.type = show ? "text" : "password";
      button.setAttribute("aria-label", show ? "Hide password" : "Show password");
      button.setAttribute("aria-pressed", String(show));
    } else if (action === "navigate") navigate(button.dataset.view);
    else if (action === "refresh") void refresh();
    else if (action === "new-refund") startRefund();
    else if (action === "order-refund") startRefund(button.dataset.id);
    else if (action === "dialog-close") closeDialog();
    else if (action === "discard-request" && [400, 409].includes(state.pending?.lastStatus)) {
      state.pending = null;
      closeDialog();
      void refresh();
    }
    else if (action === "refund-back" && !state.pending?.attempted) renderCompose();
    else if (action === "send-refund") void sendRefund();
    else if (action === "payment") showPayment(button.dataset.id);
    else if (action === "order-payment" || action === "refund-payment") {
      const payment = state.data.payments.find(item => action === "order-payment" ? item.orderId === button.dataset.id : item.refundId === button.dataset.id);
      if (payment) showPayment(payment.id);
      else toast("No matching payment in the fetched records. Refresh to check the shared state.");
    } else if (action === "receipt-ledger") { closeDialog(); navigate("payments"); }
    else if (action === "high-orders") {
      clearFilters(); state.orderStatus = "PAID"; state.orderValue = "high"; navigate("orders");
    } else if (action === "clear-orders") {
      Object.assign(state, { orderSearch: "", orderStatus: "all", orderValue: "all", orderCategory: "all" });
      renderApp(); $("#order-search").focus();
    } else if (action === "clear-payments") {
      state.paymentSearch = ""; state.paymentValue = "all"; renderApp(); $("#payment-search").focus();
    } else if (action === "info") showInfo();
    else if (action === "reset") showReset();
    else if (action === "reset-confirm") void resetDemo();
    else if (action === "logout") void logout();
    else if (action === "menu") toggleMenu(!$("#sidebar").classList.contains("open"));
    else if (action === "menu-close") toggleMenu(false);
  });

  document.addEventListener("input", event => {
    const input = event.target;
    if (input.id === "order-search") { state.orderSearch = input.value; updateOrderResults(); }
    else if (input.id === "payment-search") { state.paymentSearch = input.value; updatePaymentResults(); }
    else if (input.id === "event-search") {
      state.eventSearch = input.value;
      $("#activity-rows").innerHTML = activityRows();
      $("#event-count").textContent = `${eventMatches().length} recorded events shown`;
      announce(`${eventMatches().length} matching events.`);
    } else if (input.id === "refund-order-search") $("#order-options").innerHTML = optionRows(input.value);
    else if (input.id === "refund-notes") {
      state.pending.notes = input.value;
      $("#notes-count").textContent = `${input.value.length} / 500 characters`;
    } else if (input.id === "username") {
      document.querySelectorAll(".account-card").forEach(card => card.classList.toggle("selected", card.dataset.username === input.value.trim().toLowerCase()));
    }
  });

  document.addEventListener("change", event => {
    const input = event.target;
    const orderFilters = { "order-status": "orderStatus", "order-value": "orderValue", "order-category": "orderCategory" };
    if (orderFilters[input.id]) { state[orderFilters[input.id]] = input.value; updateOrderResults(); }
    else if (input.id === "payment-value") { state.paymentValue = input.value; updatePaymentResults(); }
    else if (input.name === "refund-order") {
      state.pending.orderId = input.value;
      $("#selected-amount").innerHTML = selectedAmount();
      $("#refund-error").hidden = true;
    } else if (input.id === "refund-reason") state.pending.reason = input.value;
    else if (input.id === "send-consent") { state.pending.confirmed = input.checked; $("#send-refund").disabled = !input.checked; }
    else if (input.id === "reset-consent") $("#reset-confirm").disabled = !input.checked;
  });

  dialog.addEventListener("cancel", event => {
    event.preventDefault();
    closeDialog();
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && !dialog.open && $("#sidebar")?.classList.contains("open")) toggleMenu(false);
    if (event.key === "Tab" && !dialog.open && $("#sidebar")?.classList.contains("open") && window.matchMedia("(max-width: 740px)").matches) {
      const items = [...$("#sidebar").querySelectorAll("button:not(:disabled)")].filter(element => element.getClientRects().length);
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
    if (event.key !== "Tab" || !dialog.open) return;
    const focusable = [...dialog.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]')].filter(element => element.getClientRects().length);
    if (!focusable.length) { event.preventDefault(); $("#dialog-title")?.focus(); return; }
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || !focusable.includes(document.activeElement))) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && (document.activeElement === last || !focusable.includes(document.activeElement))) { event.preventDefault(); first.focus(); }
  });

  void boot();
})();
