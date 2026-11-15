import { redeemLinkFromLocation } from '/js/who.js';

if (await redeemLinkFromLocation()) {
  location.assign('/');
} else {
  document.getElementById('failed').hidden = false;
}
