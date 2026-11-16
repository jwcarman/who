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
import { createWhoClient, type SignInRequired } from '@jwcarman/who-client';

export const who = createWhoClient();

// A call that needs a recent sign-in failed with a 401: say which kind.
export function signInAgain(reason: SignInRequired, tooLong: string): string {
  return reason === 'signed-out' ? 'You are signed out. Sign in again.' : tooLong;
}
