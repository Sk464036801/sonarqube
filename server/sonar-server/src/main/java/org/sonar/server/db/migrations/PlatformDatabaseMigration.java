/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.db.migrations;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.server.platform.Platform;
import org.sonar.server.ruby.RubyBridge;

/**
 * Handles concurrency to make sure only one DB migration can run at a time.
 */
public class PlatformDatabaseMigration implements DatabaseMigration {

  private static final Logger LOGGER = Loggers.get(PlatformDatabaseMigration.class);

  private final RubyBridge rubyBridge;
  /**
   * ExecutorService implements threads management.
   */
  private final PlatformDatabaseMigrationExecutorService executorService;
  private final Platform platform;
  /**
   * This lock implements thread safety from concurrent calls of method {@link #startIt()}
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * This property acts as a semaphore to make sure at most one db migration task is created at a time.
   * <p>
   * It is set to {@code true} by the first thread to execute the {@link #startIt()} method and set to {@code false}
   * by the thread executing the db migration.
   * </p>
   */
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Status status = Status.NONE;
  @Nullable
  private Date startDate;
  @Nullable
  private Throwable failureError;

  public PlatformDatabaseMigration(RubyBridge rubyBridge, PlatformDatabaseMigrationExecutorService executorService, Platform platform) {
    this.rubyBridge = rubyBridge;
    this.executorService = executorService;
    this.platform = platform;
  }

  @Override
  public void startIt() {
    if (lock.isLocked() || this.running.get()) {
      LOGGER.trace("{}: lock is already taken or process is already running", Thread.currentThread().getName());
      return;
    }

    if (lock.tryLock()) {
      try {
        startAsynchronousDBMigration();
      } finally {
        lock.unlock();
      }
    }
  }

  /**
   * This method is not thread safe and must be external protected from concurrent executions.
   */
  private void startAsynchronousDBMigration() {
    if (this.running.get()) {
      return;
    }

    running.getAndSet(true);
    executorService.execute(new Runnable() {
      @Override
      public void run() {
        status = Status.RUNNING;
        startDate = new Date();
        failureError = null;
        try {
          upgradeDb();
          restartContainer();
          recreateWebRoutes();
          status = Status.SUCCEEDED;
        } catch (Throwable t) {
          LOGGER.error(
            "DB Migration or container restart failed. Process ended at " + startDate + " with an exception", t
            );
          status = Status.FAILED;
          failureError = t;
        } finally {
          running.getAndSet(false);
        }
      }

      private void upgradeDb() {
        LOGGER.info("Starting DB Migration at {}", startDate);
        rubyBridge.databaseMigration().trigger();
        LOGGER.info("DB Migration ended successfully at {}", new Date());
      }

      private void restartContainer() {
        LOGGER.info("Restarting container at {}", new Date());
        platform.doStart();
        LOGGER.info("Container restarted successfully at {}", new Date());
      }

      private void recreateWebRoutes() {
        LOGGER.info("Recreating web routes at {}", new Date());
        rubyBridge.railsRoutes().recreate();
        LOGGER.info("Routes recreated successfully at {}", new Date());
      }
    });
  }

  @Override
  @CheckForNull
  public Date startedAt() {
    return this.startDate;
  }

  @Override
  public Status status() {
    return this.status;
  }

  @Override
  @CheckForNull
  public Throwable failureError() {
    return this.failureError;
  }
}
