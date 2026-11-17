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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;

/**
 * The OpenTelemetry jars on this example's classpath work together: a log record goes through the
 * SDK to an exporter. The Logback appender's version must match the OpenTelemetry version Spring
 * Boot manages; when it does not, the SDK fails on its export thread, where nothing else would
 * notice.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OpenTelemetryLogsTest {

  @Test
  void a_log_record_reaches_the_exporter() {
    List<LogRecordData> exported = new CopyOnWriteArrayList<>();
    LogRecordExporter exporter =
        new LogRecordExporter() {
          @Override
          public CompletableResultCode export(Collection<LogRecordData> logs) {
            exported.addAll(logs);
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };

    try (SdkLoggerProvider provider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build()) {
      provider.get("who-example").logRecordBuilder().setBody("hello").emit();
    }

    assertThat(exported)
        .singleElement()
        .satisfies(log -> assertThat(log.getBodyValue()).isNotNull());
  }
}
