/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner;

import org.exist.EXistException;
import org.exist.start.classloader.Classpath;
import org.exist.storage.BrokerPool;
import org.exist.storage.journal.Journal;
import org.exist.util.Configuration;
import org.exist.util.ConfigurationHelper;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.exist.repo.AutoDeploymentTrigger.AUTODEPLOY_PROPERTY;

/**
 * An embedded eXist-db, with its data in a temporary directory that is removed when it stops, and the
 * auto-deployment of packages turned off.
 *
 * <p>The runner used eXist-db's test support for this, {@code org.exist.test.ExistEmbeddedServer}, which from
 * eXist-db 7.0.0 is a JUnit 5 extension in exist-core's test jar, no longer in exist-core itself. This does
 * what its {@code startDb()} and {@code stopDb()} did, with exist-core's own API only. It is written in Java
 * because {@code BrokerPool}'s static {@code configure} and {@code getInstance} are inherited from a
 * package-private class, which Scala can't call them through.</p>
 */
public class EmbeddedExist {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedExist.class);

    @Nullable private volatile BrokerPool pool = null;
    @Nullable private Path dataDir = null;
    @Nullable private String prevAutoDeploy = null;

    public EmbeddedExist() {
        // eXist-db loads its libraries and extension modules through its own class loader
        Thread.currentThread().setContextClassLoader(new Classpath().getClassLoader(null));
    }

    /**
     * Starts eXist-db, with the conf.xml found from {@code exist.home} or the class path.
     *
     * @throws DatabaseConfigurationException if conf.xml is not valid
     * @throws EXistException if eXist-db can't start
     * @throws IOException if the temporary data directory can't be created
     */
    public synchronized void start() throws DatabaseConfigurationException, EXistException, IOException {
        if (pool != null) {
            throw new IllegalStateException("eXist-db is already running");
        }

        prevAutoDeploy = System.getProperty(AUTODEPLOY_PROPERTY, "off");
        System.setProperty(AUTODEPLOY_PROPERTY, "off");

        final Optional<Path> home = Optional.ofNullable(System.getProperty("exist.home", System.getProperty("user.dir"))).map(Path::of);
        final Path confFile = ConfigurationHelper.lookup("conf.xml", home);
        final Configuration config = confFile.isAbsolute() && Files.exists(confFile)
                ? new Configuration(confFile.toAbsolutePath().toString())
                : new Configuration(FileUtils.fileName(confFile), home);

        dataDir = Files.createTempDirectory("org.exist.xqts.runner.EmbeddedExist");
        config.setProperty(BrokerPool.PROPERTY_DATA_DIR, dataDir);
        config.setProperty(Journal.PROPERTY_RECOVERY_JOURNAL_DIR, dataDir);
        LOG.info("Using temporary storage location: {}", dataDir.toAbsolutePath());

        BrokerPool.configure(BrokerPool.DEFAULT_INSTANCE_NAME, 1, 5, config, Optional.empty());
        pool = BrokerPool.getInstance(BrokerPool.DEFAULT_INSTANCE_NAME);
    }

    /**
     * @return the broker pool of the running eXist-db
     */
    public BrokerPool getBrokerPool() {
        final BrokerPool running = pool;
        if (running == null) {
            throw new IllegalStateException("eXist-db is not running");
        }
        return running;
    }

    /**
     * Shuts eXist-db down, and removes its data.
     */
    public synchronized void stop() {
        final BrokerPool running = getBrokerPool();
        running.shutdown();
        pool = null;

        if (dataDir != null) {
            FileUtils.deleteQuietly(dataDir);
            dataDir = null;
        }

        if (prevAutoDeploy != null) {
            System.setProperty(AUTODEPLOY_PROPERTY, prevAutoDeploy);
            prevAutoDeploy = null;
        }
    }
}
