'use strict';
const $ = id => document.getElementById(id);
const state = { user: null, register: false, route: null, requestId: null, cancelPnr: null, pendingRoute: null, searchVersion: 0 };
const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
let toastTimer;

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}
function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 5000); }
function localDate(date = new Date()) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function readableDate(value) { return new Date(`${value}T12:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }); }
function time(value) { return String(value).slice(0, 5); }
function duration(minutes) { return `${Math.floor(minutes / 60)}h ${Math.round(minutes % 60)}m`; }
function empty(container, title, message) {
  const box = node('div', 'empty-state'); box.append(node('span', 'empty-icon', '↗'), node('h3', '', title), node('p', '', message)); container.replaceChildren(box);
}
async function api(path, data) {
  const headers = {};
  if (data !== undefined) { headers['Content-Type'] = 'application/json'; headers['X-Requested-With'] = 'railway-web'; if (state.user) headers['X-CSRF-Token'] = state.user.csrfToken; }
  let response;
  try { response = await fetch(`/api/${path}`, { method: data === undefined ? 'GET' : 'POST', headers, credentials: 'same-origin', body: data === undefined ? undefined : JSON.stringify(data) }); }
  catch (error) { throw new Error('Could not reach the server. Check your connection and try again.'); }
  let result;
  try { result = await response.json(); } catch (error) { throw new Error('The server returned an unexpected response.'); }
  if (!response.ok || !result.ok) {
    if (response.status === 401 && !['login', 'register'].includes(path)) { state.user = null; updateAccount(); }
    const error = new Error(result.error?.message || 'Request failed.'); error.code = result.error?.code; throw error;
  }
  return result.data;
}
function updateAccount() {
  $('auth-button').hidden = !!state.user; $('logout-button').hidden = !state.user; $('user-name').hidden = !state.user;
  $('user-name').textContent = state.user ? state.user.name : '';
}
function view(which) {
  $('search-view').hidden = which !== 'search'; $('bookings-view').hidden = which !== 'bookings';
  $('nav-search').classList.toggle('active', which === 'search'); $('nav-bookings').classList.toggle('active', which === 'bookings');
}
function openAuth() { $('auth-error').textContent = ''; $('password').value = ''; $('auth-dialog').showModal(); }
function authMode() {
  $('name-field').hidden = !state.register; $('display-name').required = state.register;
  $('auth-title').textContent = state.register ? 'Your journey starts here.' : 'Make yourself at home.';
  $('auth-eyebrow').textContent = state.register ? 'NICE TO MEET YOU' : 'WELCOME BACK';
  $('auth-submit').textContent = state.register ? 'Create account ↗' : 'Sign in ↗';
  $('auth-switch-label').textContent = state.register ? 'Already have an account?' : 'New here?';
  $('auth-switch').textContent = state.register ? 'Sign in' : 'Create an account';
  $('password').autocomplete = state.register ? 'new-password' : 'current-password';
  $('password').minLength = state.register ? 10 : 1; $('auth-error').textContent = '';
}
function openBooking(route) {
  if (!state.user) { state.pendingRoute = route; openAuth(); return; }
  state.route = route; state.requestId = crypto.randomUUID();
  $('booking-summary').textContent = `Train ${route.train} · ${route.source} → ${route.destination} · ${readableDate(route.date)}`;
  $('passenger-names').value = state.user.name;
  const options = [...$('travel-class').options];
  for (const option of options) { const inventory = route.availability.find(item => item.class === option.value); option.disabled = !inventory || inventory.available === 0; }
  const available = options.find(option => !option.disabled);
  if (!available) { toast('This train has no available seats. Search again for updated availability.'); return; }
  $('travel-class').value = available.value; $('book-error').textContent = ''; $('book-dialog').showModal();
}
function renderRoutes(routes) {
  const container = $('results'); container.replaceChildren();
  $('result-count').textContent = `${routes.length} ${routes.length === 1 ? 'journey' : 'journeys'} found`;
  if (!routes.length) { empty(container, 'No journeys on this date.', 'Try another date or a different pair of stations.'); return; }
  for (const route of routes) {
    const direct = route.type === 'DIRECT';
    const card = node('article', 'route-card');
    const identity = node('div');
    identity.append(node('div', 'route-number', direct ? `TRAIN ${route.train}` : `TRAINS ${route.train1} / ${route.train2}`), node('div', 'route-title', direct ? 'A straight connection' : `Via ${route.transferStation}`), node('div', 'route-detail'));
    identity.lastChild.append(node('span', `badge ${direct ? '' : 'transfer'}`, direct ? 'Direct' : '1 change'));
    const departure = node('div'); departure.append(node('div', 'route-time', time(route.departureTime)), node('div', 'route-station', route.source), node('div', 'route-detail', readableDate(route.date)));
    const arrival = node('div'); arrival.append(node('div', 'route-time', time(route.arrivalTime)), node('div', 'route-station', route.destination), node('div', 'route-detail', `${duration(route.durationMinutes)} · ${days[route.arrivalDay]} arrival`));
    const actions = node('div', 'route-actions');
    if (direct) {
      const seats = route.availability || []; const available = seats.reduce((sum, item) => sum + item.available, 0);
      actions.append(node('div', 'seat-label', seats.length ? seats.map(item => `${item.class === 'SL' ? 'Sleeper' : 'AC'} ${item.available}`).join(' · ') : 'Booking not open'));
      const button = node('button', 'button primary', available > 0 ? 'Choose seats ↗' : seats.length ? 'Sold out' : 'Not available');
      button.type = 'button'; button.disabled = available === 0; button.addEventListener('click', () => openBooking(route)); actions.append(button);
    } else actions.append(node('div', 'seat-label', `${Math.round(route.waitMinutes)} min connection`), node('span', 'subtle', 'Book each train separately'));
    card.append(identity, departure, arrival, actions); container.append(card);
  }
}
async function search() {
  const version = ++state.searchVersion; const button = $('search-form').querySelector('button[type=submit]'); button.disabled = true;
  $('results').replaceChildren(node('div', 'loading', 'Finding your next departure…'));
  try {
    const routes = await api('search', { source: $('source').value, destination: $('destination').value, date: $('journey-date').value });
    if (version === state.searchVersion) renderRoutes(routes);
  } catch (error) { if (version === state.searchVersion) { empty($('results'), 'We couldn’t find your journey.', error.message); $('result-count').textContent = ''; } }
  finally { if (version === state.searchVersion) button.disabled = false; }
}
async function loadBookings() {
  view('bookings');
  if (!state.user) { empty($('bookings'), 'Your tickets are waiting here.', 'Sign in to view and manage your journeys.'); openAuth(); return; }
  $('bookings').replaceChildren(node('div', 'loading', 'Gathering your journeys…'));
  try {
    const tickets = await api('bookings'); const container = $('bookings'); container.replaceChildren();
    if (!tickets.length) { empty(container, 'Your first journey is ahead.', 'Find a train and save a seat. Your tickets will appear here.'); return; }
    for (const ticket of tickets) {
      const card = node('article', 'ticket-card'); const heading = node('div', 'ticket-head'); const identity = node('div');
      identity.append(node('h3', '', `Train ${ticket.train}`), node('div', 'ticket-meta', `${readableDate(ticket.date)} · ${ticket.class === 'SL' ? 'Sleeper' : 'AC'} · ${ticket.passengers.length} ${ticket.passengers.length === 1 ? 'passenger' : 'passengers'}`), node('div', 'ticket-meta', `PNR ${ticket.pnr}`));
      const actions = node('div'); actions.append(node('span', `badge ${ticket.status === 'CANCELLED' ? 'cancelled' : ''}`, ticket.status));
      if (ticket.status === 'CONFIRMED') { const cancel = node('button', 'button small ghost', 'Cancel ticket'); cancel.type = 'button'; cancel.addEventListener('click', () => { state.cancelPnr = ticket.pnr; $('cancel-error').textContent = ''; $('cancel-dialog').showModal(); }); actions.append(document.createTextNode(' '), cancel); }
      heading.append(identity, actions); const people = node('div', 'passenger-list');
      for (const passenger of ticket.passengers) { const item = node('div', 'passenger-item', passenger.name); item.append(node('span', '', `Coach ${passenger.coach} · Berth ${passenger.berth} · ${passenger.berthType}`)); people.append(item); }
      card.append(heading, people); container.append(card);
    }
  } catch (error) { empty($('bookings'), 'We couldn’t load your tickets.', error.message); }
}

$('search-form').addEventListener('submit', event => { event.preventDefault(); search(); });
$('swap').addEventListener('click', () => { const source = $('source').value; $('source').value = $('destination').value; $('destination').value = source; });
$('nav-search').addEventListener('click', () => view('search'));
$('nav-bookings').addEventListener('click', loadBookings);
$('auth-button').addEventListener('click', openAuth);
$('auth-switch').addEventListener('click', () => { state.register = !state.register; authMode(); });
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
$('auth-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = $('auth-submit'); if (button.disabled) return; button.disabled = true; $('auth-error').textContent = '';
  try {
    const credentials = { email: $('email').value, password: $('password').value };
    if (state.register) { await api('register', { ...credentials, name: $('display-name').value }); state.register = false; authMode(); toast('Account created. Sign in with your new password.'); return; }
    state.user = await api('login', credentials); $('auth-dialog').close(); $('password').value = ''; updateAccount(); toast(`Welcome, ${state.user.name}.`);
    if (state.pendingRoute) { const route = state.pendingRoute; state.pendingRoute = null; openBooking(route); }
    else if (!$('bookings-view').hidden) await loadBookings();
  } catch (error) { $('auth-error').textContent = error.message; }
  finally { button.disabled = false; }
});
$('logout-button').addEventListener('click', async () => {
  try { await api('logout', {}); state.user = null; updateAccount(); view('search'); toast('You’re signed out.'); }
  catch (error) { toast(error.message); }
});
$('book-form').addEventListener('input', () => state.requestId = crypto.randomUUID());
$('book-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = $('book-submit'); if (button.disabled) return; button.disabled = true; $('book-error').textContent = '';
  try {
    const passengers = $('passenger-names').value.split(/\r?\n/).map(name => name.trim()).filter(Boolean);
    if (!passengers.length || passengers.length > 100 || passengers.some(name => name.length > 100)) throw new Error('Enter 1 to 100 names, each no longer than 100 characters.');
    await api('book', { train: state.route.train, date: state.route.date, class: $('travel-class').value, passengers, requestId: state.requestId });
    $('book-dialog').close(); toast('Your seats are confirmed. Have a great journey.'); await loadBookings();
  } catch (error) { $('book-error').textContent = error.message; }
  finally { button.disabled = false; }
});
$('cancel-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = $('cancel-form').querySelector('button[type=submit]'); if (button.disabled) return; button.disabled = true;
  try { await api('cancel', { pnr: state.cancelPnr }); $('cancel-dialog').close(); toast('Ticket cancelled. Your seats are available again.'); await loadBookings(); }
  catch (error) { $('cancel-error').textContent = error.message; }
  finally { button.disabled = false; }
});
$('admin-button').addEventListener('click', () => { if (!state.user) { openAuth(); return; } $('admin-error').textContent = ''; $('admin-token').value = ''; $('admin-date').value = $('journey-date').value; $('admin-dialog').showModal(); });
$('admin-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = $('admin-form').querySelector('button[type=submit]'); if (button.disabled) return; button.disabled = true;
  try { await api('admin/release', { train: Number($('admin-train').value), date: $('admin-date').value, acCoaches: Number($('admin-ac').value), sleeperCoaches: Number($('admin-sl').value), adminToken: $('admin-token').value }); $('admin-token').value = ''; $('admin-dialog').close(); toast('Departure is now open for booking.'); }
  catch (error) { $('admin-error').textContent = error.message; }
  finally { button.disabled = false; }
});
async function initialize() {
  const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1); $('journey-date').value = localDate(tomorrow); authMode();
  try { const stations = await api('stations'); for (const station of stations) { const option = node('option'); option.value = station; $('stations').append(option); } }
  catch (error) { toast(error.message); }
  try { state.user = await api('me'); } catch (error) { state.user = null; } updateAccount();
}
initialize();
