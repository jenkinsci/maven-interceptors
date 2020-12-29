/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Olivier Lamy
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.maven3.agent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.codehaus.plexus.classworlds.launcher.Launcher;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;


/**
 * Entry point for launching Maven 3 and Hudson remoting in the same VM, in the
 * classloader layout that Maven expects.
 * 
 * <p>
 * The actual Maven execution will be started by the program sent through
 * remoting.
 * </p>
 * 
 * @author Kohsuke Kawaguchi
 * @author Olivier Lamy
 * @since 1.4
 */
public class Maven31Main
{

    /**
     * Used to pass the classworld instance to the code running inside the
     * remoting system.
     */
    private static Launcher launcher;

    @SuppressFBWarnings({"PATH_TRAVERSAL_IN"})
    public static void main(String... args) throws Exception {
        String slaveAgentSocket = args[4];
        int i = slaveAgentSocket.indexOf(':');
        if (i > 0) {
            main(new File(args[0]), new File(args[1]), new File(args[2]),
                    new File(args[3]), slaveAgentSocket.substring(0, i), Integer.parseInt(slaveAgentSocket.substring(i+1)));
        } else {
            main(new File(args[0]), new File(args[1]), new File(args[2]),
                    new File(args[3]), null, Integer.parseInt(slaveAgentSocket));
        }
    }

    @Deprecated
    public static void main(File m2Home, File remotingJar, File interceptorJar
            , File interceptorCommonJar, int tcpPort) throws Exception {

        main(m2Home, remotingJar, interceptorJar, interceptorCommonJar, null, tcpPort);
    }


    /**
     *
     * @param m2Home
     *            Maven2 installation. This is where we find Maven jars that
     *            we'll run.
     * @param remotingJar
     *            Hudson's remoting.jar that we'll load.
     * @param interceptorJar
     *            maven-listener.jar that we'll load.
     * @param interceptorCommonJar
     *            maven3-interceptor-commons.jar we'll load
     * @param agentIp
     *            IP address the TCP socket is bound to
     * @param tcpPort
     *            TCP socket that the launching Hudson will be listening to.
     *            This is used for the remoting communication.
     */
    @SuppressFBWarnings({"DB_DUPLICATE_BRANCHES","NP_LOAD_OF_KNOWN_NULL_VALUE","NP_NULL_ON_SOME_PATH","UNENCRYPTED_SOCKET"})
    public static void main(File m2Home, File remotingJar, File interceptorJar,
                            File interceptorCommonJar, String agentIp, int tcpPort) throws Exception {
        // Unix master with Windows slave ends up passing path in Unix format,
        // so convert it to Windows format now so that no one chokes with the
        // path format later.
        try {
            m2Home = m2Home.getCanonicalFile();
        } catch (IOException e) {
            // ignore. We'll check the error later if m2Home exists anyway
        }

        if (!m2Home.exists()) {
            System.err.println("No such directory exists: " + m2Home);
            System.exit(1);
        }

        versionCheck();

        // expose variables used in the classworlds configuration
        System.setProperty("maven.home", m2Home.getPath());
        System.setProperty("maven3.interceptor.common", (interceptorCommonJar != null ? interceptorCommonJar
            : interceptorCommonJar).getPath());
        System.setProperty("maven3.interceptor", (interceptorJar != null ? interceptorJar
                : interceptorJar).getPath());

        // load the default realms
        launcher = new Launcher();
        launcher.setSystemClassLoader(Maven31Main.class.getClassLoader());
        launcher.configure(getClassWorldsConfStream());


        // create a realm for loading remoting subsystem.
        // this needs to be able to see maven.
        ClassRealm remoting = launcher.getWorld().newRealm( "hudson-remoting", launcher.getSystemClassLoader() );
        remoting.setParentRealm(launcher.getWorld().getRealm("plexus.core"));
        remoting.addURL(remotingJar.toURI().toURL());

	    final Socket s = new Socket(agentIp,tcpPort);

        Class remotingLauncher = remoting.loadClass("hudson.remoting.Launcher");
        remotingLauncher.getMethod("main",
                new Class[] { InputStream.class, OutputStream.class }).invoke(
                null,
                new Object[] {
                        // do partial close, since socket.getInputStream and
                        // getOutputStream doesn't do it by
                        new BufferedInputStream(new FilterInputStream(s
                                .getInputStream()) {
                            public void close() throws IOException {
                                s.shutdownInput();
                            }
                        }),
                        new BufferedOutputStream(new RealFilterOutputStream(s
                                .getOutputStream()) {
                            public void close() throws IOException {
                                s.shutdownOutput();
                            }
                        }) });
        System.exit(0);
	}

    /**
     * Called by the code in remoting to add more plexus components.
     * @since 1.3
     */
    public static void addPlexusComponents(URL[] modules) {
        try {
            ClassRealm realm = launcher.getWorld().getRealm("plexus.core");
            for (URL url : modules) {
                realm.addURL(url);
            }
        } catch (NoSuchRealmException e) {
            throw new Error(e);
        }
    }

    /**
     * Called by the code in remoting to launch.
     */
    public static int launch( String[] args ) throws Exception {

        try {
            launcher.launch( args );
        } catch ( Throwable e ) {
            e.printStackTrace();
            throw new Exception( e );
        }
        return launcher.getExitCode();
    }

    private static InputStream getClassWorldsConfStream() throws FileNotFoundException {
        String classWorldsConfLocation = System.getProperty("classworlds.conf");
        if (classWorldsConfLocation == null || classWorldsConfLocation.trim().length() == 0) {
            classWorldsConfLocation = System.getenv("classworlds.conf");
            if (classWorldsConfLocation == null || classWorldsConfLocation.trim().length() == 0) {
                return Maven31Main.class.getResourceAsStream("classworlds.conf");
            }
        }
        return new FileInputStream(new File(classWorldsConfLocation));
    }
	
    /**
     * Makes sure that this is Java5 or later.
     */
    private static void versionCheck() {
        String v = System.getProperty("java.class.version");
        if (v != null) {
            try {
                if (Float.parseFloat(v) < 49.0) {
                    System.err
                            .println("Native maven support requires Java 1.5 or later, but this Maven is using "
                                    + System.getProperty("java.home"));
                    System.err.println("Please use the freestyle project.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                // couldn't check.
            }
        }
    }	
	
}
