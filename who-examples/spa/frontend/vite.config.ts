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
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// What Spring answers, rather than Vite. The browser sees one origin, localhost:5173, so the
// session cookie, the CSRF cookie and same-origin fetch all work as they do on 8080.
const spring = 'http://localhost:8080';
const forwarded = ['/api', '/who', '/ott', '/login', '/logout', '/webauthn', '/oauth2'];

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // changeOrigin stays false: Spring must see the browser's Host, localhost:5173, so the
    // provider's return address ({baseUrl}/login/oauth2/code/...) points back here.
    proxy: Object.fromEntries(forwarded.map(path => [path, { target: spring }]))
  },
  build: {
    // into the jar: Spring serves it from the classpath's static/
    outDir: '../target/classes/static',
    emptyOutDir: true
  }
});
