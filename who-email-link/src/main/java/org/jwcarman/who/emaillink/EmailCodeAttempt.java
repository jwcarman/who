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
package org.jwcarman.who.emaillink;

import java.util.UUID;

/**
 * A counted attempt at a sign-in code: the credential it belongs to and the stored code hash to
 * compare the submitted code against.
 *
 * @param credentialId the email credential
 * @param codeHash the stored HMAC hash of the code
 */
public record EmailCodeAttempt(UUID credentialId, String codeHash) {}
