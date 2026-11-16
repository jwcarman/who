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
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

// ESLint config for who-client, run from who-client (npm run lint). Plugins and the parser resolve
// from this directory's node_modules, because who-client itself has no ESLint (it needs TypeScript 6).
const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "package.json"));
const js = require("@eslint/js");
const tseslint = require("typescript-eslint");
const prettier = require("eslint-config-prettier");

// The defect rules roux holds its web code to, as errors.
const defects = [
  "no-async-promise-executor",
  "no-compare-neg-zero",
  "no-cond-assign",
  "no-constant-binary-expression",
  "no-dupe-else-if",
  "no-dupe-keys",
  "no-duplicate-case",
  "no-fallthrough",
  "no-loss-of-precision",
  "no-self-assign",
  "no-self-compare",
  "no-sparse-arrays",
  "no-unreachable",
  "no-unsafe-finally",
  "no-unsafe-negation",
  "no-unsafe-optional-chaining",
  "use-isnan",
  "@typescript-eslint/await-thenable",
  "@typescript-eslint/no-array-delete",
  "@typescript-eslint/no-base-to-string",
  "@typescript-eslint/no-floating-promises",
  "@typescript-eslint/no-for-in-array",
  "@typescript-eslint/no-implied-eval",
  "@typescript-eslint/restrict-plus-operands",
];

export default tseslint.config(
  { ignores: ["dist", "coverage", "node_modules", "lint-tools", "target"] },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    files: ["**/*.{ts,mts,cts}"],
    languageOptions: {
      parserOptions: { project: ["tsconfig.test.json"], tsconfigRootDir: process.cwd() },
    },
    rules: {
      ...Object.fromEntries(defects.map((rule) => [rule, "error"])),
      "no-restricted-syntax": [
        "error",
        { selector: "ImportNamespaceSpecifier", message: "No star imports: import each name you use" },
      ],
    },
  },
  prettier,
);
