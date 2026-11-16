import { signOut } from '/js/who.js';

document.getElementById('sign-out')?.addEventListener('click', async () => {
  await signOut();
  location.assign('/signin');
});
