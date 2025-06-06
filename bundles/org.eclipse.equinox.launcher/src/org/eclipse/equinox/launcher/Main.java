/*******************************************************************************
 * Copyright (c) 2000, 2025 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Anton Leherbauer (Wind River Systems) - bug 301226
 *     Red Hat Inc. - bug 373640, 379102
 *     Ericsson AB (Pascal Rapicault) - bug 304132
 *     Rapicorp, Inc - Default the configuration to Application Support (bug 461725)
 *     Lars Vogel <Lars.Vogel@vogella.com> - Bug 221969
 *******************************************************************************/
package org.eclipse.equinox.launcher;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The launcher for Eclipse.
 *
 * <b>Note:</b> This class should not be referenced programmatically by
 * other Java code. This class exists only for the purpose of launching Eclipse
 * from the command line. To launch Eclipse programmatically, use
 * org.eclipse.core.runtime.adaptor.EclipseStarter. The fields and methods
 * on this class are not API.
 *
 * @noextend This class is not intended to be subclassed by clients.
 * @noinstantiate This class is not intended to be instantiated by clients.
 */
public class Main {

	/**
	 * Indicates whether this instance is running in debug mode.
	 */
	private boolean debug = false;

	/**
	 * The location of the launcher to run.
	 */
	private String bootLocation = null;

	/**
	 * The location of the install root
	 */
	private URL installLocation = null;

	/**
	 * The location of the configuration information for this instance
	 */
	private URL configurationLocation = null;

	/**
	 * The id of the bundle that will contain the framework to run.  Defaults to org.eclipse.osgi.
	 */
	private String framework = OSGI;

	/**
	 * The extra development time class path entries for the framework.
	 */
	private String devClassPath = null;

	/*
	 * The extra development time class path entries for all bundles.
	 */
	private Properties devClassPathProps = null;

	/**
	 * Indicates whether this instance is running in development mode.
	 */
	private boolean inDevelopmentMode = false;

	/**
	 * Indicates which OS was passed in with -os
	 */
	private String os = null;
	private String ws = null;
	private String arch = null;

	//    private String name = null; // The name to brand the launcher
	//    private String launcher = null; // The full path to the launcher
	private String library = null;
	private String exitData = null;

	private String vm = null;
	private List<String> vmargs;
	private List<String> commands;
	private List<String> extensionPaths = null;

	private JNIBridge bridge = null;

	// splash handling
	private boolean showSplash = false;
	private String splashLocation = null;
	private String endSplash = null;
	private boolean initialize = false;
	private boolean splashDown = false;

	public final class SplashHandler extends Thread {
		@Override
		public void run() {
			takeDownSplash();
		}

		//Called reflectively by org.eclipse.core.runtime.internal.adaptor.DefaultStartupMonitor
		public void updateSplash() {
			if (bridge != null && !splashDown) {
				bridge.updateSplash();
			}
		}
	}

	private final Thread splashHandler = new SplashHandler();

	//splash screen system properties
	private static final String SPLASH_HANDLE = "org.eclipse.equinox.launcher.splash.handle"; //$NON-NLS-1$
	private static final String SPLASH_LOCATION = "org.eclipse.equinox.launcher.splash.location"; //$NON-NLS-1$

	// command line args
	private static final String FRAMEWORK = "-framework"; //$NON-NLS-1$
	private static final String INSTALL = "-install"; //$NON-NLS-1$
	private static final String INITIALIZE = "-initialize"; //$NON-NLS-1$
	private static final String VM = "-vm"; //$NON-NLS-1$
	private static final String VMARGS = "-vmargs"; //$NON-NLS-1$
	private static final String DEBUG = "-debug"; //$NON-NLS-1$
	private static final String DEV = "-dev"; //$NON-NLS-1$
	private static final String CONFIGURATION = "-configuration"; //$NON-NLS-1$
	private static final String NOSPLASH = "-nosplash"; //$NON-NLS-1$
	private static final String SHOWSPLASH = "-showsplash"; //$NON-NLS-1$
	private static final String EXITDATA = "-exitdata"; //$NON-NLS-1$
	private static final String NAME = "-name"; //$NON-NLS-1$
	private static final String LAUNCHER = "-launcher"; //$NON-NLS-1$

	private static final String PROTECT = "-protect"; //$NON-NLS-1$
	//currently the only level of protection we care about.
	private static final String PROTECT_MASTER = "master"; //$NON-NLS-1$
	private static final String PROTECT_BASE = "base"; //$NON-NLS-1$

	private static final String LIBRARY = "--launcher.library"; //$NON-NLS-1$
	private static final String APPEND_VMARGS = "--launcher.appendvmargs"; //$NON-NLS-1$
	private static final String OVERRIDE_VMARGS = "--launcher.overridevmargs"; //$NON-NLS-1$
	private static final String NL = "-nl"; //$NON-NLS-1$
	private static final String ENDSPLASH = "-endsplash"; //$NON-NLS-1$
	private static final String[] SPLASH_IMAGES = { //
			"splash.png", //$NON-NLS-1$
			"splash.jpg", //$NON-NLS-1$
			"splash.jpeg", //$NON-NLS-1$
			"splash.gif", //$NON-NLS-1$
			"splash.bmp", //$NON-NLS-1$
	};
	private static final String CLEAN = "-clean"; //$NON-NLS-1$
	private static final String NOEXIT = "-noexit"; //$NON-NLS-1$
	private static final String OS = "-os"; //$NON-NLS-1$
	private static final String WS = "-ws"; //$NON-NLS-1$
	private static final String ARCH = "-arch"; //$NON-NLS-1$
	private static final String STARTUP = "-startup"; //$NON-NLS-1$

	private static final String OSGI = "org.eclipse.osgi"; //$NON-NLS-1$
	private static final String STARTER = "org.eclipse.core.runtime.adaptor.EclipseStarter"; //$NON-NLS-1$
	private static final String PLATFORM_URL = "platform:/base/"; //$NON-NLS-1$
	private static final String ECLIPSE_PROPERTIES = "eclipse.properties"; //$NON-NLS-1$
	private static final String FILE_SCHEME = "file:"; //$NON-NLS-1$
	private static final String REFERENCE_SCHEME = "reference:"; //$NON-NLS-1$
	private static final String JAR_SCHEME = "jar:"; //$NON-NLS-1$

	// constants: configuration file location
	private static final String CONFIG_DIR = "configuration/"; //$NON-NLS-1$
	private static final String CONFIG_FILE = "config.ini"; //$NON-NLS-1$
	private static final String CONFIG_FILE_TEMP_SUFFIX = ".tmp"; //$NON-NLS-1$
	private static final String CONFIG_FILE_BAK_SUFFIX = ".bak"; //$NON-NLS-1$
	private static final String ECLIPSE = "eclipse"; //$NON-NLS-1$
	private static final String PRODUCT_SITE_MARKER = ".eclipseproduct"; //$NON-NLS-1$
	private static final String PRODUCT_SITE_ID = "id"; //$NON-NLS-1$
	private static final String PRODUCT_SITE_VERSION = "version"; //$NON-NLS-1$

	// constants: System property keys and/or configuration file elements
	private static final String PROP_USER_HOME = "user.home"; //$NON-NLS-1$
	private static final String PROP_USER_DIR = "user.dir"; //$NON-NLS-1$
	private static final String PROP_INSTALL_AREA = "osgi.install.area"; //$NON-NLS-1$
	private static final String PROP_CONFIG_AREA = "osgi.configuration.area"; //$NON-NLS-1$
	private static final String PROP_CONFIG_AREA_DEFAULT = "osgi.configuration.area.default"; //$NON-NLS-1$
	private static final String PROP_BASE_CONFIG_AREA = "osgi.baseConfiguration.area"; //$NON-NLS-1$
	private static final String PROP_SHARED_CONFIG_AREA = "osgi.sharedConfiguration.area"; //$NON-NLS-1$
	private static final String PROP_CONFIG_CASCADED = "osgi.configuration.cascaded"; //$NON-NLS-1$
	protected static final String PROP_FRAMEWORK = "osgi.framework"; //$NON-NLS-1$
	private static final String PROP_SPLASHPATH = "osgi.splashPath"; //$NON-NLS-1$
	private static final String PROP_SPLASHLOCATION = "osgi.splashLocation"; //$NON-NLS-1$
	private static final String PROP_CLASSPATH = "osgi.frameworkClassPath"; //$NON-NLS-1$
	private static final String PROP_EXTENSIONS = "osgi.framework.extensions"; //$NON-NLS-1$
	private static final String PROP_FRAMEWORK_SYSPATH = "osgi.syspath"; //$NON-NLS-1$
	private static final String PROP_FRAMEWORK_SHAPE = "osgi.framework.shape"; //$NON-NLS-1$
	private static final String PROP_LOGFILE = "osgi.logfile"; //$NON-NLS-1$
	private static final String PROP_REQUIRED_JAVA_VERSION = "osgi.requiredJavaVersion"; //$NON-NLS-1$
	private static final String PROP_PARENT_CLASSLOADER = "osgi.parentClassloader"; //$NON-NLS-1$
	private static final String PROP_FRAMEWORK_PARENT_CLASSLOADER = "osgi.frameworkParentClassloader"; //$NON-NLS-1$
	private static final String PROP_NL = "osgi.nl"; //$NON-NLS-1$
	private static final String PROP_NOSHUTDOWN = "osgi.noShutdown"; //$NON-NLS-1$
	private static final String PROP_DEBUG = "osgi.debug"; //$NON-NLS-1$
	private static final String PROP_OS = "osgi.os"; //$NON-NLS-1$
	private static final String PROP_WS = "osgi.ws"; //$NON-NLS-1$
	private static final String PROP_ARCH = "osgi.arch"; //$NON-NLS-1$

	private static final String PROP_EXITCODE = "eclipse.exitcode"; //$NON-NLS-1$
	private static final String PROP_EXITDATA = "eclipse.exitdata"; //$NON-NLS-1$
	private static final String PROP_LAUNCHER = "eclipse.launcher"; //$NON-NLS-1$
	private static final String PROP_LAUNCHER_NAME = "eclipse.launcher.name"; //$NON-NLS-1$

	private static final String PROP_VM = "eclipse.vm"; //$NON-NLS-1$
	private static final String PROP_VMARGS = "eclipse.vmargs"; //$NON-NLS-1$
	private static final String PROP_COMMANDS = "eclipse.commands"; //$NON-NLS-1$
	private static final String PROP_ECLIPSESECURITY = "eclipse.security"; //$NON-NLS-1$

	// Suffix for location properties - see LocationManager.
	private static final String READ_ONLY_AREA_SUFFIX = ".readOnly"; //$NON-NLS-1$

	// Data mode constants for user, configuration and data locations.
	private static final String NONE = "@none"; //$NON-NLS-1$
	private static final String NO_DEFAULT = "@noDefault"; //$NON-NLS-1$
	private static final String USER_HOME = "@user.home"; //$NON-NLS-1$
	private static final String USER_DIR = "@user.dir"; //$NON-NLS-1$
	// Placeholder for hashcode of installation directory
	private static final String INSTALL_HASH_PLACEHOLDER = "@install.hash"; //$NON-NLS-1$
	private static final String LAUNCHER_DIR = "@launcher.dir"; //$NON-NLS-1$

	// types of parent classloaders the framework can have
	private static final String PARENT_CLASSLOADER_APP = "app"; //$NON-NLS-1$
	private static final String PARENT_CLASSLOADER_EXT = "ext"; //$NON-NLS-1$
	private static final String PARENT_CLASSLOADER_CURRENT = "current"; //$NON-NLS-1$

	// log file handling
	private static final String SESSION = "!SESSION"; //$NON-NLS-1$
	private static final String ENTRY = "!ENTRY"; //$NON-NLS-1$
	private static final String MESSAGE = "!MESSAGE"; //$NON-NLS-1$
	private static final String STACK = "!STACK"; //$NON-NLS-1$
	private static final int ERROR = 4;
	private static final String PLUGIN_ID = "org.eclipse.equinox.launcher"; //$NON-NLS-1$
	private File logFile = null;
	private BufferedWriter log = null;
	private boolean newSession = true;

	private boolean protectBase = false;

