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
package org.jwcarman.who.enrollment;

/**
 * Lifecycle status of an {@link EnrollmentToken}.
 *
 * <p>Expiry is not modeled as a status — it is determined at runtime by comparing
 * {@link EnrollmentToken#expiresAt()} to {@link java.time.Instant#now()}.
 */
public enum EnrollmentTokenStatus {
    /** Token has been issued and not yet redeemed or revoked. */
    PENDING,
    /** Token was successfully used to link a credential to an identity. */
    REDEEMED,
    /** Token was explicitly invalidated before redemption. */
    REVOKED
}
