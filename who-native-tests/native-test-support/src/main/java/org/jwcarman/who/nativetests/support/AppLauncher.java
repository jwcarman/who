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
package org.jwcarman.who.nativetests.support;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts a test app on a given port: in this JVM by default, or, when the system property {@code
 * app.binary} names a file (the {@code native} profile sets it), as that native binary in its own
 * process with its output in {@code target/app.log}. Both get the same arguments.
 */
public final class AppLauncher {

  /** The system property naming the native binary. */
  public static final String BINARY_PROPERTY = "app.binary";

  static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
  static final int LOG_TAIL_LINES = 40;
  static final File APP_LOG = new File("target/app.log");

  private AppLauncher() {}

  /**
   * A port nothing listens on right now.
   *
   * @return the port
   */
  public static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Starts the app and waits until it answers HTTP.
   *
   * @param applicationClass the app's {@code @SpringBootApplication} class, used in JVM mode
   * @param port the port to listen on
   * @param arguments further command-line arguments, such as {@code --spring.datasource.url=...}
   * @return the running app
   * @throws IllegalStateException if {@code app.binary} names a missing file, or the native app
   *     exits or does not answer within 60 seconds; the message carries the end of its log
   */
  public static RunningApp start(Class<?> applicationClass, int port, List<String> arguments) {
    List<String> all = new ArrayList<>();
    all.add("--server.port=" + port);
    all.addAll(arguments);
    URI baseUri = URI.create("http://localhost:" + port);
    String binary = System.getProperty(BINARY_PROPERTY, "");
    if (binary.isBlank()) {
      ConfigurableApplicationContext context =
          SpringApplication.run(applicationClass, all.toArray(String[]::new));
      return new InProcess(baseUri, context);
    }
    File file = new File(binary);
    if (!file.canExecute()) {
      throw new IllegalStateException(
          BINARY_PROPERTY + " is " + file.getAbsolutePath() + ", which is not an executable file");
    }
    List<String> command = new ArrayList<>();
    command.add(file.getAbsolutePath());
    command.addAll(all);
    Process process;
    try {
      Files.createDirectories(APP_LOG.getAbsoluteFile().toPath().getParent());
      process =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(APP_LOG).start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    NativeProcess app = new NativeProcess(baseUri, process);
    app.awaitStartup();
    return app;
  }

  /** The last {@value #LOG_TAIL_LINES} lines of {@code target/app.log}. */
  static String logTail() {
    try {
      List<String> lines = Files.readAllLines(APP_LOG.toPath(), StandardCharsets.UTF_8);
      return String.join(
          System.lineSeparator(),
          lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
    } catch (IOException e) {
      return "(no log: " + e.getMessage() + ")";
    }
  }

  private record InProcess(URI baseUri, ConfigurableApplicationContext context)
      implements RunningApp {

    @Override
    public void close() {
      context.close();
    }
  }

  private record NativeProcess(URI baseUri, Process process) implements RunningApp {

    void awaitStartup() {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest probe =
          HttpRequest.newBuilder(baseUri.resolve("/test/events"))
              .timeout(Duration.ofSeconds(2))
              .build();
      Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
      try {
        while (Instant.now().isBefore(deadline)) {
          if (!process.isAlive()) {
            throw new IllegalStateException(
                "The native app exited with "
                    + process.exitValue()
                    + " before answering. End of "
                    + APP_LOG
                    + ":"
                    + System.lineSeparator()
                    + logTail());
          }
          try {
            client.send(probe, HttpResponse.BodyHandlers.discarding());
            return;
          } catch (IOException notYet) {
            Thread.sleep(200);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        close();
        throw new IllegalStateException("Interrupted while waiting for the native app", e);
      }
      close();
      throw new IllegalStateException(
          "The native app did not answer on "
              + baseUri
              + " within "
              + STARTUP_TIMEOUT.toSeconds()
              + " seconds. End of "
              + APP_LOG
              + ":"
              + System.lineSeparator()
              + logTail());
    }

    @Override
    public void close() {
      process.destroy();
      try {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }
}
