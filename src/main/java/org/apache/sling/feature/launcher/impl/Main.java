/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.launcher.impl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.IOUtils;
import org.apache.sling.feature.io.file.ArtifactHandler;
import org.apache.sling.feature.io.file.ArtifactManager;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.apache.sling.feature.launcher.impl.launchers.FrameworkLauncher;
import org.apache.sling.feature.launcher.spi.Launcher;
import org.apache.sling.feature.launcher.spi.LauncherPrepareContext;
import org.osgi.framework.FrameworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the launcher main class.
 * It parses command line parameters and prepares the launcher.
 */
public class Main {

    private static Logger LOGGER;

    public static Logger LOG() {
        if ( LOGGER == null ) {
            LOGGER = LoggerFactory.getLogger("launcher");
        }
        return LOGGER;
    }

    private static volatile File m_populate;

    private static volatile String m_frameworkVersion = null; // DEFAULT is null

    /** Split a string into key and value */
    private static String[] split(final String val) {
        final int pos = val.indexOf('=');
        if ( pos == -1 ) {
            return new String[] {val, "true"};
        }
        return new String[] {val.substring(0, pos), val.substring(pos + 1)};
    }

    /**
     * Parse the command line parameters and update a configuration object.
     * @param args Command line parameters
     * @return Configuration object.
     */
    private static void parseArgs(final LauncherConfig config, final String[] args) {
        final Options options = new Options();

        final Option repoOption =  new Option("u", true, "Set repository url");
        final Option featureOption =  new Option("f", true, "Set feature files");
        final Option fwkProperties = new Option("D", true, "Set framework properties");
        final Option varValue = new Option("V", true, "Set variable value");
        final Option debugOption = new Option("v", false, "Verbose");
        debugOption.setArgs(0);
        final Option cacheOption = new Option("c", true, "Set cache dir");
        final Option homeOption = new Option("p", true, "Set home dir");
        final Option populateOption = new Option("dao", true, "Only download required artifacts into directory");

        final Option frameworkOption = new Option("fv", true, "Set felix framework version");

        options.addOption(repoOption);
        options.addOption(featureOption);
        options.addOption(fwkProperties);
        options.addOption(varValue);
        options.addOption(debugOption);
        options.addOption(cacheOption);
        options.addOption(homeOption);
        options.addOption(populateOption);
        options.addOption(frameworkOption);

        final CommandLineParser clp = new BasicParser();
        try {
            final CommandLine cl = clp.parse(options, args);

            if ( cl.hasOption(repoOption.getOpt()) ) {
                final String value = cl.getOptionValue(repoOption.getOpt());
                config.setRepositoryUrls(value.split(","));
            }
            if ( cl.hasOption(fwkProperties.getOpt()) ) {
                for(final String value : cl.getOptionValues(fwkProperties.getOpt())) {
                    final String[] keyVal = split(value);

                    config.getInstallation().getFrameworkProperties().put(keyVal[0], keyVal[1]);
                }
            }
            if ( cl.hasOption(varValue.getOpt()) ) {
                for(final String optVal : cl.getOptionValues(varValue.getOpt())) {
                    final String[] keyVal = split(optVal);

                    config.getVariables().put(keyVal[0], keyVal[1]);
                }
            }
            if ( cl.hasOption(debugOption.getOpt()) ) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }

            if ( cl.hasOption(featureOption.getOpt()) ) {
                for(final String optVal : cl.getOptionValues(featureOption.getOpt())) {
                    config.addFeatureFiles(optVal.split(","));
                }
            }
            if (cl.hasOption(cacheOption.getOpt())) {
                config.setCacheDirectory(new File(cl.getOptionValue(cacheOption.getOpt())));
            }
            if (cl.hasOption(homeOption.getOpt())) {
                config.setHomeDirectory(new File(cl.getOptionValue(homeOption.getOpt())));
            }
            if (cl.hasOption(populateOption.getOpt())) {
                m_populate = new File(cl.getOptionValue(populateOption.getOpt()));
                if (!m_populate.isDirectory() && !m_populate.mkdirs()) {
                    throw new ParseException("Bad dao directory");
                }
            }
            if (cl.hasOption(frameworkOption.getOpt())) {
                m_frameworkVersion = cl.getOptionValue(frameworkOption.getOpt());
            }
        } catch ( final ParseException pe) {
            Main.LOG().error("Unable to parse command line: {}", pe.getMessage(), pe);

            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("launcher", options);

            System.exit(1);
        }
    }


    public static void main(final String[] args) {
        // setup logging
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.levelInBrackets", "true");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "false");

        // check if launcher has already been created
        final LauncherConfig launcherConfig = new LauncherConfig();
        parseArgs(launcherConfig, args);

        launcherConfig.getVariables().put("sling.home", launcherConfig.getHomeDirectory().getAbsolutePath());
        if (launcherConfig.getVariables().get("repository.home") == null ) {
            launcherConfig.getVariables().put("repository.home", launcherConfig.getHomeDirectory().getAbsolutePath() + File.separatorChar + "repository");
        }
        launcherConfig.getVariables().put("sling.launchpad", launcherConfig.getHomeDirectory().getAbsolutePath() + "/launchpad");

        final Installation installation = launcherConfig.getInstallation();

        // set sling home, and use separate locations for launchpad and properties
        installation.getFrameworkProperties().put("sling.home", launcherConfig.getHomeDirectory().getAbsolutePath());
        installation.getFrameworkProperties().put("sling.launchpad", launcherConfig.getHomeDirectory().getAbsolutePath() + "/launchpad");
        if (!installation.getFrameworkProperties().containsKey("repository.home")) {
            installation.getFrameworkProperties().put("repository.home", launcherConfig.getHomeDirectory().getAbsolutePath() + File.separatorChar + "repository");
        }
        installation.getFrameworkProperties().put("sling.properties", "conf/sling.properties");


        // additional OSGi properties
        // move storage inside launcher
        if ( installation.getFrameworkProperties().get(STORAGE_PROPERTY) == null ) {
            installation.getFrameworkProperties().put(STORAGE_PROPERTY, launcherConfig.getHomeDirectory().getAbsolutePath() + File.separatorChar + "framework");
        }
        // set start level to 30
        if ( installation.getFrameworkProperties().get(START_LEVEL_PROP) == null ) {
            installation.getFrameworkProperties().put(START_LEVEL_PROP, "30");
        }

        Main.LOG().info("");
        Main.LOG().info("Apache Sling Application Launcher");
        Main.LOG().info("---------------------------------");


        Main.LOG().info("Initializing...");

        final Launcher launcher = new FrameworkLauncher();

        try (ArtifactManager artifactManager = ArtifactManager.getArtifactManager(launcherConfig)) {

            Main.LOG().info("Artifact Repositories: {}", Arrays.toString(launcherConfig.getRepositoryUrls()));
            Main.LOG().info("Assembling provisioning model...");

            try {
                boolean restart = launcherConfig.getFeatureFiles().length == 0;

                final Feature app = assemble(launcherConfig, artifactManager);

                Main.LOG().info("");
                Main.LOG().info("Assembling launcher...");

                final LauncherPrepareContext ctx = new LauncherPrepareContext()
                {
                    @Override
                    public File getArtifactFile(final ArtifactId artifact) throws IOException
                    {
                        final ArtifactHandler handler = artifactManager.getArtifactHandler(":" + artifact.toMvnPath());
                        if (m_populate != null)
                        {
                            populate(handler.getFile(), artifact);
                        }
                        return handler.getFile();
                    }

                    @Override
                    public void addAppJar(final File jar)
                    {
                        launcherConfig.getInstallation().addAppJar(jar);
                    }
                };

                launcher.prepare(ctx, IOUtils.getFelixFrameworkId(m_frameworkVersion), app);

                FeatureProcessor.prepareLauncher(ctx, launcherConfig, app);

                Main.LOG().info("Using {} local artifacts, {} cached artifacts, and {} downloaded artifacts",
                    launcherConfig.getLocalArtifacts(), launcherConfig.getCachedArtifacts(), launcherConfig.getDownloadedArtifacts());

                if (m_populate != null)
                {
                    Map<Artifact, File> local = FeatureProcessor.calculateArtifacts(artifactManager, app);
                    for (Map.Entry<Artifact, File> entry : local.entrySet())
                    {
                        populate(entry.getValue(), entry.getKey().getId());
                    }
                    return;
                }

                if (restart) {
                    launcherConfig.getInstallation().getInstallableArtifacts().clear();
                    launcherConfig.getInstallation().getConfigurations().clear();
                    launcherConfig.getInstallation().getBundleMap().clear();
                }
            } catch ( final Exception iae) {
                Main.LOG().error("Error while assembling launcher: {}", iae.getMessage(), iae);
                System.exit(1);
            }
        }
        catch (IOException ex) {
            Main.LOG().error("Unable to setup artifact manager: {}", ex.getMessage(), ex);
            System.exit(1);
        }

        try {
            run(launcherConfig, launcher);
        } catch ( final Exception iae) {
            Main.LOG().error("Error while running launcher: {}", iae.getMessage(), iae);
            System.exit(1);
        }
    }

    private static void populate(File file, ArtifactId artifactId) throws IOException{
        File target = new File(m_populate, artifactId.toMvnPath().replace('/', File.separatorChar));

        if (!target.isFile())
        {
            if (Main.LOG().isDebugEnabled())
            {
                Main.LOG().debug("Populating {} with {}", target.getAbsolutePath(), file.getAbsolutePath());
            }
            target.getParentFile().mkdirs();
            Files.copy(file.toPath(), target.toPath());
        }
    }

    private static Feature assemble(final LauncherConfig launcherConfig, final ArtifactManager artifactManager) throws IOException
    {
        if (launcherConfig.getFeatureFiles().length == 0) {
            File application = new File(launcherConfig.getHomeDirectory(), "resources" + File.separatorChar + "provisioning" + File.separatorChar + "application.json");
            if (application.isFile()) {
                launcherConfig.addFeatureFiles(application.toURI().toURL().toString());
            }
            else {
                throw new IllegalStateException("No feature(s) to launch found and none where specified");
            }
            return FeatureProcessor.createApplication(launcherConfig, artifactManager);
        }
        else
        {
            final Feature app = FeatureProcessor.createApplication(launcherConfig, artifactManager);

            // write application back
            final File file = new File(launcherConfig.getHomeDirectory(), "resources" + File.separatorChar + "provisioning" + File.separatorChar + "application.json");
            file.getParentFile().mkdirs();

            try (final FileWriter writer = new FileWriter(file))
            {
                FeatureJSONWriter.write(writer, app);
            }
            catch (final IOException ioe)
            {
                Main.LOG().error("Error while writing application file: {}", ioe.getMessage(), ioe);
                System.exit(1);
            }
            return app;
        }
    }

    private static final String STORAGE_PROPERTY = "org.osgi.framework.storage";

    private static final String START_LEVEL_PROP = "org.osgi.framework.startlevel.beginning";

    /**
     * Run launcher.
     * @param config The configuration
     * @throws Exception If anything goes wrong
     */
    private static void run(final LauncherConfig config, final Launcher launcher) throws Exception {
        Main.LOG().info("");
        Main.LOG().info("Starting launcher...");
        Main.LOG().info("Launcher Home: {}", config.getHomeDirectory().getAbsolutePath());
        Main.LOG().info("Cache Directory: {}", config.getCacheDirectory().getAbsolutePath());
        Main.LOG().info("");

        final Installation installation = config.getInstallation();

        // set sling home, and use separate locations for launchpad and properties
        installation.getFrameworkProperties().put("sling.home", config.getHomeDirectory().getAbsolutePath());
        installation.getFrameworkProperties().put("sling.launchpad", config.getHomeDirectory().getAbsolutePath() + "/launchpad");
        if (!installation.getFrameworkProperties().containsKey("repository.home")) {
            installation.getFrameworkProperties().put("repository.home", config.getHomeDirectory().getAbsolutePath() + File.separatorChar + "repository");
        }
        installation.getFrameworkProperties().put("sling.properties", "conf/sling.properties");


        // additional OSGi properties
        // move storage inside launcher
        if ( installation.getFrameworkProperties().get(STORAGE_PROPERTY) == null ) {
            installation.getFrameworkProperties().put(STORAGE_PROPERTY, config.getHomeDirectory().getAbsolutePath() + File.separatorChar + "framework");
        }
        // set start level to 30
        if ( installation.getFrameworkProperties().get(START_LEVEL_PROP) == null ) {
            installation.getFrameworkProperties().put(START_LEVEL_PROP, "30");
        }

        while (launcher.run(installation, createClassLoader(installation)) == FrameworkEvent.STOPPED_SYSTEM_REFRESHED) {
            Main.LOG().info("Framework restart due to extension refresh");
        }
    }

    /**
     * Create the class loader.
     * @param installation The launcher configuration
     * @throws Exception If anything goes wrong
     */
    public static ClassLoader createClassLoader(final Installation installation) throws Exception {
        final List<URL> list = new ArrayList<>();
        for(final File f : installation.getAppJars()) {
            try {
                list.add(f.toURI().toURL());
            } catch (IOException e) {
                // ignore
            }
        }
        list.add(Main.class.getProtectionDomain().getCodeSource().getLocation());

        final URL[] urls = list.toArray(new URL[list.size()]);

        if ( Main.LOG().isDebugEnabled() ) {
            Main.LOG().debug("App classpath: ");
            for (int i = 0; i < urls.length; i++) {
                Main.LOG().debug(" - {}", urls[i]);
            }
        }

        // create a paranoid class loader, loading from parent last
        final ClassLoader cl = new URLClassLoader(urls) {
            @Override
            public final Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // First check if it's already loaded
                Class<?> clazz = findLoadedClass(name);

                if (clazz == null) {

                    try {
                        clazz = findClass(name);
                    } catch (ClassNotFoundException cnfe) {
                        ClassLoader parent = getParent();
                        if (parent != null) {
                            // Ask to parent ClassLoader (can also throw a CNFE).
                            clazz = parent.loadClass(name);
                        } else {
                            // Propagate exception
                            throw cnfe;
                        }
                    }
                }

                if (resolve) {
                    resolveClass(clazz);
                }

                return clazz;
            }

            @Override
            public final URL getResource(final String name) {

                URL resource = findResource(name);
                ClassLoader parent = this.getParent();
                if (resource == null && parent != null) {
                    resource = parent.getResource(name);
                }

                return resource;
            }
        };

        Thread.currentThread().setContextClassLoader(cl);

        return cl;
    }
}
