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
package org.jwcarman.who.core.service.impl;

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.repository.ContactMethodRepository;
import org.jwcarman.who.core.repository.UserRepository;
import org.jwcarman.who.core.service.ContactMethodService;
import org.jwcarman.who.core.spi.ContactConfirmationNotifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core implementation of ContactMethodService with business logic.
 */
public class DefaultContactMethodService implements ContactMethodService {

    private final ContactMethodRepository repository;
    private final UserRepository userRepository;
    private final ContactConfirmationNotifier notifier;

    public DefaultContactMethodService(ContactMethodRepository repository,
                                     UserRepository userRepository,
                                     ContactConfirmationNotifier notifier) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notifier = notifier;
    }

    @Override
    public ContactMethod createUnverified(UUID userId, ContactType type, String value) {
        ContactMethod contactMethod = ContactMethod.createUnverified(userId, type, value);
        ContactMethod saved = repository.save(contactMethod);

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        notifier.notifyContactAdded(saved, user);

        return saved;
    }

    @Override
    public ContactMethod createVerified(UUID userId, ContactType type, String value) {
        ContactMethod contactMethod = ContactMethod.createVerified(userId, type, value);
        ContactMethod saved = repository.save(contactMethod);

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        notifier.notifyContactAdded(saved, user);

        return saved;
    }

    @Override
    public ContactMethod markVerified(UUID contactMethodId) {
        ContactMethod contactMethod = repository.findById(contactMethodId)
            .orElseThrow(() -> new IllegalArgumentException("Contact method not found: " + contactMethodId));

        ContactMethod verified = contactMethod.markVerified();
        return repository.save(verified);
    }

    @Override
    public List<ContactMethod> findByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    @Override
    public Optional<ContactMethod> findByUserIdAndType(UUID userId, ContactType type) {
        return repository.findByUserIdAndType(userId, type);
    }

    @Override
    public void delete(UUID contactMethodId) {
        // Validate contact method exists
        if (!repository.findById(contactMethodId).isPresent()) {
            throw new IllegalArgumentException("Contact method not found: " + contactMethodId);
        }

        repository.deleteById(contactMethodId);
    }
}
