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
// The calls to who, through @jwcarman/who-client: it handles CSRF, the passkey encoding and the
// fragment tokens, and answers results the components switch on. who is at /who, the default.
import { type BrowserSessionRequired, createWhoClient, type SignInRequired } from '@jwcarman/who-client';

export const who = createWhoClient();

// A call that needs a recent sign-in from this browser failed: say which kind. A browser session
// is refused only when the request carried another credential beside its cookie, which this page
// never sends.
export function signInAgain(reason: SignInRequired | BrowserSessionRequired, tooLong: string): string {
  if (reason === 'browser-session-required') return 'Only this browser on its own can do that.';
  return reason === 'signed-out' ? 'You are signed out. Sign in again.' : tooLong;
}
