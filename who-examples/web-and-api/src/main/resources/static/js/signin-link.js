import { redeemLinkFromLocation } from '/js/who.js';

function next() {
  try {
    const saved = localStorage.getItem('who-next');
    localStorage.removeItem('who-next');
    if (!saved) return '/';
    // only a page of this site: '//evil.example' and '/\evil.example' resolve to another origin
    const url = new URL(saved, location.origin);
    return url.origin === location.origin ? url.pathname + url.search + url.hash : '/';
  } catch {
    return '/';
  }
}

if (await redeemLinkFromLocation()) {
  location.assign(next());
} else {
  document.getElementById('failed').hidden = false;
}