	// for variable substitution
	private static final String VARIABLE_DELIM_STRING = "$"; //$NON-NLS-1$
	private static final char VARIABLE_DELIM_CHAR = '$';

	//for change detection in the base when running in shared install mode
	private static final long NO_TIMESTAMP = -1;
	private static final String BASE_TIMESTAMP_FILE_CONFIGINI = ".baseConfigIniTimestamp"; //$NON-NLS-1$
	private static final String KEY_CONFIGINI_TIMESTAMP = "configIniTimestamp"; //$NON-NLS-1$
	private static final String PROP_IGNORE_USER_CONFIGURATION = "eclipse.ignoreUserConfiguration"; //$NON-NLS-1$

	private static final Set<PosixFilePermission> PERMISSION_755 = PosixFilePermissions.fromString("rwxr-xr-x"); //$NON-NLS-1$

	/**
	 * A structured form for a version identifier.
	 *
	 * @see "http://www.oracle.com/technetwork/java/javase/versioning-naming-139433.html for information on valid version strings"
	 * @see "http://openjdk.java.net/jeps/223 for information on the JavaSE-9 version JEP 223"
	 */
	private record Identifier(int major, int minor, int service, String qualifier) {

		/**
		 * @throws NumberFormatException if cannot parse the major and minor version components
		 */
		static Identifier create(String versionString, String delimiter) {
			StringTokenizer tokenizer = new StringTokenizer(versionString, delimiter);
			int major = 0, minor = 0, service = 0;
			String qualifier = ""; //$NON-NLS-1$
			// major
			if (tokenizer.hasMoreTokens()) {
				major = Integer.parseInt(tokenizer.nextToken());
			}

			try {
				// minor
				if (tokenizer.hasMoreTokens()) {
					minor = Integer.parseInt(tokenizer.nextToken());
				}

				// service
				if (tokenizer.hasMoreTokens()) {
					service = Integer.parseInt(tokenizer.nextToken());
				}
				// qualifier
				if (tokenizer.hasMoreTokens()) {
					qualifier = tokenizer.nextToken();
				}
			} catch (NumberFormatException nfe) {
				// ignore the minor and service qualifiers in that case and default to 0
				// this will allow us to tolerate other non-conventional version numbers
			}
			return new Identifier(major, minor, service, qualifier);
		}

		private static final Comparator<Identifier> MAIN_VERSION = Comparator. //
				comparingInt(Identifier::major) //
				.thenComparingInt(Identifier::minor) //
				.thenComparingInt(Identifier::service);
		private static final Comparator<Identifier> FULL_VERSION = MAIN_VERSION.thenComparing(Identifier::qualifier);
	}

	private String getWS() {
		if (ws != null) {
			return ws;
		}

		String osgiWs = System.getProperty(PROP_WS);
		if (osgiWs != null) {
			ws = osgiWs;
			return ws;
		}

		String osName = getOS();
		return switch (osName) {
			case Constants.OS_WIN32 -> Constants.WS_WIN32;
			case Constants.OS_LINUX -> Constants.WS_GTK;
			case Constants.OS_MACOSX -> Constants.WS_COCOA;
			case Constants.OS_HPUX -> Constants.WS_GTK;
			case Constants.OS_AIX -> Constants.WS_GTK;
			case Constants.OS_SOLARIS -> Constants.WS_GTK;
			case Constants.OS_QNX -> Constants.WS_PHOTON;
			default -> Constants.WS_UNKNOWN;
		};
	}

