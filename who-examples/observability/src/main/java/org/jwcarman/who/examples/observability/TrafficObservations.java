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
package org.jwcarman.who.examples.observability;

import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Marks every HTTP request's metrics and span with {@code traffic}: {@code synthetic} when the
 * traffic generator sent it ({@value #HEADER}: {@code synthetic}), otherwise {@code interactive}.
 * The dashboard uses it to show either. who's own meters are not marked: their tags are only who's
 * enums.
 */
@Component
class TrafficObservations implements ObservationFilter {

  /** The request header the traffic generator sends. */
  static final String HEADER = "X-Traffic";

  static final String SYNTHETIC = "synthetic";
  static final String INTERACTIVE = "interactive";

  @Override
  public Observation.Context map(Observation.Context context) {
    if (context instanceof ServerRequestObservationContext request) {
      String traffic =
          SYNTHETIC.equals(request.getCarrier().getHeader(HEADER)) ? SYNTHETIC : INTERACTIVE;
      context.addLowCardinalityKeyValue(KeyValue.of("traffic", traffic));
    }
    return context;
  }
}
