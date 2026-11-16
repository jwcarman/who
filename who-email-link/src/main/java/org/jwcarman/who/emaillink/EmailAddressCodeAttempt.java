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
 * A typed confirmation code counted against a request: the request and the code hash to compare the
 * typed code with.
 *
 * @param requestId the request
 * @param codeHash the stored hash of its code
 */
public record EmailAddressCodeAttempt(UUID requestId, String codeHash) {}
