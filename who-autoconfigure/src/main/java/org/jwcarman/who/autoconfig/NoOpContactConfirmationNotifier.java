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
package org.jwcarman.who.autoconfig;

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.spi.ContactConfirmationNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op implementation of ContactConfirmationNotifier that logs at DEBUG level.
 * Applications should provide their own implementation for production use.
 */
class NoOpContactConfirmationNotifier implements ContactConfirmationNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoOpContactConfirmationNotifier.class);

    @Override
    public void notifyContactAdded(ContactMethod contact, User user) {
        log.debug("Contact method added - no notification sent (using no-op notifier): userId={}, contactType={}",
                user.id(), contact.type());
    }
}
