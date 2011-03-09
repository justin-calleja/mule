/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.launcher.log4j;

import org.mule.module.launcher.MuleApplicationClassLoader;
import org.mule.module.reboot.MuleContainerBootstrapUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.log4j.Hierarchy;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.RollingFileAppender;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggerRepository;
import org.apache.log4j.spi.RepositorySelector;
import org.apache.log4j.spi.RootLogger;
import org.apache.log4j.xml.DOMConfigurator;

public class ApplicationAwareRepositorySelector implements RepositorySelector
{
    protected static final String PATTERN_LAYOUT = "%-5p %d [%t] %c: %m%n";

    protected static final Integer NO_CCL_CLASSLOADER = 0;
    protected ConcurrentMap<Integer, LoggerRepository> repository = new ConcurrentHashMap<Integer, LoggerRepository>();

    // note that this is a direct log4j logger declaration, not a clogging one
    protected Logger logger = Logger.getLogger(getClass());

    public LoggerRepository getLoggerRepository()
    {
        final ClassLoader ccl = Thread.currentThread().getContextClassLoader();

        LoggerRepository repository = this.repository.get(ccl == null ? NO_CCL_CLASSLOADER : ccl.hashCode());
        if (repository == null)
        {
            final RootLogger root = new RootLogger(Level.INFO);
            repository = new Hierarchy(root);

            try
            {
                ConfigWatchDog configWatchDog = null;
                if (ccl instanceof MuleApplicationClassLoader)
                {
                    MuleApplicationClassLoader muleCL = (MuleApplicationClassLoader) ccl;
                    // check if there's an app-specific logging configuration available,
                    // scope the lookup to this classloader only, as getResource() will delegate to parents
                    // locate xml config first, fallback to properties format if not found
                    URL appLogConfig = muleCL.findResource("log4j.xml");
                    if (appLogConfig == null)
                    {
                        appLogConfig = muleCL.findResource("log4j.properties");
                    }
                    final String appName = muleCL.getAppName();
                    if (appLogConfig == null)
                    {
                        // fallback to defaults
                        String logName = String.format("mule-app-%s.log", appName);
                        File logDir = new File(MuleContainerBootstrapUtils.getMuleHome(), "logs");
                        File logFile = new File(logDir, logName);
                        RollingFileAppender fileAppender = new RollingFileAppender(new PatternLayout(PATTERN_LAYOUT), logFile.getAbsolutePath(), true);
                        fileAppender.setMaxBackupIndex(100);
                        fileAppender.setMaximumFileSize(1000000);
                        fileAppender.activateOptions();
                        root.addAppender(fileAppender);
                    }
                    else
                    {
                        configureFrom(appLogConfig, repository);
                        if (appLogConfig.toExternalForm().startsWith("file:"))
                        {
                            // if it's not a file, no sense in monitoring it for changes
                            configWatchDog = new ConfigWatchDog(muleCL, appLogConfig.getFile(), repository);
                            configWatchDog.setName(String.format("[%s].log4j.config.watchdog", appName));
                        }
                        else
                        {
                            if (logger.isInfoEnabled())
                            {
                                logger.info(String.format("Logging config %s is not an external file, will not be monitored for changes", appLogConfig));
                            }
                        }
                    }
                }
                else
                {
                    // this is not an app init, but a Mule container, use the top-level defaults
                    File defaultSystemLog = new File(MuleContainerBootstrapUtils.getMuleHome(), "conf/log4j.xml");
                    if (defaultSystemLog.exists() && defaultSystemLog.canRead())
                    {
                        new DOMConfigurator().doConfigure(defaultSystemLog.getAbsolutePath(), repository);
                    }
                    else
                    {
                        defaultSystemLog = new File(MuleContainerBootstrapUtils.getMuleHome(), "conf/log4j.properties");
                        new PropertyConfigurator().doConfigure(defaultSystemLog.getAbsolutePath(), repository);
                    }
                }

                final LoggerRepository previous = this.repository.putIfAbsent(ccl == null ? NO_CCL_CLASSLOADER : ccl.hashCode(), repository);
                if (previous != null)
                {
                    repository = previous;
                }

                if (configWatchDog != null)
                {
                    configWatchDog.start();
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        return repository;
    }

    protected void configureFrom(URL url, LoggerRepository repository)
    {
        if (url.toExternalForm().endsWith(".xml"))
        {
            new DOMConfigurator().doConfigure(url, repository);
        }
        else
        {
            new PropertyConfigurator().doConfigure(url, repository);
        }
    }

    // TODO rewrite using a single-threaded scheduled executor and terminate on undeploy/redeploy
    // this is a modified and unified version from log4j to better fit Mule's app lifecycle
    protected class ConfigWatchDog extends Thread
    {

        protected LoggerRepository repository;
        protected File file;
        protected long lastModif = 0;
        protected boolean warnedAlready = false;
        protected volatile boolean interrupted = false;

        /**
         * The default delay between every file modification check, set to 60
         * seconds.
         */
        static final public long DEFAULT_DELAY = 60000;
        /**
         * The name of the file to observe  for changes.
         */
        protected String filename;

        /**
         * The delay to observe between every check. By default set {@link
         * #DEFAULT_DELAY}.
         */
        protected long delay = DEFAULT_DELAY;

        public ConfigWatchDog(final MuleApplicationClassLoader appClassLoader, String filename, LoggerRepository repository)
        {
            appClassLoader.addShutdownListener(new MuleApplicationClassLoader.ShutdownListener()
            {
                public void execute()
                {
                    interrupted = true;
                }
            });
            this.filename = filename;
            this.file = new File(filename);
            this.lastModif = file.lastModified();
            setDaemon(true);
            this.repository = repository;
            this.delay = 10000; // 10 secs
        }

        public void doOnChange()
        {
            if (logger.isInfoEnabled())
            {
                logger.info("Reconfiguring logging from: " + filename);
            }
            if (filename.endsWith(".xml"))
            {
                new DOMConfigurator().doConfigure(filename, repository);
            }
            else
            {
                new PropertyConfigurator().doConfigure(filename, repository);
            }
        }

        /**
         * Set the delay to observe between each check of the file changes.
         */
        public void setDelay(long delay)
        {
            this.delay = delay;
        }

        protected void checkAndConfigure()
        {
            boolean fileExists;
            try
            {
                fileExists = file.exists();
            }
            catch (SecurityException e)
            {
                LogLog.warn("Was not allowed to read check file existence, file:[" + filename + "].");
                interrupted = true; // there is no point in continuing
                return;
            }

            if (fileExists)
            {
                long l = file.lastModified(); // this can also throw a SecurityException
                if (l > lastModif)
                {           // however, if we reached this point this
                    lastModif = l;              // is very unlikely.
                    doOnChange();
                    warnedAlready = false;
                }
            }
            else
            {
                if (!warnedAlready)
                {
                    LogLog.debug("[" + filename + "] does not exist.");
                    warnedAlready = true;
                }
            }
        }

        public void run()
        {
            while (!interrupted)
            {
                try
                {
                    Thread.sleep(delay);
                }
                catch (InterruptedException e)
                {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                }
                checkAndConfigure();
            }
            if (logger.isDebugEnabled())
            {
                logger.debug(getName() + " terminated successfully");
            }
        }

    }
}
