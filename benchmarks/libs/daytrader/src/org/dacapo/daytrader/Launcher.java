/*
 * Copyright (c) 2009 The Australian National University.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0.
 * You may obtain the license at
 * 
 *    http://www.opensource.org/licenses/apache2.0.php
 */
package org.dacapo.daytrader;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/** 
* date:  $Date: 2009-12-24 11:19:36 +1100 (Thu, 24 Dec 2009) $
* id: $Id: Launcher.java 738 2009-12-24 00:19:36Z steveb-oss $
*/
public class Launcher {
  // Geronimo configuration
  private final static String GVERSION = "3.0.1";
  private final static String GTYPE = "minimal";
  private final static String GDIRECTORY = "geronimo-tomcat7-" + GTYPE + "-" + GVERSION;

  // The daytrader-dacapo application
  private final static String CAR_NAME = "org.apache.geronimo.daytrader.plugins/daytrader-dacapo/3.0-SNAPSHOT/car";

  // This jar contains the code that knows how to create and communicate with
  // geronimo environments
  private final static String[] DACAPO_CLI_JAR = { "jar/daytrader.jar" };

  // The following list is defined in the "Class-Path:" filed of MANIFEST.MF for the client and server jars
  private final static String[] GERONIMO_LIB_JARS = {"agent/transformer.jar", "endorsed/yoko-rmi-spec.jar"
          , "endorsed/yoko-spec-corba.jar", "geronimo-main.jar", "geronimo-cli.jar", "commons-cli.jar"
          , "geronimo-hook.jar", "geronimo-rmi-loader.jar", "karaf-jaas-boot.jar"};

  private static int numThreads = -1;
  private static String size;
  private static boolean useBeans = true;

  private static ClassLoader serverCLoader = null;
  private static ClassLoader clientCLoader = null;
  private static Method clientMethod = null;
  private static File scratch = null;

  private static final String TRADEBEANS_LOG_FILE_NAME = "tradebeans.log";
  private static final String TRADESOAP_LOG_FILE_NAME = "tradesoap.log";

  public static void initialize(File scratchdir, int threads, String dtSize, boolean beans) {
    numThreads = threads;
    size = dtSize;
    useBeans = beans;
    scratch = new File(scratchdir.getAbsolutePath());
    setGeronimoProperties();
    ClassLoader originalCLoader = Thread.currentThread().getContextClassLoader();

    try {
      // Create a server environment
      serverCLoader = createGeronimoClassLoader(originalCLoader, true);
      Thread.currentThread().setContextClassLoader(serverCLoader);
      Class<?> clazz = serverCLoader.loadClass("org.dacapo.daytrader.DaCapoServerRunner");
      Method method = clazz.getMethod("initialize", new Class[] {});
      method.invoke(null, new Object[] {});

      // Create a client environment
      clientCLoader = createGeronimoClassLoader(originalCLoader, false);
      Thread.currentThread().setContextClassLoader(clientCLoader);
      clazz = clientCLoader.loadClass("org.dacapo.daytrader.DaCapoClientRunner");
      method = clazz.getMethod("initialize", new Class[] { String.class, String.class, int.class, boolean.class });
      method.invoke(null, new Object[] { CAR_NAME, size, numThreads, useBeans });
      clientMethod = clazz.getMethod("runIteration", new Class[] { String.class, int.class, boolean.class });
    } catch (Exception e) {
      System.err.println("Exception during initialization: " + e.toString());
      e.printStackTrace();
      System.exit(-1);
    } finally {
      Thread.currentThread().setContextClassLoader(originalCLoader);
    }
  }

  private static void setGeronimoProperties() {
    File geronimo = new File(scratch, GDIRECTORY);
    System.setProperty("org.apache.geronimo.home.dir", geronimo.getPath());
    System.setProperty("org.apache.geronimo.server.dir", geronimo.getPath());
    System.setProperty("karaf.home", geronimo.getPath());
    System.setProperty("karaf.base", geronimo.getPath());
    System.setProperty("java.util.logging.config.file", geronimo.getPath() + "/etc/java.util.logging.properties");
    System.setProperty("java.ext.dirs", geronimo.getPath() + "/lib/ext:" + System.getProperty("java.home") + "/lib/ext");
    System.setProperty("java.io.tmpdir", geronimo.getPath() + "/var/temp");
    System.setProperty("karaf.startRemoteShell", "true");
  }

  public static void performIteration() {
    if (numThreads == -1) {
      System.err.println("Trying to run Daytrader before initializing.  Exiting.");
      System.exit(0);
    }
    ClassLoader originalClassloader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(clientCLoader);
      clientMethod.invoke(null, new Object[] { size, numThreads, useBeans });
    } catch (Exception e) {
      System.err.println("Exception during iteration: " + e.toString());
      e.printStackTrace();
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassloader);
    }
  }

  public static void shutdown(){
    try {
      Class<?> clazz = serverCLoader.loadClass("org.dacapo.daytrader.DaCapoServerRunner");
      Method method = clazz.getMethod("shutdown", new Class[]{});
      method.invoke(null, new Object[]{});
    }catch (Exception e) {
      System.err.println("Exception during iteration: " + e.toString());
      e.printStackTrace();
    }
  }

  /**
   * Create the classloader from within which to start the Geronimo client
   * and/or server kernels.
   * 
   * @param server Is this the server (or client) classloader
   * @return The classloader
   * @throws Exception
   */
  private static ClassLoader createGeronimoClassLoader(ClassLoader parent, boolean server) {
    File geronimo = new File(scratch, GDIRECTORY).getAbsoluteFile();
    ClassLoader libCL = new URLClassLoader(getGeronimoLibraryJars(geronimo, server), parent);
    return libCL;
  }

  /**
   * Get a list of jars (if any) which should be in the library classpath for
   * this benchmark
   * 
   * @param geronimo The base directory for the jars
   * @return An array of URLs, one URL for each jar
   */
  private static URL[] getGeronimoLibraryJars(File geronimo, boolean server) {
    List<URL> jars = new ArrayList<URL>();

    if (server) {
      File endorsed = new File(geronimo, "lib/endorsed");
      addJars(jars, endorsed, endorsed.list());
    }
    addJars(jars, scratch, DACAPO_CLI_JAR);

    File lib = new File(geronimo, "lib");
    addJars(jars, lib, GERONIMO_LIB_JARS);

    return jars.toArray(new URL[jars.size()]);
  }

  /**
   * Compile a list of paths to jars from a base directory and relative paths.
   * 
   * @param config The config file for this benchmark, which lists the jars
   * @param scratch The scratch directory, in which the jars will be located
   * @return An array of URLs, one URL for each jar
   */
  private static void addJars(List<URL> jars, File directory, String[] jarNames) {
    if (jarNames != null) {
      for (int i = 0; i < jarNames.length; i++) {
        File jar = new File(directory, jarNames[i]);
        try {
          URL url = jar.toURI().toURL();
          jars.add(url);
        } catch (MalformedURLException e) {
          System.err.println("Unable to create URL for jar: " + jarNames[i] + " in " + directory.toString());
          e.printStackTrace();
          System.exit(-1);
        }
      }
    }
  }
}
