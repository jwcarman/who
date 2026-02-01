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

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.User;

/**
 * SPI for notifying users when contact methods are added.
 * Sends "if this wasn't you" security notifications.
 * Required if notify-on-contact-add is true.
 */
public interface ContactConfirmationNotifier {

    /**
     * Notify user that a contact method was added to their account.
     * User can revoke if this wasn't them.
     *
     * @param contact the contact method that was added
     * @param user the user it was added to
     */
    void notifyContactAdded(ContactMethod contact, User user);
}