	private String getOS() {
		if (os != null) {
			return os;
		}
		String osgiOs = System.getProperty(PROP_OS);
		if (osgiOs != null) {
			os = osgiOs;
			return os;
		}
		String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT); //$NON-NLS-1$
		return switch (osName) {
			// EXCEPTION: All mappings of SunOS convert to Solaris
			case Constants.INTERNAL_OS_SUNOS -> Constants.OS_SOLARIS;
			case Constants.OS_LINUX -> Constants.OS_LINUX;
			case Constants.OS_QNX -> Constants.OS_QNX;
			case Constants.OS_AIX -> Constants.OS_AIX;
			case Constants.INTERNAL_OS_HPUX -> Constants.OS_HPUX;
			case Constants.OS_OS400 -> Constants.OS_OS400;
			case Constants.OS_OS390 -> Constants.OS_OS390;
			case Constants.OS_ZOS -> Constants.OS_ZOS;
			default -> {
				if (osName.startsWith(Constants.OS_WIN32.substring(0, 3))) {
					yield Constants.OS_WIN32;
				} else if (osName.startsWith(Constants.OS_MACOSX.substring(0, 3))) {
					// os.name on Mac OS can be either Mac OS or Mac OS X
					yield Constants.OS_MACOSX;
				}
				yield Constants.OS_UNKNOWN;
			}
		};
	}

	private String getArch() {
		if (arch != null) {
			return arch;
		}
		String osgiArch = System.getProperty(PROP_ARCH);
		if (osgiArch != null) {
			arch = osgiArch;
			return arch;
		}
		String name = System.getProperty("os.arch");//$NON-NLS-1$
		// Map amd64 architecture to x86_64
		if (name.equalsIgnoreCase(Constants.INTERNAL_AMD64)) {
			return Constants.ARCH_X86_64;
		}
		return name;
	}

	/**
	 *  Sets up the JNI bridge to native calls
	 */
	private void setupJNI(List<URL> defaultPath) {
		if (bridge != null) {
			return;
		}

		String libPath = null;

		if (library != null) {
			File lib = new File(library);
			if (lib.isDirectory()) {
				libPath = searchFor("eclipse", lib.getAbsolutePath()); //$NON-NLS-1$
			} else if (lib.exists()) {
				libPath = lib.getAbsolutePath();
			}
		}
		if (libPath == null) {
			//find our fragment name
			String fragmentName = String.join(".", PLUGIN_ID, getWS(), getOS(), getArch()); //$NON-NLS-1$
			libPath = getLibraryPath(fragmentName, defaultPath);
		}
		library = libPath;
		if (library != null) {
			bridge = new JNIBridge(library);
		}
	}

	private String getLibraryPath(String fragmentName, List<URL> defaultPath) {
		Stream<String> searchPath = Stream.empty();
		if (inDevelopmentMode && devClassPathProps != null) {
			String devPathList = devClassPathProps.getProperty(PLUGIN_ID);
			List<String> locations = getArrayFromList(devPathList);
			if (!locations.isEmpty()) {
				File location = new File(locations.get(0));
				if (location.isAbsolute()) {
					searchPath = Stream.of(location.getParent());
				}
			}
		}
		if (bootLocation != null && defaultPath != null && !defaultPath.isEmpty()) {
			searchPath = Stream.concat(searchPath, // the last one is most interesting
					IntStream.range(0, defaultPath.size()).map(i -> defaultPath.size() - 1 - i).mapToObj(defaultPath::get) //
							.map(url -> new File(url.getFile()).getParent()));
		}
		searchPath = Stream.concat(searchPath, //
				Stream.of(getInstallLocation().getFile() + "/plugins/")); //$NON-NLS-1$

		return searchPath.map(dir -> getLibraryFromFragment(fragmentName, dir)) //
				.filter(Objects::nonNull).findFirst().orElse(null);
	}

	private String getLibraryFromFragment(String fragmentName, String dir) {
		String fragment = searchFor(fragmentName, dir);
		if (fragment == null) {
			return null;
		}
		if (fragment.startsWith(FILE_SCHEME)) {
			fragment = fragment.substring(5);
		}

		File frag = new File(fragment);
		if (!frag.exists()) {
			return null;
		}

		if (frag.isDirectory()) {
			return searchFor("eclipse", fragment); //$NON-NLS-1$;
		}

		try (ZipFile fragmentJar = new ZipFile(frag)) {
			Optional<String> entry = fragmentJar.stream().map(ZipEntry::getName).filter(n -> n.startsWith("eclipse_")).findFirst(); //$NON-NLS-1$
			if (entry.isPresent()) {
				String lib = extractFromJAR(fragment, entry.get());
				if (!getOS().equals("win32")) { //$NON-NLS-1$
					try {
						Files.setPosixFilePermissions(Paths.get(lib), PERMISSION_755);
					} catch (Throwable e) {
						//ignore
					}
				}
				return lib;
			}
		} catch (IOException e) {
			log("Exception opening JAR file: " + fragment); //$NON-NLS-1$
			log(e);
			return null;
		}
		return null;
	}

	/**
	 * Executes the launch.
	 *
	 * @param args command-line arguments
	 * @exception Exception thrown if a problem occurs during the launch
	 */
	private void basicRun(String[] args) throws Exception {
		System.setProperty("eclipse.startTime", Long.toString(System.currentTimeMillis())); //$NON-NLS-1$
		commands = List.of(args);
		String[] passThruArgs = processCommandLine(commands).toArray(String[]::new);

		if (!debug) {
			// debug can be specified as system property as well
			debug = System.getProperty(PROP_DEBUG) != null;
		}
		setupVMProperties();
		processConfiguration();

		if (protectBase && (System.getProperty(PROP_SHARED_CONFIG_AREA) == null)) {
			System.err.println("This application is configured to run in a cascaded mode only."); //$NON-NLS-1$
			System.setProperty(PROP_EXITCODE, Integer.toString(14));
			return;
		}
		// need to ensure that getInstallLocation is called at least once to initialize the value.
		// Do this AFTER processing the configuration to allow the configuration to set
		// the install location.
		getInstallLocation();

		// locate boot plugin (may return -dev mode variations)
		List<URL> bootPath = getBootPath(bootLocation);

		//Set up the JNI bridge.  We need to know the install location to find the shared library
		setupJNI(bootPath);

		//ensure minimum Java version, do this after JNI is set up so that we can write an error message
		//with exitdata if we fail.
		if (!checkVersion(System.getProperty("java.version"), System.getProperty(PROP_REQUIRED_JAVA_VERSION))) { //$NON-NLS-1$
			return;
		}

		// verify configuration location is writable
		if (!checkConfigurationLocation(configurationLocation)) {
			return;
		}
		URL[] bootPathURLs = bootPath.toArray(URL[]::new);
		setSecurityPolicy(bootPathURLs);
		// splash handling is done here, because the default case needs to know
		// the location of the boot plugin we are going to use
		handleSplash();

		invokeFramework(passThruArgs, bootPathURLs);
	}

	@SuppressWarnings({"removal"})
	private void setSecurityPolicy(URL[] bootPath) {
		String eclipseSecurity = System.getProperty(PROP_ECLIPSESECURITY);
		if (eclipseSecurity != null) {
			// setup a policy that grants the launcher and path for the framework AllPermissions.
			// Do not set the security manager, this will be done by the framework itself.
			ProtectionDomain domain = Main.class.getProtectionDomain();
			CodeSource source = null;
			if (domain != null) {
				source = Main.class.getProtectionDomain().getCodeSource();
			}
			if (domain == null || source == null) {
				log("Can not automatically set the security manager. Please use a policy file."); //$NON-NLS-1$
				return;
			}
			// get the list of codesource URLs to grant AllPermission to
			List<URL> rootURLs = new ArrayList<>(bootPath.length + 1);
			rootURLs.add(source.getLocation());
			Collections.addAll(rootURLs, bootPath);
			// replace the security policy
			Policy eclipsePolicy = new EclipsePolicy(Policy.getPolicy(), rootURLs);
			Policy.setPolicy(eclipsePolicy);
		}
	}

	private void invokeFramework(String[] passThruArgs, URL[] bootPath) throws Exception {
		// Workaround for bug 466683. Some org.w3c.dom.* packages that used to be available from
		// JavaSE's boot classpath are only available from the extension path in Java 9 b62.
		// Workaround for bug 489958. javax.annotation.* types are only available from
		// JavaSE-9's extension path in Java 9-ea+108. The identifier "1.9" could be changed to "9", but "1.9" works just as well.
		String type = PARENT_CLASSLOADER_EXT;
		type = System.getProperty(PROP_PARENT_CLASSLOADER, type);
		type = System.getProperty(PROP_FRAMEWORK_PARENT_CLASSLOADER, type);
		ClassLoader parent = null;
		if (PARENT_CLASSLOADER_APP.equalsIgnoreCase(type)) {
			parent = ClassLoader.getSystemClassLoader();
		} else if (PARENT_CLASSLOADER_EXT.equalsIgnoreCase(type)) {
			ClassLoader appCL = ClassLoader.getSystemClassLoader();
			if (appCL != null) {
				parent = appCL.getParent();
			}
		} else if (PARENT_CLASSLOADER_CURRENT.equalsIgnoreCase(type)) {
			parent = this.getClass().getClassLoader();
		}
		@SuppressWarnings("resource")
		URLClassLoader loader = new StartupClassLoader(bootPath, parent);
		Class<?> clazz = loader.loadClass(STARTER);
		Method method = clazz.getDeclaredMethod("run", String[].class, Runnable.class); //$NON-NLS-1$
		try {
			method.invoke(clazz, passThruArgs, splashHandler);
		} catch (InvocationTargetException e) {
			if (e.getTargetException() instanceof Error error) {
				throw error;
			} else if (e.getTargetException() instanceof Exception exception) {
				throw exception;
			} else {
				//could be a subclass of Throwable!
				throw e;
			}
		}
	}

	/**
	 * Checks whether the given available version is greater or equal to the
	 * given required version.
	 * <p>Will set PROP_EXITCODE/PROP_EXITDATA accordingly if check fails.</p>
	 *
	 * @return a boolean indicating whether the checking passed
	 */
	private boolean checkVersion(String availableVersion, String requiredVersion) {
		if (requiredVersion == null || availableVersion == null) {
			return true;
		}
		try {
			String delimiter = ". _-"; //$NON-NLS-1$
			Identifier required = Identifier.create(requiredVersion, delimiter);
			Identifier available = Identifier.create(availableVersion, delimiter);
			boolean compatible = Identifier.MAIN_VERSION.compare(available, required) >= 0;
			if (!compatible) {
				// any non-zero value should do it - 14 used to be used for version incompatibility in Eclipse 2.1
				System.setProperty(PROP_EXITCODE, "14"); //$NON-NLS-1$
				System.setProperty(PROP_EXITDATA, "<title>Incompatible JVM</title>Version " + availableVersion + " of the JVM is not suitable for this product. Version: " + requiredVersion + " or greater is required."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			return compatible;
		} catch (SecurityException | NumberFormatException e) {
			// If the security manager won't allow us to get the system property, continue for
			// now and let things fail later on their own if necessary.
			// If the version string was in a format that we don't understand, continue and
			// let things fail later on their own if necessary.
			return true;
		}
	}

	/**
	 * Checks whether the given location can be created and is writable.
	 * If the system property "osgi.configuration.area.readOnly" is set
	 * the check always succeeds.
	 * <p>Will set PROP_EXITCODE/PROP_EXITDATA accordingly if check fails.</p>
	 *
	 * @param locationUrl  configuration area URL, may be <code>null</code>
	 * @return a boolean indicating whether the checking passed
	 */
	private boolean checkConfigurationLocation(URL locationUrl) {
		if (locationUrl == null || !"file".equals(locationUrl.getProtocol())) { //$NON-NLS-1$
			return true;
		}
		if (Boolean.parseBoolean(System.getProperty(PROP_CONFIG_AREA + READ_ONLY_AREA_SUFFIX))) {
			// user wants readonly config area
			return true;
		}
		File configDir = new File(locationUrl.getFile()).getAbsoluteFile();
		if (!configDir.exists()) {
			configDir.mkdirs();
			if (!configDir.exists()) {
				System.setProperty(PROP_EXITCODE, "15"); //$NON-NLS-1$
				System.setProperty(PROP_EXITDATA, "<title>Invalid Configuration Location</title>The configuration area at '" + configDir + //$NON-NLS-1$
						"' could not be created.  Please choose a writable location using the '-configuration' command line option."); //$NON-NLS-1$
				return false;
			}
		}
		if (!canWrite(configDir, getOS())) {
			System.setProperty(PROP_EXITCODE, "15"); //$NON-NLS-1$
			System.setProperty(PROP_EXITDATA, "<title>Invalid Configuration Location</title>The configuration area at '" + configDir + //$NON-NLS-1$
					"' is not writable.  Please choose a writable location using the '-configuration' command line option."); //$NON-NLS-1$
			return false;
		}
		return true;
	}

	/**
	 * Returns a string representation of the given URL String.  This converts
	 * escaped sequences (%..) in the URL into the appropriate characters.
	 * NOTE: due to class visibility there is a copy of this method
	 *       in InternalBootLoader
	 */
	private String decode(String urlString) {
		try {
			//first encode '+' characters, because URLDecoder incorrectly converts
			//them to spaces on certain class library implementations.
			if (urlString.indexOf('+') >= 0) {
				int len = urlString.length();
				StringBuilder buf = new StringBuilder(len);
				for (int i = 0; i < len; i++) {
					char c = urlString.charAt(i);
					if (c == '+') {
						buf.append("%2B"); //$NON-NLS-1$
					} else {
						buf.append(c);
					}
				}
				urlString = buf.toString();
			}
			return URLDecoder.decode(urlString, "UTF-8"); //$NON-NLS-1$
		} catch (UnsupportedEncodingException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	private static final Pattern COMMA = Pattern.compile(","); //$NON-NLS-1$

	/**
	 * Returns the result of converting a list of comma-separated tokens into an array
	 *
	 * @return the array of string tokens
	 * @param prop the initial comma-separated string
	 */
	private List<String> getArrayFromList(String prop) {
		if (prop == null || prop.isBlank()) {
			return List.of();
		}
		return COMMA.splitAsStream(prop) //
				.map(String::trim).filter(s -> !s.isEmpty()) //
				.toList();
	}

	/**
	 * Returns the <code>URL</code>-based class path describing where the boot classes
	 * are located when running in development mode.
	 *
	 * @return the url-based class path
	 * @param base the base location
	 * @exception MalformedURLException if a problem occurs computing the class path
	 */
	private List<URL> getDevPath(URL base) throws IOException {
		List<URL> result = new ArrayList<>(5);
		if (inDevelopmentMode) {
			addDevEntries(base, result, OSGI);
		}
		//The jars from the base always need to be added, even when running in dev mode (bug 46772)
		addBaseJars(base, result);
		return result;
	}

	private URL constructURL(URL url, String name) {
		//Recognize the following URLs
		//url: file:foo/dir/
		//url: file:foo/file.jar

		String externalForm = url.toExternalForm();
		if (externalForm.endsWith(".jar")) { //$NON-NLS-1$
			try {
				return new URL(JAR_SCHEME + url + "!/" + name); //$NON-NLS-1$
			} catch (MalformedURLException e) {
				//Ignore
			}
		}

		try {
			return new URL(url, name);
		} catch (MalformedURLException e) {
			//Ignore
			return null;
		}
	}

	private void readFrameworkExtensions(URL base, List<URL> result) throws IOException {
		List<String> extensions = getArrayFromList(System.getProperty(PROP_EXTENSIONS));
		String parent = new File(base.getFile()).getParent();
		List<String> extensionResults = new ArrayList<>(extensions.size());
		for (String extension : extensions) {
			//Search the extension relatively to the osgi plugin
			String path = searchForBundle(extension, parent);
			if (path == null) {
				log("Could not find extension: " + extension); //$NON-NLS-1$
				continue;
			}
			if (debug) {
				System.out.println("Loading extension: " + extension); //$NON-NLS-1$
			}
			URL extensionURL = null;
			if (installLocation.getProtocol().equals("file")) { //$NON-NLS-1$
				extensionResults.add(path);
				extensionURL = new File(path).toURL();
			} else {
				extensionURL = new URL(installLocation.getProtocol(), installLocation.getHost(), installLocation.getPort(), path);
			}
			//Load a property file of the extension, merge its content, and in case of dev mode add the bin entries
			Properties extensionProperties = null;
			try {
				extensionProperties = loadProperties(constructURL(extensionURL, ECLIPSE_PROPERTIES));
			} catch (IOException e) {
				if (debug) {
					System.out.println("\t" + ECLIPSE_PROPERTIES + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			String extensionClassPath = null;
			if (extensionProperties != null) {
				extensionClassPath = extensionProperties.getProperty(PROP_CLASSPATH);
			} else {
				// this is a "normal" RFC 101 framework extension bundle just put the base path on the classpath
				extensionProperties = new Properties();
			}
			List<String> entries = extensionClassPath == null || extensionClassPath.isEmpty() ? List.of("") : getArrayFromList(extensionClassPath); //$NON-NLS-1$
			String qualifiedPath = System.getProperty(PROP_CLASSPATH) == null ? "." : ""; //$NON-NLS-1$ //$NON-NLS-2$
			for (String entry : entries) {
				qualifiedPath += ", " + FILE_SCHEME + path + entry; //$NON-NLS-1$
			}
			extensionProperties.put(PROP_CLASSPATH, qualifiedPath);
			mergeWithSystemProperties(extensionProperties, null);
			if (inDevelopmentMode) {
				String name = extension;
				if (name.startsWith(REFERENCE_SCHEME)) {
					// need to extract the BSN from the path
					name = new File(path).getName();
					// Note that we do not extract any version information.
					// We assume the extension is located in the workspace in a project
					// that has the same name as the BSN.
					// We could add more logic here to support versions in project folder names
					// but it will likely be complicated and error prone.
				}
				addDevEntries(extensionURL, result, name);
			}
		}
		extensionPaths = extensionResults;
	}

	private void addBaseJars(URL base, List<URL> result) throws IOException {
		String baseJarList = System.getProperty(PROP_CLASSPATH);
		if (baseJarList == null) {
			readFrameworkExtensions(base, result);
			baseJarList = System.getProperty(PROP_CLASSPATH);
		}

		File fwkFile = new File(base.getFile());
		boolean fwkIsDirectory = fwkFile.isDirectory();
		//We found where the fwk is, remember it and its shape
		if (fwkIsDirectory) {
			System.setProperty(PROP_FRAMEWORK_SHAPE, "folder");//$NON-NLS-1$
		} else {
			System.setProperty(PROP_FRAMEWORK_SHAPE, "jar");//$NON-NLS-1$
		}
		String fwkPath = new File(new File(base.getFile()).getParent()).getAbsolutePath();
		if (Character.isUpperCase(fwkPath.charAt(0))) {
			fwkPath = Character.toLowerCase(fwkPath.charAt(0)) + fwkPath.substring(1);
		}
		System.setProperty(PROP_FRAMEWORK_SYSPATH, fwkPath);

		List<String> baseJars = getArrayFromList(baseJarList);
		if (baseJars.isEmpty()) {
			if (!inDevelopmentMode && new File(base.getFile()).isDirectory()) {
				throw new IOException("Unable to initialize " + PROP_CLASSPATH); //$NON-NLS-1$
			}
			addEntry(base, result);
			return;
		}
		for (String string : baseJars) {
			try {
				// if the string is a file: URL then *carefully* construct the
				// URL. Otherwisejust try to build a URL. In either case, if we fail, use
				// string as something to tack on the end of the base.

				if (string.equals(".")) { //$NON-NLS-1$
					addEntry(base, result);
				}
				URL url = null;
				if (string.startsWith(FILE_SCHEME)) {
					url = new File(string.substring(5)).toURL();
				} else {
					url = new URL(string);
				}
				addEntry(url, result);
			} catch (MalformedURLException e) {
				addEntry(new URL(base, string), result);
			}
		}
	}

	private void addEntry(URL url, List<URL> result) {
		if (new File(url.getFile()).exists()) {
			result.add(url);
		}
	}

	private void addDevEntries(URL base, List<URL> result, String symbolicName) throws MalformedURLException {
		if (devClassPathProps == null) {
			return; // do nothing
		}
		String devPathList = devClassPathProps.getProperty(symbolicName);
		if (devPathList == null) {
			devPathList = devClassPathProps.getProperty("*"); //$NON-NLS-1$
		}
		List<String> locations = getArrayFromList(devPathList);
		for (String location : locations) {
			File path = new File(location);
			URL url;
			if (path.isAbsolute()) {
				url = path.toURL();
			} else {
				// dev path is relative, combine with base location
				char lastChar = location.charAt(location.length() - 1);
				if ((location.endsWith(".jar") || (lastChar == '/' || lastChar == '\\'))) { //$NON-NLS-1$
					url = new URL(base, location);
				} else {
					url = new URL(base, location + "/"); //$NON-NLS-1$
				}
			}
			addEntry(url, result);
		}
	}

	/**
	 * Returns the <code>URL</code>-based class path describing where the boot classes are located.
	 *
	 * @return the url-based class path
	 * @param base the base location
	 * @exception MalformedURLException if a problem occurs computing the class path
	 */
	private List<URL> getBootPath(String base) throws IOException {
		URL url = null;
		if (base != null) {
			url = buildURL(base, true);
		} else {
			// search in the root location
			url = getInstallLocation();
			String pluginsLocation = new File(url.getFile(), "plugins").toString(); //$NON-NLS-1$
			String path = searchFor(framework, pluginsLocation);
			if (path == null) {
				throw new FileNotFoundException(String.format("Could not find framework under %s", pluginsLocation)); //$NON-NLS-1$
			}
			if (url.getProtocol().equals("file")) { //$NON-NLS-1$
				url = new File(path).toURL();
			} else {
				url = new URL(url.getProtocol(), url.getHost(), url.getPort(), path);
			}
		}
		if (System.getProperty(PROP_FRAMEWORK) == null) {
			System.setProperty(PROP_FRAMEWORK, url.toExternalForm());
		}
		if (debug) {
			System.out.println("Framework located:\n    " + url.toExternalForm()); //$NON-NLS-1$
		}
		// add on any dev path elements
		List<URL> result = getDevPath(url);
		if (debug) {
			System.out.println("Framework classpath:"); //$NON-NLS-1$
			for (URL devPath : result) {
				System.out.println("    " + devPath.toExternalForm()); //$NON-NLS-1$
			}
		}
		return result;
	}

	/**
	 * Searches for the given target directory starting in the "plugins" subdirectory
	 * of the given location.
	 *
	 * @return the location where target directory was found, <code>null</code> otherwise
	 * @param start the location to begin searching
	 */
	private String searchFor(final String target, String start) {
		File root = resolveFile(new File(start));

		// Note that File.list only gives you file names not the complete path from start
		String[] candidates = root.list();
		if (candidates == null) {
			return null;
		}

		List<String> matches = new ArrayList<>(2);
		for (String candidate : candidates) {
			if (isMatchingCandidate(target, candidate, root)) {
				matches.add(candidate);
			}
		}
		return findMax(target, matches).map(name -> {
			File candidate = new File(root, name);
			return candidate.getAbsolutePath().replace(File.separatorChar, '/') + (candidate.isDirectory() ? "/" : ""); //$NON-NLS-1$//$NON-NLS-2$
		}).orElse(null);
	}

	private boolean isMatchingCandidate(String target, String candidate, File root) {
		if (candidate.equals(target)) {
			return true;
		}
		if (!candidate.startsWith(target + "_")) { //$NON-NLS-1$
			return false;
		}
		int targetLength = target.length();
		int lastUnderscore = candidate.lastIndexOf('_');

		//do we have a second '_', version (foo_1.0.0.v1_123) or id (foo.x86_64) ?
		//files are assumed to have an extension (zip or jar only), remove it
		//NOTE: we only remove .zip and .jar extensions because we still need to accept libraries with
		//simple versions (e.g. eclipse_1234.dll)
		File candidateFile = new File(root, candidate);
		if (candidateFile.isFile() && (candidate.endsWith(".jar") || candidate.endsWith(".zip"))) { //$NON-NLS-1$//$NON-NLS-2$
			int extension = candidate.lastIndexOf('.');
			candidate = candidate.substring(0, extension);
		}

		int lastDot = candidate.lastIndexOf('.');
		if (lastDot < targetLength) {
			// no dots after target, the '_' is not in a version (foo.x86_64 case), not a match
			return false;
		}

		//get past all '_' that are part of the qualifier
		while (lastUnderscore > lastDot) {
			lastUnderscore = candidate.lastIndexOf('_', lastUnderscore - 1);
		}

		if (lastUnderscore == targetLength) {
			return true; //underscore at the end of target (foo_1.0.0.v1_123 case)
		}
		return false; //another underscore between target and version (foo_64_1.0.0.v1_123 case)
	}

	private String searchForBundle(String target, String start) {
		//Only handle "reference:file:" urls, and not simple "file:" because we will be using the jar wherever it is.
		if (target.startsWith(REFERENCE_SCHEME)) {
			target = target.substring(REFERENCE_SCHEME.length());
			if (!target.startsWith(FILE_SCHEME)) {
				throw new IllegalArgumentException("Bundle URL is invalid: " + target); //$NON-NLS-1$
			}
			target = target.substring(FILE_SCHEME.length());
			File child = new File(target);
			File fileLocation = child;
			if (!child.isAbsolute()) {
				File parent = resolveFile(new File(start));
				fileLocation = new File(parent, child.getPath());
			}
			return searchFor(fileLocation.getName(), fileLocation.getParentFile().getAbsolutePath());
		}
		return searchFor(target, start);
	}

	private Optional<String> findMax(String prefix, List<String> candidates) {
		return candidates.stream().map(name -> {
			String version = ""; //$NON-NLS-1$ // Note: directory with version suffix is always > than directory without version suffix
			if (prefix == null) {
				version = name; //webstart just passes in versions
			} else if (name.startsWith(prefix + "_")) { //$NON-NLS-1$
				version = name.substring(prefix.length() + 1); //prefix_version
			}
			Identifier currentVersion = getVersionElements(version);
			return Map.entry(name, currentVersion);
		}).max(Comparator.comparing(Map.Entry::getValue, Identifier.FULL_VERSION)).map(Map.Entry::getKey);
	}

	/**
	 * Do a quick parse of version identifier so its elements can be correctly compared.
	 * If we are unable to parse the full version, remaining elements are initialized
	 * with suitable defaults.
	 * @return an array of size 4; first three elements are of type Integer (representing
	 * major, minor and service) and the fourth element is of type String (representing
	 * qualifier). Note, that returning anything else will cause exceptions in the caller.
	 */
	private Identifier getVersionElements(String version) {
		if (version.endsWith(".jar")) { //$NON-NLS-1$
			version = version.substring(0, version.length() - 4);
		}
		try {
			return Identifier.create(version, "."); //$NON-NLS-1$
		} catch (NumberFormatException e) {
			return new Identifier(0, 0, 0, ""); //$NON-NLS-1$
		}
	}

	private static URL buildURL(String spec, boolean trailingSlash) {
		if (spec == null) {
			return null;
		}
		if (File.separatorChar == '\\') {
			spec = spec.trim();
		}
		boolean isFile = spec.startsWith(FILE_SCHEME);
		try {
			if (isFile) {
				File toAdjust = toFileURL(spec);
				toAdjust = resolveFile(toAdjust);
				if (toAdjust.isDirectory()) {
					return adjustTrailingSlash(toAdjust.toURL(), trailingSlash);
				}
				return toAdjust.toURL();
			}
			return new URL(spec);
		} catch (MalformedURLException e) {
			// if we failed and it is a file spec, there is nothing more we can do
			// otherwise, try to make the spec into a file URL.
			if (isFile) {
				return null;
			}
			try {
				File toAdjust = new File(spec);
				if (toAdjust.isDirectory()) {
					return adjustTrailingSlash(toAdjust.toURL(), trailingSlash);
				}
				return toAdjust.toURL();
			} catch (MalformedURLException e1) {
				return null;
			}
		}
	}

	private static File toFileURL(String spec) {
		try {
			// Try to build it from a URI that will be properly decoded.
			return new File(new URI(spec));
		} catch (URISyntaxException | IllegalArgumentException e) {
			return new File(spec.substring(5));
		}
	}

	/**
	 * Resolve the given file against  osgi.install.area.
	 * If osgi.install.area is not set, or the file is not relative, then
	 * the file is returned as is.
	 */
	private static File resolveFile(File toAdjust) {
		if (!toAdjust.isAbsolute()) {
			String installArea = System.getProperty(PROP_INSTALL_AREA);
			if (installArea != null) {
				if (installArea.startsWith(FILE_SCHEME)) {
					toAdjust = new File(installArea.substring(5), toAdjust.getPath());
				} else if (new File(installArea).exists()) {
					toAdjust = new File(installArea, toAdjust.getPath());
				}
			}
		}
		return toAdjust;
	}

	private static URL adjustTrailingSlash(URL url, boolean trailingSlash) throws MalformedURLException {
		String file = url.getFile();
		if (trailingSlash == (file.endsWith("/"))) { //$NON-NLS-1$
			return url;
		}
		file = trailingSlash ? file + "/" : file.substring(0, file.length() - 1); //$NON-NLS-1$
		return new URL(url.getProtocol(), url.getHost(), file);
	}

	private URL buildLocation(String property) {
		URL result = null;
		String location = System.getProperty(property);
		System.clearProperty(property);
		// if the instance location is not set, predict where the workspace will be and
		// put the instance area inside the workspace meta area.
		try {
			if (location == null) {
				return null;
			} else if (location.equalsIgnoreCase(NONE)) {
				return null;
			} else if (location.equalsIgnoreCase(NO_DEFAULT)) {
				result = buildURL(location, true);
			} else {
				if (location.startsWith(USER_HOME)) {
					String base = substituteVar(location, USER_HOME, PROP_USER_HOME);
					location = new File(base).getAbsolutePath();
				} else if (location.startsWith(USER_DIR)) {
					String base = substituteVar(location, USER_DIR, PROP_USER_DIR);
					location = new File(base).getAbsolutePath();
				}
				int idx = location.indexOf(INSTALL_HASH_PLACEHOLDER);
				if (idx == 0) {
					throw new RuntimeException("The location cannot start with '" + INSTALL_HASH_PLACEHOLDER + "': " + location); //$NON-NLS-1$ //$NON-NLS-2$
				} else if (idx > 0) {
					location = location.substring(0, idx) + getInstallDirHash() + location.substring(idx + INSTALL_HASH_PLACEHOLDER.length());
				}
				result = buildURL(location, true);
			}
		} finally {
			if (result != null) {
				System.setProperty(property, result.toExternalForm());
			}
		}
		return result;
	}

	private String substituteVar(String source, String var, String prop) {
		String value = System.getProperty(prop, ""); //$NON-NLS-1$
		return value + source.substring(var.length());
	}

	/**
	 * Retuns the default file system path for the configuration location.
	 * By default the configuration information is in the installation directory
	 * if this is writeable.  Otherwise it is located somewhere in the user.home
	 * area relative to the current product.
	 * @return the default file system path for the configuration information
	 */
	private String computeDefaultConfigurationLocation() {
		// 1) We store the config state relative to the 'eclipse' directory if possible
		// 2) If this directory is read-only
		//    we store the state in <user.home>/.eclipse/<application-id>_<version> where <user.home>
		//    is unique for each local user, and <application-id> is the one
		//    defined in .eclipseproduct marker file. If .eclipseproduct does not
		//    exist, use "eclipse" as the application-id.

		URL install = getInstallLocation();
		if (protectBase) {
			return computeDefaultUserAreaLocation(CONFIG_DIR);
		}

		// TODO a little dangerous here.  Basically we have to assume that it is a file URL.
		if (install.getProtocol().equals("file")) { //$NON-NLS-1$
			File installDir = new File(install.getFile());
			if (canWrite(installDir, getOS())) {
				return installDir.getAbsolutePath() + File.separator + CONFIG_DIR;
			}
		}
		// We can't write in the eclipse install dir so try for some place in the user's home dir
		return computeDefaultUserAreaLocation(CONFIG_DIR);
	}

	private static boolean canWrite(File installDir, String os) {
		if (!installDir.isDirectory()) {
			return false;
		}

		if (Files.isWritable(installDir.toPath())) {
			return true;
		} else if (Constants.OS_ZOS.equals(os)) {
			// For z/OS avoid doing the windows specific .dll check below.
			// This causes additional alarms on z/OS for unauthorized attempts to write.
			return false;
		}

		File fileTest = null;
		try {
			// we use the .dll suffix to properly test on Vista virtual directories
			// on Vista you are not allowed to write executable files on virtual directories like "Program Files"
			fileTest = File.createTempFile("writableArea", ".dll", installDir); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IOException e) {
			//If an exception occured while trying to create the file, it means that it is not writtable
			return false;
		} finally {
			if (fileTest != null) {
				fileTest.delete();
			}
		}
		return true;
	}

	/**
	 * Returns a files system path for an area in the user.home region related to the
	 * current product.  The given appendage is added to this base location
	 * @param pathAppendage the path segments to add to computed base
	 * @return a file system location in the user.home area related the the current
	 *   product and the given appendage
	 */
	private String computeDefaultUserAreaLocation(String pathAppendage) {
		//    we store the state in <user.home>/.eclipse/<application-id>_<version> where <user.home>
		//    is unique for each local user, and <application-id> is the one
		//    defined in .eclipseproduct marker file. If .eclipseproduct does not
		//    exist, use "eclipse" as the application-id.
		URL installURL = getInstallLocation();
		if (installURL == null) {
			return null;
		}
		File installDir = new File(installURL.getFile());
		String installDirHash = getInstallDirHash();

		if (protectBase && Constants.OS_MACOSX.equals(getOS())) {
			initializeBridgeEarly();
			String macConfiguration = computeConfigurationLocationForMacOS();
			if (macConfiguration != null) {
				return macConfiguration;
			}
			if (debug) {
				System.out.println("Computation of Mac specific configuration folder failed."); //$NON-NLS-1$
			}
		}

		String appName = "." + ECLIPSE; //$NON-NLS-1$
		File eclipseProduct = new File(installDir, PRODUCT_SITE_MARKER);
		if (eclipseProduct.exists()) {
			Properties props = new Properties();
			try (FileInputStream inStream = new FileInputStream(eclipseProduct)) {
				props.load(inStream);
				String appId = props.getProperty(PRODUCT_SITE_ID);
				if (appId == null || appId.isBlank()) {
					appId = ECLIPSE;
				}
				String appVersion = props.getProperty(PRODUCT_SITE_VERSION);
				if (appVersion == null || appVersion.isBlank()) {
					appVersion = ""; //$NON-NLS-1$
				}
				appName += File.separator + appId + "_" + appVersion + "_" + installDirHash; //$NON-NLS-1$ //$NON-NLS-2$
			} catch (IOException e) {
				// Do nothing if we get an exception.  We will default to a standard location
				// in the user's home dir.
				// add the hash to help prevent collisions
				appName += File.separator + installDirHash;
			}
		} else {
			// add the hash to help prevent collisions
			appName += File.separator + installDirHash;
		}
		appName += '_' + OS_WS_ARCHToString();
		String userHome = System.getProperty(PROP_USER_HOME);
		return new File(userHome, appName + "/" + pathAppendage).getAbsolutePath(); //$NON-NLS-1$
	}

	private String computeConfigurationLocationForMacOS() {
		if (bridge != null) {
			String folder = bridge.getOSRecommendedFolder();
			if (debug) {
				System.out.println("App folder provided by MacOS is: " + folder); //$NON-NLS-1$
			}
			if (folder != null) {
				return folder + '/' + CONFIG_DIR;
			}
		}
		return null;
	}

	private String OS_WS_ARCHToString() {
		return getOS() + '_' + getWS() + '_' + getArch();
	}

	private void initializeBridgeEarly() {
		setupJNI(null);
	}

	/**
	 * Return hash code identifying an absolute installation path
	 * @return hash code as String
	 */
	private String getInstallDirHash() {
		// compute an install dir hash to prevent configuration area collisions with other eclipse installs
		URL installURL = getInstallLocation();
		if (installURL == null) {
			return ""; //$NON-NLS-1$
		}
		File installDir = new File(installURL.getFile());
		int hashCode;
		try {
			hashCode = installDir.getCanonicalPath().hashCode();
		} catch (IOException ioe) {
			// fall back to absolute path
			hashCode = installDir.getAbsolutePath().hashCode();
		}
		if (hashCode < 0) {
			hashCode = -(hashCode);
		}
		return String.valueOf(hashCode);
	}

	/**
	 * Runs this launcher with the arguments specified in the given string.
	 *
	 * @param argString the arguments string
	 */
	public static void main(String argString) {
		main(argString.split(" ")); //$NON-NLS-1$
	}

	/**
	 * Runs the platform with the given arguments.  The arguments must identify
	 * an application to run (e.g., <code>-application com.example.application</code>).
	 * After running the application <code>System.exit(N)</code> is executed.
	 * The value of N is derived from the value returned from running the application.
	 * If the application's return value is an <code>Integer</code>, N is this value.
	 * In all other cases, N = 0.
	 * <p>
	 * Clients wishing to run the platform without a following <code>System.exit</code>
	 * call should use <code>run()</code>.
	 * </p>
	 *
	 * @param args the command line arguments
	 * @see #run(String[])
	 */
	public static void main(String[] args) {
		int result = 0;
		try {
			result = new Main().run(args);
		} catch (Throwable t) {
			// This is *really* unlikely to happen - run() takes care of exceptional situations.
			// In case something weird happens, just dump stack - logging is not available at this point
			t.printStackTrace();
		} finally {
			// If the return code is 23, that means that Equinox requested a restart.
			// In order to distinguish the request for a restart, do a System.exit(23)
			// no matter of 'osgi.noShutdown' runtime property value.
			if (!Boolean.getBoolean(PROP_NOSHUTDOWN) || result == 23) {
				// make sure we always terminate the VM
				System.exit(result);
			}
		}
	}

	/**
	 * Runs the platform with the given arguments.  The arguments must identify
	 * an application to run (e.g., <code>-application com.example.application</code>).
	 * Returns the value returned from running the application.
	 * If the application's return value is an <code>Integer</code>, N is this value.
	 * In all other cases, N = 0.
	 *
	 * @param args the command line arguments
	 */
	public int run(String[] args) {
		int result = 0;
		try {
			basicRun(args);
			String exitCode = System.getProperty(PROP_EXITCODE);
			try {
				result = exitCode == null ? 0 : Integer.parseInt(exitCode);
			} catch (NumberFormatException e) {
				result = 17;
			}
		} catch (Throwable e) {
			// only log the exceptions if they have not been caught by the
			// EclipseStarter (i.e., if the exitCode is not 13)
			if (!"13".equals(System.getProperty(PROP_EXITCODE))) { //$NON-NLS-1$
				log("Exception launching the Eclipse Platform:"); //$NON-NLS-1$
				log(e);
				String message = "An error has occurred"; //$NON-NLS-1$
				if (logFile == null) {
					message += " and could not be logged: \n" + e.getMessage(); //$NON-NLS-1$
				} else {
					message += ".  See the log file\n" + logFile.getAbsolutePath(); //$NON-NLS-1$
				}
				System.setProperty(PROP_EXITDATA, message);
			} else {
				// we have an exit code of 13, in most cases the user tries to start a 32/64 bit Eclipse
				// on a 64/32 bit Eclipse
				log("Are you trying to start an 64/32-bit Eclipse on a 32/64-JVM? These must be the same, as Eclipse uses native code."); //$NON-NLS-1$
			}
			// Return "unlucky" 13 as the exit code. The executable will recognize
			// this constant and display a message to the user telling them that
			// there is information in their log file.
			result = 13;
		} finally {
			// always try putting down the splash screen just in case the application failed to do so
			takeDownSplash();
		}
		// Return an int exit code and ensure the system property is set.
		System.setProperty(PROP_EXITCODE, Integer.toString(result));
		setExitData();
		return result;
	}

	private void setExitData() {
		String data = System.getProperty(PROP_EXITDATA);
		if (data == null) {
			return;
		}
		//if the bridge is null then we have nothing to send the data to;
		//exitData is a shared memory id, if we loaded the library from java, we need a non-null exitData
		//if the executable loaded the library, then we don't need the exitData id
		if (bridge == null || (bridge.isLibraryLoadedByJava() && exitData == null)) {
			System.out.println(data);
		} else {
			bridge.setExitData(exitData, data);
		}
	}

	/**
	 * Processes the command line arguments. The general principle is to NOT
	 * consume the arguments and leave them to be processed by Eclipse proper.
	 * There are a few args which are directed towards main() and a few others
	 * which we need to know about. Very few should actually be consumed here.
	 *
	 * @return the arguments to pass through to the launched application
	 * @param args the command line arguments
	 */
	private List<String> processCommandLine(List<String> argsList) {
		Queue<String> args = new ArrayDeque<>(argsList);
		List<String> passThrough = new ArrayList<>();
		while (!args.isEmpty()) {
			// check for args without parameters (i.e., a flag arg)
			// check if debug should be enabled for the entire platform
			String key = args.remove();
			if (!key.startsWith("-")) { //$NON-NLS-1$
				passThrough.add(key);
				continue;
			}
			boolean processed = switch (key.toLowerCase(Locale.ROOT)) {
				case DEBUG -> {
					debug = true;
					passThrough.add(key); // pass thru this arg
					yield true;
				}
				case NOSPLASH -> { // look for and consume the nosplash directive.
					// This supercedes any -showsplash command that might be present.
					splashDown = true;
					yield true;
				}
				case NOEXIT -> {
					System.setProperty(PROP_NOSHUTDOWN, "true"); //$NON-NLS-1$
					yield true;
				}
				case APPEND_VMARGS, OVERRIDE_VMARGS -> {
					//just consume the --launcher.overrideVmargs and --launcher.appendVmargs
					yield true;
				}
				case INITIALIZE -> { // check if this is initialization pass
					initialize = true;
					passThrough.add(key); // pass thru this arg
					yield true;
				}
				case DEV -> { // check if development mode should be enabled for the entire platform
					// enable development mode (regardless of a argument is present or not)
					inDevelopmentMode = true;
					passThrough.add(key); // pass thru this key and its optional value (if present)
					consumeParameter(args, arg -> {
						// assume that that the following arg is actually some additional development time class path entries.
						// look for the development mode and class path entries.
						devClassPathProps = processDevArg(arg);
						if (devClassPathProps != null) {
							devClassPath = devClassPathProps.getProperty(OSGI);
							if (devClassPath == null) {
								devClassPath = devClassPathProps.getProperty("*"); //$NON-NLS-1$
							}
						}
						passThrough.add(arg); // pass thru this value
					});
					yield true;
				}
				case SHOWSPLASH -> { // look for the command to use to show the splash screen
					showSplash = true;
					consumeParameter(args, arg -> { //consume optional parameter for showsplash
						splashLocation = arg;
					});
					yield true;
				}
				case PROTECT -> { // look for the command to use to show the splash screen
					String arg = args.remove(); //consume next parameter
					if (arg.equals(PROTECT_MASTER) || arg.equals(PROTECT_BASE)) {
						protectBase = true;
					}
					yield true;
				}
				case VMARGS -> {
					// look for the VM args arg. We have to do that before looking to see
					// if the next element is a -arg as the thing following -vmargs may in
					// fact be another -arg.
					vmargs = new ArrayList<>(args);
					args.clear(); // abort the loop after this
					yield true;
				}
				// All keys below expect a suitable parameter and are ignored (and passed-through) if no value is available
				case FRAMEWORK -> consumeParameter(args, arg -> { // look for the framework to run
					framework = arg;
				});
				case OS -> consumeParameter(args, arg -> {
					os = arg;
					passThrough.addAll(List.of(key, arg)); // pass thru this arg
				});
				case WS -> consumeParameter(args, arg -> {
					ws = arg;
					passThrough.addAll(List.of(key, arg)); // pass thru this arg
				});
				case ARCH -> consumeParameter(args, arg -> {
					arch = arg;
					passThrough.addAll(List.of(key, arg)); // pass thru this arg
				});
				case INSTALL -> consumeParameter(args, arg -> { // look for explicitly set install root
					// Consume the arg here to ensure that the launcher and Eclipse get the
					// same value as each other.
					System.setProperty(PROP_INSTALL_AREA, arg);
				});
				case CONFIGURATION -> consumeParameter(args, arg -> { // look for the configuration to use.
					// Consume the arg here to ensure that the launcher and Eclipse get the
					// same value as each other.
					System.setProperty(PROP_CONFIG_AREA, arg);
				});
				case EXITDATA -> consumeParameter(args, arg -> {
					exitData = arg;
				});
				case NAME -> consumeParameter(args, arg -> { // look for the name to use by the launcher
					System.setProperty(PROP_LAUNCHER_NAME, arg);
				});
				case STARTUP -> consumeParameter(args, arg -> { // look for the startup jar used
					// not doing anything with this right now, but still consume it
					//startup = arg;
				});
				case LAUNCHER -> consumeParameter(args, arg -> { // look for the launcher location
					// not doing anything with this right now, but still consume it
					// launcher = arg;
					System.setProperty(PROP_LAUNCHER, arg);
				});
				case LIBRARY -> consumeParameter(args, arg -> {
					library = arg;
				});
				case ENDSPLASH -> consumeParameter(args, arg -> { // look for the command to use to end the splash screen
					endSplash = arg;
				});
				case VM -> consumeParameter(args, arg -> { // look for the VM location arg
					vm = arg;
				});
				case NL -> consumeParameter(args, arg -> { // look for the nl setting
					System.setProperty(PROP_NL, arg);
				});
				default -> false;
			};
			if (!processed) {
				passThrough.add(key);
			}
		}
		return passThrough;
	}

	private static boolean consumeParameter(Queue<String> arguments, Consumer<String> consumer) {
		// If we are at the last argument or if the next one has a '-' as the first character, then we can't have an arg with a parameter
		if (!arguments.isEmpty() && !arguments.peek().startsWith("-")) { //$NON-NLS-1$
			consumer.accept(arguments.remove());
			return true;
		}
		return false;
	}

	private Properties processDevArg(String arg) {
		if (arg == null) {
			return null;
		}
		try {
			URL location = new URL(arg);
			return load(location, null);
		} catch (MalformedURLException e) {
			// the arg was not a URL so use it as is.
			Properties result = new Properties();
			result.put("*", arg); //$NON-NLS-1$
			return result;
		} catch (IOException e) {
			// TODO consider logging here
			return null;
		}
	}

	private URL getConfigurationLocation() {
		if (configurationLocation != null) {
			return configurationLocation;
		}
		configurationLocation = buildLocation(PROP_CONFIG_AREA);
		if (configurationLocation == null) {
			configurationLocation = buildLocation(PROP_CONFIG_AREA_DEFAULT);
			if (configurationLocation == null) {
				configurationLocation = buildURL(computeDefaultConfigurationLocation(), true);
			}
		}
		if (configurationLocation != null) {
			System.setProperty(PROP_CONFIG_AREA, configurationLocation.toExternalForm());
		}
		if (debug) {
			System.out.println("Configuration location:\n    " + configurationLocation); //$NON-NLS-1$
		}
		return configurationLocation;
	}

	private void processConfiguration() {
		// if the configuration area is not already defined, discover the config area by
		// trying to find a base config area.  This is either defined in a system property or
		// is computed relative to the install location.
		// Note that the config info read here is only used to determine a value
		// for the user configuration area
		URL baseConfigurationLocation = null;
		Properties baseConfiguration = null;
		if (System.getProperty(PROP_CONFIG_AREA) == null) {
			ensureAbsolute(PROP_BASE_CONFIG_AREA);
			String baseLocation = System.getProperty(PROP_BASE_CONFIG_AREA);
			if (baseLocation != null) {
				// here the base config cannot have any symbolic (e..g, @xxx) entries.  It must just
				// point to the config file.
				baseConfigurationLocation = buildURL(baseLocation, true);
			}
			if (baseConfigurationLocation == null) {
				try {
					// here we access the install location but this is very early.  This case will only happen if
					// the config area is not set and the base config area is not set (or is bogus).
					// In this case we compute based on the install location.
					baseConfigurationLocation = new URL(getInstallLocation(), CONFIG_DIR);
				} catch (MalformedURLException e) {
					// leave baseConfigurationLocation null
				}
			}
			baseConfiguration = loadConfiguration(baseConfigurationLocation);
			if (baseConfiguration != null) {
				// if the base sets the install area then use that value if the property.  We know the
				// property is not already set.
				String location = baseConfiguration.getProperty(PROP_CONFIG_AREA);
				if (location != null) {
					System.setProperty(PROP_CONFIG_AREA, location);
				}
				// if the base sets the install area then use that value if the property is not already set.
				// This helps in selfhosting cases where you cannot easily compute the install location
				// from the code base.
				location = baseConfiguration.getProperty(PROP_INSTALL_AREA);
				if (location != null && System.getProperty(PROP_INSTALL_AREA) == null) {
					System.setProperty(PROP_INSTALL_AREA, location);
				}
			}
		}

		// Now we know where the base configuration is supposed to be.  Go ahead and load
		// it and merge into the System properties.  Then, if cascaded, read the parent configuration.
		// Note that in a cascaded situation, the user configuration may be ignored if the parent
		// configuration has changed since the user configuration has been written.
		// Note that the parent may or may not be the same parent as we read above since the
		// base can define its parent.  The first parent we read was either defined by the user
		// on the command line or was the one in the install dir.
		// if the config or parent we are about to read is the same as the base config we read above,
		// just reuse the base
		Properties configuration = baseConfiguration;
		if (configuration == null || !getConfigurationLocation().equals(baseConfigurationLocation)) {
			configuration = loadConfiguration(getConfigurationLocation());
		}

		if (configuration != null && "false".equalsIgnoreCase(configuration.getProperty(PROP_CONFIG_CASCADED))) { //$NON-NLS-1$
			System.clearProperty(PROP_SHARED_CONFIG_AREA);
			configuration.remove(PROP_SHARED_CONFIG_AREA);
			mergeWithSystemProperties(configuration, null);
		} else {
			ensureAbsolute(PROP_SHARED_CONFIG_AREA);
			URL sharedConfigURL = buildLocation(PROP_SHARED_CONFIG_AREA);
			if (sharedConfigURL == null) {
				try {
					// there is no shared config value so compute one
					sharedConfigURL = new URL(getInstallLocation(), CONFIG_DIR);
				} catch (MalformedURLException e) {
					// leave sharedConfigurationLocation null
				}
			}
			// if the parent location is different from the config location, read it too.
			if (sharedConfigURL != null) {
				if (sharedConfigURL.equals(getConfigurationLocation())) {
					//After all we are not in a shared configuration setup.
					// - remove the property to show that we do not have a parent
					// - merge configuration with the system properties
					System.clearProperty(PROP_SHARED_CONFIG_AREA);
					mergeWithSystemProperties(configuration, null);
				} else {
					// if the parent we are about to read is the same as the base config we read above,
					// just reuse the base
					Properties sharedConfiguration = baseConfiguration;
					if (!sharedConfigURL.equals(baseConfigurationLocation)) {
						sharedConfiguration = loadConfiguration(sharedConfigURL);
					}
					long sharedConfigTimestamp = getCurrentConfigIniBaseTimestamp(sharedConfigURL);
					long lastKnownBaseTimestamp = getLastKnownConfigIniBaseTimestamp();
					if (debug) {
						System.out.println("Timestamps found: \n\t config.ini in the base: " + sharedConfigTimestamp + "\n\t remembered " + lastKnownBaseTimestamp); //$NON-NLS-1$ //$NON-NLS-2$
					}

					//merge user configuration since the base has not changed.
					if (lastKnownBaseTimestamp == sharedConfigTimestamp || lastKnownBaseTimestamp == NO_TIMESTAMP) {
						mergeWithSystemProperties(configuration, null);
					} else {
						configuration = null;
						System.setProperty(PROP_IGNORE_USER_CONFIGURATION, Boolean.TRUE.toString());
					}

					//now merge the base configuration
					mergeWithSystemProperties(sharedConfiguration, configuration);
					System.setProperty(PROP_SHARED_CONFIG_AREA, sharedConfigURL.toExternalForm());
					if (debug) {
						System.out.println("Shared configuration location:\n    " + sharedConfigURL.toExternalForm()); //$NON-NLS-1$
					}
				}
			}
		}
		// setup the path to the framework
		String urlString = System.getProperty(PROP_FRAMEWORK, null);
		if (urlString != null) {
			urlString = resolve(urlString);
			//ensure that the install location is set before resolving framework
			getInstallLocation();
			URL url = buildURL(urlString, true);
			urlString = url.toExternalForm();
			System.setProperty(PROP_FRAMEWORK, urlString);
			bootLocation = urlString;
		}
	}

	private long getCurrentConfigIniBaseTimestamp(URL url) {
		try {
			url = new URL(url, CONFIG_FILE);
		} catch (MalformedURLException e1) {
			return NO_TIMESTAMP;
		}
		URLConnection connection = null;
		try {
			connection = url.openConnection();
		} catch (IOException e) {
			return NO_TIMESTAMP;
		}
		return connection.getLastModified();
	}

	//Get the timestamp that has been remembered. The BASE_TIMESTAMP_FILE_CONFIGINI is written at provisioning time by fwkAdmin.
	private long getLastKnownConfigIniBaseTimestamp() {
		if (debug) {
			System.out.println("Loading timestamp file from:\n\t " + getConfigurationLocation() + "   " + BASE_TIMESTAMP_FILE_CONFIGINI); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Properties result;
		try {
			result = load(getConfigurationLocation(), BASE_TIMESTAMP_FILE_CONFIGINI);
		} catch (IOException e) {
			if (debug) {
				System.out.println("\tNo timestamp file found"); //$NON-NLS-1$
			}
			return NO_TIMESTAMP;
		}
		String timestamp = result.getProperty(KEY_CONFIGINI_TIMESTAMP);
		return Long.parseLong(timestamp);
	}

	/**
	 * Ensures the value for a system property is an absolute URL. Relative URLs are translated to
	 * absolute URLs by taking the install URL as reference.
	 *
	 * @param locationProperty the key for a system property containing a URL
	 */
	private void ensureAbsolute(String locationProperty) {
		String propertyValue = System.getProperty(locationProperty);
		if (propertyValue == null) {
			// property not defined
			return;
		}
		URL locationURL = null;
		try {
			locationURL = new URL(propertyValue);
		} catch (MalformedURLException e) {
			// property is not a valid URL
			return;
		}
		String locationPath = locationURL.getPath();
		if (locationPath.startsWith("/")) { //$NON-NLS-1$
			// property value is absolute
			return;
		}
		URL installURL = getInstallLocation();
		if (!locationURL.getProtocol().equals(installURL.getProtocol())) {
			// not same protocol
			return;
		}
		try {
			URL absoluteURL = new URL(installURL, locationPath);
			System.setProperty(locationProperty, absoluteURL.toExternalForm());
		} catch (MalformedURLException e) {
			// should not happen - the relative URL is known to be valid
		}
	}

	/**
	 * Returns url of the location this class was loaded from
	 */
	private URL getInstallLocation() {
		if (installLocation != null) {
			return installLocation;
		}

		// value is not set so compute the default and set the value
		String installArea = System.getProperty(PROP_INSTALL_AREA);
		if (installArea != null) {
			if (installArea.startsWith(LAUNCHER_DIR)) {
				String launcher = System.getProperty(PROP_LAUNCHER);
				if (launcher == null) {
					throw new IllegalStateException("Install location depends on launcher, but launcher is not defined"); //$NON-NLS-1$
				}
				installArea = installArea.replace(LAUNCHER_DIR, new File(launcher).getParent());
			}
			installLocation = buildURL(installArea, true);
			if (installLocation == null) {
				throw new IllegalStateException("Install location is invalid: " + installArea); //$NON-NLS-1$
			}
			System.setProperty(PROP_INSTALL_AREA, installLocation.toExternalForm());
			if (debug) {
				System.out.println("Install location:\n    " + installLocation); //$NON-NLS-1$
			}
			return installLocation;
		}

		ProtectionDomain domain = Main.class.getProtectionDomain();
		CodeSource source = null;
		URL result = null;
		if (domain != null) {
			source = domain.getCodeSource();
		}
		if (source == null || domain == null) {
			if (debug) {
				System.out.println("CodeSource location is null. Defaulting the install location to file:startup.jar"); //$NON-NLS-1$
			}
			try {
				result = new URL("file:startup.jar"); //$NON-NLS-1$
			} catch (MalformedURLException e2) {
				//Ignore
			}
		}
		if (source != null) {
			result = source.getLocation();
		}

		String path = decode(result.getFile());
		// normalize to not have leading / so we can check the form
		File file = new File(path);
		path = file.toString().replace('\\', '/');
		// TODO need a better test for windows
		// If on Windows then canonicalize the drive letter to be lowercase.
		// remember that there may be UNC paths
		if (File.separatorChar == '\\') {
			if (Character.isUpperCase(path.charAt(0))) {
				path = Character.toLowerCase(path.charAt(0)) + path.substring(1);
			}
		}
		if (path.toLowerCase(Locale.ROOT).endsWith(".jar")) { //$NON-NLS-1$
			path = path.substring(0, path.lastIndexOf('/') + 1);
		}
		if (path.toLowerCase(Locale.ROOT).endsWith("/plugins/")) { //$NON-NLS-1$
			path = path.substring(0, path.length() - "/plugins/".length()); //$NON-NLS-1$
		}
		try {
			try {
				// create a file URL (via File) to normalize the form (e.g., put
				// the leading / on if necessary)
				path = new File(path).toURL().getFile();
			} catch (MalformedURLException e1) {
				// will never happen.  The path is straight from a URL.
			}
			installLocation = new URL(result.getProtocol(), result.getHost(), result.getPort(), path);
			System.setProperty(PROP_INSTALL_AREA, installLocation.toExternalForm());
		} catch (MalformedURLException e) {
			// TODO Very unlikely case.  log here.
		}
		if (debug) {
			System.out.println("Install location:\n    " + installLocation); //$NON-NLS-1$
		}
		return installLocation;
	}

	/*
	 * Load the given configuration file
	 */
	private Properties loadConfiguration(URL url) {
		Properties result = null;
		try {
			url = new URL(url, CONFIG_FILE);
		} catch (MalformedURLException e) {
			return null;
		}
		try {
			if (debug) {
				System.out.print("Configuration file:\n    " + url); //$NON-NLS-1$
			}
			result = loadProperties(url);
			if (debug) {
				System.out.println(" loaded"); //$NON-NLS-1$
			}
		} catch (IOException e) {
			if (debug) {
				System.out.println(" not found or not read"); //$NON-NLS-1$
			}
		}
		return substituteVars(result);
	}

	private Properties loadProperties(URL url) throws IOException {
		// try to load saved configuration file (watch for failed prior save())
		if (url == null) {
			return null;
		}
		Properties result = null;
		IOException originalException = null;
		try {
			result = load(url, null); // try to load config file
		} catch (IOException e1) {
			originalException = e1;
			try {
				result = load(url, CONFIG_FILE_TEMP_SUFFIX); // check for failures on save
			} catch (IOException e2) {
				try {
					result = load(url, CONFIG_FILE_BAK_SUFFIX); // check for failures on save
				} catch (IOException e3) {
					throw originalException; // we tried, but no config here ...
				}
			}
		}
		return result;
	}

	/*
	 * Load the configuration
	 */
	private Properties load(URL url, String suffix) throws IOException {
		// figure out what we will be loading
		if (suffix != null && !suffix.equals("")) { //$NON-NLS-1$
			url = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + suffix);
		}

		// try to load saved configuration file
		Properties props = new Properties();
		try (InputStream is = getStream(url)) {
			props.load(is);
		}
		return props;
	}

	private InputStream getStream(URL location) throws IOException {
		if ("file".equalsIgnoreCase(location.getProtocol())) { //$NON-NLS-1$
			// this is done to handle URLs with invalid syntax in the path
			File f = new File(location.getPath());
			if (f.exists()) {
				return new FileInputStream(f);
			}
		}
		return location.openStream();
	}

	/*
	 * Handle splash screen.
	 *  The splash screen is displayed natively.  Whether or not the splash screen
	 *  was displayed by the launcher, we invoke JNIBridge.showSplash() and the
	 *  native code handles the case of the splash screen already existing.
	 *
	 * The -showsplash argument may indicate the bitmap used by the native launcher,
	 * or the bitmap location may be extracted from the config.ini
	 *
	 * We pass a handler (Runnable) to the platform which is called as a result of the
	 * launched application calling Platform.endSplash(). This handle calls
	 * JNIBridge.takeDownSplash and the native code will close the splash screen.
	 *
	 * The -endsplash argument is longer used and has the same result as -nosplash
	 *
	 * @param defaultPath search path for the boot plugin
	 */
	private void handleSplash() {
		// run without splash if we are initializing or nosplash
		// was specified (splashdown = true)
		if (initialize || splashDown || bridge == null) {
			showSplash = false;
			endSplash = null;
			return;
		}

		if (showSplash || endSplash != null) {
			// Register the endSplashHandler to be run at VM shutdown. This hook will be
			// removed once the splash screen has been taken down.
			try {
				Runtime.getRuntime().addShutdownHook(splashHandler);
			} catch (Throwable ex) {
				// Best effort to register the handler
			}
		}

		// if -endsplash is specified, use it and ignore any -showsplash command
		if (endSplash != null) {
			showSplash = false;
			return;
		}

		// check if we are running without a splash screen
		if (!showSplash) {
			return;
		}

		// determine the splash location
		splashLocation = getSplashLocation();
		if (debug) {
			System.out.println("Splash location:\n    " + splashLocation); //$NON-NLS-1$
		}
		if (splashLocation == null) {
			return;
		}

		bridge.setLauncherInfo(System.getProperty(PROP_LAUNCHER), System.getProperty(PROP_LAUNCHER_NAME));
		bridge.showSplash(splashLocation);
		long handle = bridge.getSplashHandle();
		if (handle != 0 && handle != -1) {
			System.setProperty(SPLASH_HANDLE, String.valueOf(handle));
			System.setProperty(SPLASH_LOCATION, splashLocation);
			bridge.updateSplash();
		} else {
			// couldn't show the splash screen for some reason
			splashDown = true;
		}
	}

	/*
	 * Take down the splash screen.
	 */
	private void takeDownSplash() {
		if (splashDown || bridge == null) { // splash is already down
			return;
		}

		splashDown = bridge.takeDownSplash();
		System.clearProperty(SPLASH_HANDLE);

		try {
			Runtime.getRuntime().removeShutdownHook(splashHandler);
		} catch (Throwable e) {
			// OK to ignore this, happens when the VM is already shutting down
		}
	}

	/*
	 * Return path of the splash image to use.  First search the defined splash path.
	 * If that does not work, look for a default splash.  Currently the splash must be in the file system
	 * so the return value here is the file system path.
	 */
	private String getSplashLocation() {
		//check the path passed in from -showsplash first.  The old launcher passed a timeout value
		//as the argument, so only use it if it isn't a number and the file exists.
		if (splashLocation != null && !Character.isDigit(splashLocation.charAt(0)) && new File(splashLocation).exists()) {
			System.setProperty(PROP_SPLASHLOCATION, splashLocation);
			return splashLocation;
		}
		String result = System.getProperty(PROP_SPLASHLOCATION);
		if (result != null) {
			return result;
		}
		String splashPath = System.getProperty(PROP_SPLASHPATH);
		if (splashPath != null) {
			List<String> entries = getArrayFromList(splashPath);
			List<String> paths = new ArrayList<>(entries.size());
			for (String e : entries) {
				String entry = resolve(e);
				if (entry != null && entry.startsWith(FILE_SCHEME)) {
					File entryFile = new File(entry.substring(5).replace('/', File.separatorChar));
					entry = searchFor(entryFile.getName(), entryFile.getParent());
					if (entry != null) {
						paths.add(entry);
					}
				} else {
					log("Invalid splash path entry: " + e); //$NON-NLS-1$
				}
			}
			// see if we can get a splash given the splash path
			result = searchForSplash(paths);
			if (result != null) {
				System.setProperty(PROP_SPLASHLOCATION, result);
				return result;
			}
		}
		return result;
	}

	/*
	 * Do a locale-sensitive lookup of splash image
	 */
	private String searchForSplash(List<String> searchPath) {
		if (searchPath.isEmpty()) {
			return null;
		}
		// Get the splash screen for the specified locale
		String locale = System.getProperty(PROP_NL);
		if (locale == null) {
			locale = Locale.getDefault().toString();
		}
		List<String> nlVariants = buildNLVariants(locale);
		for (String nlVariant : nlVariants) {
			for (String path : searchPath) {
				if (path.startsWith(FILE_SCHEME)) {
					path = path.substring(5);
				}
				// do we have a JAR?
				if (isJAR(path)) {
					String result = extractFromJAR(path, nlVariant);
					if (result != null) {
						return result;
					}
				} else {
					// we have a file or a directory
					if (!path.endsWith(File.separator)) {
						path += File.separator;
					}
					path += nlVariant;
					File result = new File(path);
					if (result.exists()) {
						return result.getAbsolutePath(); // return the first match found [20063]
					}
				}
			}
		}
		// sorry, could not find splash image
		return null;
	}

	/*
	 * Look for the specified spash file in the given JAR and extract it to the config
	 * area for caching purposes.
	 */
	private String extractFromJAR(String jarPath, String jarEntry) {
		String configLocation = System.getProperty(PROP_CONFIG_AREA);
		if (configLocation == null) {
			log("Configuration area not set yet. Unable to extract " + jarEntry + " from JAR'd plug-in: " + jarPath); //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
		URL configURL = buildURL(configLocation, false);
		if (configURL == null) {
			return null;
		}
		// cache the splash in the equinox launcher sub-dir in the config area
		File splash = new File(configURL.getPath(), PLUGIN_ID);
		//include the name of the jar in the cache location
		File jarFile = new File(jarPath);
		String cache = jarFile.getName();
		if (cache.endsWith(".jar")) { //$NON-NLS-1$
			cache = cache.substring(0, cache.length() - 4);
		}
		splash = new File(splash, cache);
		splash = new File(splash, jarEntry);
		// if we have already extracted this file before, then return
		if (splash.exists()) {
			// if we are running with -clean then delete the cached splash file
			boolean clean = commands.stream().anyMatch(CLEAN::equalsIgnoreCase);
			if (clean) {
				splash.delete();
			} else {
				return splash.getAbsolutePath();
			}
		}

		try (ZipFile file = new ZipFile(jarPath)) {
			ZipEntry entry = file.getEntry(jarEntry.replace(File.separatorChar, '/'));
			if (entry == null) {
				return null;
			}

			Path outputFile = splash.toPath();
			Files.createDirectories(outputFile.getParent());

			try (InputStream input = file.getInputStream(entry)) {
				Files.copy(input, outputFile);
			} catch (IOException e) {
				log("Exception opening splash: " + entry.getName() + " in JAR file: " + jarPath); //$NON-NLS-1$ //$NON-NLS-2$
				log(e);
				return null;
			}

			return splash.exists() ? splash.getAbsolutePath() : null;
		} catch (IOException e) {
			log("Exception looking for " + jarEntry + " in JAR file: " + jarPath); //$NON-NLS-1$ //$NON-NLS-2$
			log(e);
			return null;
		}
	}

	/*
	 * Return a boolean value indicating whether or not the given
	 * path represents a JAR file.
	 */
	private boolean isJAR(String path) {
		return new File(path).isFile();
	}

	/*
	 * Build an array of path suffixes based on the given NL which is suitable
	 * for splash path searching.  The returned array contains paths in order
	 * from most specific to most generic. So, in the FR_fr locale, it will return
	 * candidates in "nl/fr/FR/", then "nl/fr/", and finally in the root.
	 * Candidate names are defined in SPLASH_IMAGES and include splash.png, splash.jpg, etc.
	 */
	private static List<String> buildNLVariants(String locale) {
		//build list of suffixes for loading resource bundles
		String nl = locale;
		List<String> result = new ArrayList<>(4);
		int lastSeparator;
		while (true) {
			for (String name : SPLASH_IMAGES) {
				result.add("nl" + File.separatorChar + nl.replace('_', File.separatorChar) + File.separatorChar + name); //$NON-NLS-1$
			}
			lastSeparator = nl.lastIndexOf('_');
			if (lastSeparator == -1) {
				break;
			}
			nl = nl.substring(0, lastSeparator);
		}
		//add the empty suffix last (most general)
		for (String name : SPLASH_IMAGES) {
			result.add(name);
		}
		return result;
	}

	/*
	 * resolve platform:/base/ URLs
	 */
	private String resolve(String urlString) {
		// handle the case where people mistakenly spec a refererence: url.
		if (urlString.startsWith(REFERENCE_SCHEME)) {
			urlString = urlString.substring(10);
		}
		if (urlString.startsWith(PLATFORM_URL)) {
			String path = urlString.substring(PLATFORM_URL.length());
			return getInstallLocation() + path;
		}
		return urlString;
	}

	/*
	 * Entry point for logging.
	 */
	private synchronized void log(Object obj) {
		if (obj == null) {
			return;
		}
		try {
			openLogFile();
			try {
				if (newSession) {
					log.write(SESSION);
					log.write(' ');
					String timestamp = new Date().toString();
					log.write(timestamp);
					log.write(' ');
					for (int i = SESSION.length() + timestamp.length(); i < 78; i++) {
						log.write('-');
					}
					log.newLine();
					newSession = false;
				}
				write(obj);
			} finally {
				if (logFile == null) {
					if (log != null) {
						log.flush();
					}
				} else {
					closeLogFile();
				}
			}
		} catch (Exception e) {
			System.err.println("An exception occurred while writing to the platform log:"); //$NON-NLS-1$
			e.printStackTrace(System.err);
			System.err.println("Logging to the console instead."); //$NON-NLS-1$
			//we failed to write, so dump log entry to console instead
			try {
				log = logForStream(System.err);
				write(obj);
				log.flush();
			} catch (Exception e2) {
				System.err.println("An exception occurred while logging to the console:"); //$NON-NLS-1$
				e2.printStackTrace(System.err);
			}
		} finally {
			log = null;
		}
	}

	/*
	 * This should only be called from #log()
	 */
	private void write(Object obj) throws IOException {
		if (obj == null) {
			return;
		}
		if (obj instanceof Throwable throwable) {
			log.write(STACK);
			log.newLine();
			throwable.printStackTrace(new PrintWriter(log));
		} else {
			log.write(ENTRY);
			log.write(' ');
			log.write(PLUGIN_ID);
			log.write(' ');
			log.write(String.valueOf(ERROR));
			log.write(' ');
			log.write(String.valueOf(0));
			log.write(' ');
			log.write(getDate(new Date()));
			log.newLine();
			log.write(MESSAGE);
			log.write(' ');
			log.write(String.valueOf(obj));
		}
		log.newLine();
	}

	private String getDate(Date date) {
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		StringBuilder sb = new StringBuilder();
		appendPaddedInt(c.get(Calendar.YEAR), 4, sb).append('-');
		appendPaddedInt(c.get(Calendar.MONTH) + 1, 2, sb).append('-');
		appendPaddedInt(c.get(Calendar.DAY_OF_MONTH), 2, sb).append(' ');
		appendPaddedInt(c.get(Calendar.HOUR_OF_DAY), 2, sb).append(':');
		appendPaddedInt(c.get(Calendar.MINUTE), 2, sb).append(':');
		appendPaddedInt(c.get(Calendar.SECOND), 2, sb).append('.');
		appendPaddedInt(c.get(Calendar.MILLISECOND), 3, sb);
		return sb.toString();
	}

	private StringBuilder appendPaddedInt(int value, int pad, StringBuilder buffer) {
		pad = pad - 1;
		if (pad == 0) {
			return buffer.append(value);
		}
		int padding = (int) Math.pow(10, pad);
		if (value >= padding) {
			return buffer.append(value);
		}
		while (padding > value && padding > 1) {
			buffer.append('0');
			padding = padding / 10;
		}
		buffer.append(value);
		return buffer;
	}

	private void computeLogFileLocation() {
		String logFileProp = System.getProperty(PROP_LOGFILE);
		if (logFileProp != null) {
			if (logFile == null || !logFileProp.equals(logFile.getAbsolutePath())) {
				logFile = new File(logFileProp);
				new File(logFile.getParent()).mkdirs();
			}
			return;
		}

		// compute the base location and then append the name of the log file
		URL base = buildURL(System.getProperty(PROP_CONFIG_AREA), false);
		if (base == null) {
			return;
		}
		logFile = new File(base.getPath(), System.currentTimeMillis() + ".log"); //$NON-NLS-1$
		new File(logFile.getParent()).mkdirs();
		System.setProperty(PROP_LOGFILE, logFile.getAbsolutePath());
	}

	private void openLogFile() throws IOException {
		computeLogFileLocation();
		try {
			log = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile.getAbsolutePath(), true), StandardCharsets.UTF_8));
		} catch (IOException e) {
			logFile = null;
			throw e;
		}
	}

	private BufferedWriter logForStream(OutputStream output) {
		return new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
	}

	private void closeLogFile() throws IOException {
		try {
			if (log != null) {
				log.flush();
				log.close();
			}
		} finally {
			log = null;
		}
	}

	private void mergeWithSystemProperties(Properties source, Properties userConfiguration) {
		final String EXT_OVERRIDE_USER = ".override.user"; //$NON-NLS-1$
		if (source == null) {
			return;
		}
		for (Enumeration<?> e = source.keys(); e.hasMoreElements();) {
			String key = (String) e.nextElement();
			if (key.equals(PROP_CLASSPATH)) {
				String destinationClasspath = System.getProperty(PROP_CLASSPATH);
				String sourceClasspath = source.getProperty(PROP_CLASSPATH);
				if (destinationClasspath == null) {
					destinationClasspath = sourceClasspath;
				} else {
					destinationClasspath = destinationClasspath + sourceClasspath;
				}
				System.setProperty(PROP_CLASSPATH, destinationClasspath);
				continue;
			}
			String value = source.getProperty(key);

			// Check to see if we are supposed to override existing values from the user configuraiton.
			// This is done only in the case of shared install where we have already set the user values
			// but want to override them with values from the shared location's config.
			if (userConfiguration != null && !key.endsWith(EXT_OVERRIDE_USER)) {
				// check all levels to see if the "override" property was set
				final String overrideKey = key + EXT_OVERRIDE_USER;
				boolean shouldOverride = System.getProperty(overrideKey) != null || source.getProperty(overrideKey) != null;
				// only set the value if the user specified the override property and if the
				// original property wasn't set by a commad-line arg
				if (shouldOverride && !userConfiguration.contains(key)) {
					System.setProperty(key, value);
					continue;
				}
			}

			// only set the value if it doesn't already exist to preserve ordering (command-line, user config, shared config)
			if (System.getProperty(key) == null) {
				System.setProperty(key, value);
			}
		}
	}

	private void setupVMProperties() {
		if (vm != null) {
			System.setProperty(PROP_VM, vm);
		}
		setMultiValueProperty(PROP_VMARGS, vmargs);
		setMultiValueProperty(PROP_COMMANDS, commands);
	}

	private void setMultiValueProperty(String property, List<String> values) {
		if (values != null) {
			StringBuilder result = new StringBuilder(300);
			for (String value : values) {
				if (value != null) {
					result.append(value);
					result.append('\n');
				}
			}
			System.setProperty(property, result.toString());
		}
	}

	/*
	 * NOTE: It is ok here for EclipsePolicy to use 1.4 methods because the methods
	 * that it calls them from don't exist in Foundation so they will never be called. A more
	 * detailed explanation from Tom:
	 *
	 * They will never get called because in a pre 1.4 VM the methods
	 * getPermissions(CodeSource) and implies(ProtectionDomain, Permission) are
	 * undefined on the Policy class which is what EclipsePolicy extends.  EclipsePolicy
	 * implements these two methods so it can proxy them to the parent Policy.
	 * But since these methods are not actually defined on Policy in a pre-1.4 VM
	 * nobody will actually call them (unless they casted the policy to EclipsePolicy and
	 * called our methods)
	 */
	@SuppressWarnings({"removal"})
	private class EclipsePolicy extends Policy {
		// The policy that this EclipsePolicy is replacing
		private Policy policy;

		// The set of URLs to give AllPermissions to; this is the set of bootURLs
		private List<URL> urls;

		// The AllPermissions collection
		private PermissionCollection allPermissions;

		// The AllPermission permission
		Permission allPermission = new AllPermission();

		EclipsePolicy(Policy policy, List<URL> urls) {
			this.policy = policy;
			this.urls = urls;
			allPermissions = new PermissionCollection() {
				private static final long serialVersionUID = 3258131349494708277L;

				// A simple PermissionCollection that only has AllPermission
				@Override
				public void add(Permission permission) {
					//no adding to this policy
				}

				@Override
				public boolean implies(Permission permission) {
					return true;
				}

				@Override
				public Enumeration<Permission> elements() {
					return Collections.enumeration(Collections.singleton(allPermission));
				}
			};
		}

		@Override
		public PermissionCollection getPermissions(CodeSource codesource) {
			if (contains(codesource)) {
				return allPermissions;
			}
			return policy == null ? allPermissions : policy.getPermissions(codesource);
		}

		@Override
		public PermissionCollection getPermissions(ProtectionDomain domain) {
			if (contains(domain.getCodeSource())) {
				return allPermissions;
			}
			return policy == null ? allPermissions : policy.getPermissions(domain);
		}

		@Override
		public boolean implies(ProtectionDomain domain, Permission permission) {
			if (contains(domain.getCodeSource())) {
				return true;
			}
			return policy == null ? true : policy.implies(domain, permission);
		}

		@Override
		public void refresh() {
			if (policy != null) {
				policy.refresh();
			}
		}

		private boolean contains(CodeSource codeSource) {
			if (codeSource == null) {
				return false;
			}
			URL location = codeSource.getLocation();
			if (location == null) {
				return false;
			}
			// Check to see if this URL is in our set of URLs to give AllPermissions to.
			for (URL url : urls) {
				// We do simple equals test here because we assume the URLs will be the same objects.
				if (url == location) {
					return true;
				}
			}
			return false;
		}
	}

	public class StartupClassLoader extends URLClassLoader {

		public StartupClassLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		@Override
		protected String findLibrary(String name) {
			if (extensionPaths == null) {
				return super.findLibrary(name);
			}
			String libName = System.mapLibraryName(name);
			for (String extensionPath : extensionPaths) {
				File libFile = new File(extensionPath, libName);
				if (libFile.isFile()) {
					return libFile.getAbsolutePath();
				}
			}
			return super.findLibrary(name);
		}

		/**
		 * Must override addURL to make it public so the framework can
		 * do deep reflection to add URLs on Java 9.
		 */
		@Override
		public void addURL(URL url) {
			super.addURL(url);
		}

		@Override
		protected URL findResource(String moduleName, String name) {
			return findResource(name);
		}

		@Override
		protected Class<?> findClass(String moduleName, String name) {
			try {
				return findClass(name);
			} catch (ClassNotFoundException e) {
				return null;
			}
		}
	}

	private Properties substituteVars(Properties result) {
		if (result == null) {
			//nothing todo.
			return null;
		}
		for (Enumeration<?> eKeys = result.keys(); eKeys.hasMoreElements();) {
			if (eKeys.nextElement() instanceof String key) {
				String value = result.getProperty(key);
				if (value != null) {
					result.put(key, substituteVars(value));
				}
			}
		}
		return result;
	}

	private static String substituteVars(String path) {
		StringBuilder buf = new StringBuilder(path.length());
		StringTokenizer st = new StringTokenizer(path, VARIABLE_DELIM_STRING, true);
		boolean varStarted = false; // indicates we are processing a var subtitute
		String var = null; // the current var key
		while (st.hasMoreElements()) {
			String tok = st.nextToken();
			if (VARIABLE_DELIM_STRING.equals(tok)) {
				if (!varStarted) {
					varStarted = true; // we found the start of a var
					var = ""; //$NON-NLS-1$
				} else {
					// we have found the end of a var
					String prop = null;
					// get the value of the var from system properties
					if (var != null && !var.isEmpty()) {
						prop = System.getProperty(var);
					}
					if (prop == null) {
						prop = System.getenv(var);
					}
					if (prop != null) {
						// found a value; use it
						buf.append(prop);
					} else {
						// could not find a value append the var; keep delemiters
						buf.append(VARIABLE_DELIM_CHAR);
						buf.append(var == null ? "" : var); //$NON-NLS-1$
						buf.append(VARIABLE_DELIM_CHAR);
					}
					varStarted = false;
					var = null;
				}
			} else {
				if (!varStarted) {
					buf.append(tok); // the token is not part of a var
				} else {
					var = tok; // the token is the var key; save the key to process when we find the end token
				}
			}
		}
		if (var != null) {
			// found a case of $var at the end of the path with no trailing $; just append it as is.
			buf.append(VARIABLE_DELIM_CHAR).append(var);
		}
		return buf.toString();
	}
}
