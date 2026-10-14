/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.storage.database;

import com.djrapitops.plan.exceptions.database.DBInitException;
import com.djrapitops.plan.processing.Processing;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.settings.config.paths.DatabaseSettings;
import com.djrapitops.plan.settings.locale.Locale;
import com.djrapitops.plan.storage.file.PlanFiles;
import com.djrapitops.plan.utilities.logging.ErrorLogger;
import dev.vankka.dependencydownload.ApplicationDependencyManager;
import net.playeranalytics.plugin.scheduling.RunnableFactory;
import net.playeranalytics.plugin.server.PluginLogger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * {@link MySQLDB#setupDataSource()} points the calling thread's context class loader at the driver class loader while
 * Hikari starts. The calling thread is the server thread, whose context class loader every other mod or plugin may use
 * (Java's ServiceLoader, for one), so it has to get its own back even when the pool fails to start.
 */
class MySQLDBContextClassLoaderTest {

    @Test
    void theCallersContextClassLoaderIsRestoredWhenThePoolCannotStart() throws Exception {
        PlanConfig config = Mockito.mock(PlanConfig.class);
        when(config.get(DatabaseSettings.MYSQL_HOST)).thenReturn("127.0.0.1");
        when(config.get(DatabaseSettings.MYSQL_PORT)).thenReturn("1"); // nothing listens on port 1: the pool cannot start
        when(config.get(DatabaseSettings.MYSQL_DATABASE)).thenReturn("Plan");
        when(config.get(DatabaseSettings.MYSQL_LAUNCH_OPTIONS)).thenReturn("?rewriteBatchedStatements=true&useSSL=false&connectTimeout=2000");
        when(config.get(DatabaseSettings.MYSQL_USER)).thenReturn("plan");
        when(config.get(DatabaseSettings.MYSQL_PASS)).thenReturn("");
        when(config.get(DatabaseSettings.MAX_CONNECTIONS)).thenReturn(1);
        when(config.get(DatabaseSettings.MAX_LIFETIME)).thenReturn(TimeUnit.MINUTES.toMillis(25));

        MySQLDB underTest = new MySQLDB(
                Mockito.mock(Locale.class),
                config,
                Mockito.mock(PlanFiles.class),
                () -> null,
                Mockito.mock(RunnableFactory.class),
                Mockito.mock(PluginLogger.class),
                Mockito.mock(ErrorLogger.class),
                Mockito.mock(ApplicationDependencyManager.class),
                Mockito.mock(Processing.class)
        );
        // Stands in for the downloaded driver's class loader (skipping the download); it delegates to the test class
        // path, where the drivers are, without owning them, so the driver unloading after setup leaves them registered.
        try (URLClassLoader driverClassLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());
             URLClassLoader callersClassLoader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            underTest.driverClassLoader = driverClassLoader;

            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            thread.setContextClassLoader(callersClassLoader);
            try {
                assertThrows(DBInitException.class, underTest::setupDataSource);
                assertSame(callersClassLoader, thread.getContextClassLoader(),
                        "a pool that fails to start must not leave the driver class loader on the calling thread");
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }
}
