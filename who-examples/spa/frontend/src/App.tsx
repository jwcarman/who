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
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, Route, Routes, useLocation, useNavigate } from 'react-router';
import { getJson, SignInRequired, type Me } from './api';
import { signOut } from './who';
import { Board } from './pages/Board';
import { Members } from './pages/Members';
import { Profile } from './pages/Profile';
import { SignIn } from './pages/SignIn';
import { SignInLink } from './pages/SignInLink';

type MeState = { me: Me; refresh: () => Promise<void> };
const MeContext = createContext<MeState | null>(null);

export function useMe(): MeState {
  const state = useContext(MeContext);
  if (!state) throw new Error('useMe outside a signed-in page');
  return state;
}

// The routes match ClientRoutes and who.security.public-paths on the server.
export function App() {
  return (
    <Routes>
      <Route path="/signin" element={<SignIn />} />
      <Route path="/signin/link" element={<SignInLink />} />
      <Route path="/" element={<SignedIn><Board /></SignedIn>} />
      <Route path="/members" element={<SignedIn><Members /></SignedIn>} />
      <Route path="/profile" element={<SignedIn><Profile /></SignedIn>} />
    </Routes>
  );
}

/**
 * A page for someone signed in: loads /api/me, and sends a stranger to /signin. Who the person is
 * comes from the server on every load; nothing about the session is kept in the browser.
 */
function SignedIn({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const navigate = useNavigate();
  const location = useLocation();

  const refresh = useCallback(async () => {
    try {
      setMe(await getJson<Me>('/api/me'));
    } catch (e) {
      if (e instanceof SignInRequired) navigate('/signin', { replace: true });
      else throw e;
    }
  }, [navigate]);

  useEffect(() => { void refresh(); }, [refresh, location.pathname]);

  if (!me) return null;
  return (
    <MeContext.Provider value={{ me, refresh }}>
      <Header me={me} />
      <main>{children}</main>
    </MeContext.Provider>
  );
}

function Header({ me }: { me: Me }) {
  const navigate = useNavigate();
  return (
    <header>
      <span className="brand">Who Tasks</span>
      <Link to="/">Board</Link>
      {/* shown or hidden for convenience only: the server checks member.manage on every call */}
      {me.permissions.includes('member.manage') && <Link to="/members">Members</Link>}
      <Link to="/profile">Profile</Link>
      <button type="button" className="link" onClick={async () => {
        await signOut();
        navigate('/signin');
      }}>Sign out</button>
    </header>
  );
}
