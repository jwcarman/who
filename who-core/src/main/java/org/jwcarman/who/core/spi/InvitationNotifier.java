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
package org.jwcarman.who.core.spi;

import org.jwcarman.who.core.domain.Invitation;

/**
 * SPI for sending invitation notifications.
 * Application must provide a bean implementing this interface.
 */
public interface InvitationNotifier {

    /**
     * Send invitation email to user.
     * Application constructs the acceptance URL based on its own deployment.
     *
     * @param invitation the invitation details (includes token, email, expiration, role)
     */
    void sendInvitation(Invitation invitation);
}
