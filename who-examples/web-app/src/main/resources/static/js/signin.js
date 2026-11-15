import {
  confirmLink, confirmSignUp, ensureCsrfCookie, pendingSignUp, requestLink, signInWithCode,
  signInWithPasskey
} from '/js/who.js';

const $ = id => document.getElementById(id);

function showError(text) {
  $('error').textContent = text;
  $('error').hidden = false;
}

await ensureCsrfCookie();

// --- Email link and code ---
$('email-form').addEventListener('submit', async e => {
  e.preventDefault();
  await requestLink($('email').value);
  $('code-step').hidden = false;
  $('code').focus();
});

$('code-form').addEventListener('submit', async e => {
  e.preventDefault();
  if (await signInWithCode($('email').value, $('code').value)) {
    location.assign('/');
  } else {
    showError('That code did not work. Check it, or ask for a new email.');
  }
});

// --- Passkey ---
$('passkey').addEventListener('click', async () => {
  try {
    if (await signInWithPasskey()) {
      location.assign('/');
    } else {
      showError('That passkey did not sign you in.');
    }
  } catch {
    showError('No passkey was used.');
  }
});

// --- Coming back from a provider ---
const error = new URLSearchParams(location.search).get('error');
if (error === 'already-linked') {
  showError('That account is already linked to someone else. Nothing was changed.');
} else if (error === 'signin-failed') {
  showError('Signing in with the provider did not work. Try again, or sign in another way.');
} else if (error === 'no-account') {
  const pending = await pendingSignUp();
  if (pending) {
    // who names the provider by its registration id; show the name its button shows.
    pending.provider = document.querySelector(`[data-provider="${CSS.escape(pending.provider)}"]`)
      ?.dataset.providerName ?? pending.provider;
  }
  if (!pending) {
    showError('That took too long. Sign in with the provider again.');
  } else if (pending.canLink) {
    offer(`Link ${pending.provider}?`,
      `Link the ${pending.provider} account ${pending.displayName} to your account, so it can sign you in.`,
      'Link it', async () => {
        const status = await confirmLink();
        if (status === 200) location.assign('/profile');
        else if (status === 401) showError('Sign in again, then link the account within a few minutes.');
        else if (status === 409) showError('That account is already linked to someone else.');
        else showError('That took too long. Start again from your profile.');
      });
  } else if (pending.canSignUp) {
    offer(`New here?`,
      `No account uses the ${pending.provider} account ${pending.displayName} yet. Create one?`,
      'Create my account', async () => {
        const status = await confirmSignUp();
        if (status === 200) location.assign('/');
        else if (status === 403) showError(`Sign-up with ${pending.provider} is not open. Ask an admin to invite you, then link it from your profile.`);
        else showError('That took too long. Sign in with the provider again.');
      });
  } else {
    showError(`No account uses the ${pending.provider} account ${pending.displayName}.`);
  }
}

function offer(title, text, action, onConfirm) {
  $('pending-title').textContent = title;
  $('pending-text').textContent = text;
  $('pending-confirm').textContent = action;
  $('pending-confirm').addEventListener('click', onConfirm, { once: true });
  $('pending').hidden = false;
}
