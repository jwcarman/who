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
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { who } from '../who-client';

// Where who.email-link.link-base-url points. The token is in the fragment, so it never reaches a
// server log; who-client takes it out of the address bar and posts it.
export function SignInLink() {
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  const redeemed = useRef(false); // a link works once: never post it twice (StrictMode runs effects twice)

  useEffect(() => {
    if (redeemed.current) return;
    redeemed.current = true;
    void who.email.signInFromLocation().then(result =>
      (result?.ok ? navigate('/', { replace: true }) : setFailed(true)));
  }, [navigate]);

  return (
    <main>
      <h1>Signing you in…</h1>
      {failed && (
        <section>
          <p>This link did not work. It may have been used already, or be more than a few minutes old.</p>
          <Link className="button" to="/signin">Get a new one</Link>
        </section>
      )}
    </main>
  );
}
