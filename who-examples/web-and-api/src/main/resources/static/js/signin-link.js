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
