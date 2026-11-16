import { redeemLinkFromLocation } from '/js/who.js';

function next() {
  try {
    const saved = localStorage.getItem('who-next');
    localStorage.removeItem('who-next');
    return saved && saved.startsWith('/') && !saved.startsWith('//') ? saved : '/';
  } catch {
    return '/';
  }
}

if (await redeemLinkFromLocation()) {
  location.assign(next());
} else {
  document.getElementById('failed').hidden = false;
}
