/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// The turnstile profile's human check.
// A fresh Turnstile token for one request, made for that endpoint's action; without a site key,
// who checks nothing and the page sends no token. Each token works once.
const siteKey = document.body.dataset.turnstileSiteKey;

let shown;

export function humanCheck(action) {
  if (!siteKey) return Promise.resolve(undefined);
  // one widget at a time: drop the last one, which stays up a moment to show its result
  if (shown !== undefined) turnstile.remove(shown);
  return new Promise((resolve, reject) => {
    const widget = turnstile.render('#human-check', {
      sitekey: siteKey,
      action,
      // shown every time so the check is visible in this example; a real app would usually use
      // 'interaction-only', which shows it only when Cloudflare wants the person to act
      appearance: 'always',
      callback: token => {
        resolve(token);
        shown = widget;
        setTimeout(() => {
          if (shown === widget) {
            turnstile.remove(widget);
            shown = undefined;
          }
        }, 2000);
      },
      'error-callback': () => {
        reject(new Error('Turnstile could not check this browser'));
        setTimeout(() => turnstile.remove(widget));
      },
    });
  });
}
