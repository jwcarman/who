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

export function humanCheck(action) {
  if (!siteKey) return Promise.resolve(undefined);
  return new Promise((resolve, reject) => {
    const widget = turnstile.render('#human-check', {
      sitekey: siteKey,
      action,
      appearance: 'interaction-only',
      callback: token => {
        resolve(token);
        setTimeout(() => turnstile.remove(widget));
      },
      'error-callback': () => {
        reject(new Error('Turnstile could not check this browser'));
        setTimeout(() => turnstile.remove(widget));
      },
    });
  });
}
