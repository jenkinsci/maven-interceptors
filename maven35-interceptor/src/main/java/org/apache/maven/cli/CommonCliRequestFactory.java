package org.apache.maven.cli;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.maven.cli.logging.Slf4jConfiguration;
import org.apache.maven.cli.logging.Slf4jConfigurationFactory;
import org.apache.maven.cli.logging.Slf4jLoggerManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.codehaus.plexus.logging.LoggerManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Properties;

public class CommonCliRequestFactory {

    private ILoggerFactory slf4jLoggerFactory;

    private LoggerManager plexusLoggerManager;

    public CommonCliRequest create( String[] args ) throws MavenExecutionRequestsBuilderException {
        try {
            CommonCliRequest cliRequest = new CommonCliRequest( args, null );
            initialize( cliRequest );
            cli( cliRequest );
            properties( cliRequest );
            logging( cliRequest );
            version( cliRequest );

            return cliRequest;
        } catch (Exception e) {
            throw new MavenExecutionRequestsBuilderException( e.getMessage(), e );
        }
    }

    public ILoggerFactory getSlf4jLoggerFactory() {
        return slf4jLoggerFactory;
    }

    public LoggerManager getPlexusLoggerManager() {
        return plexusLoggerManager;
    }

    private void initialize(CommonCliRequest cliRequest ) throws IOException {
        if ( cliRequest.workingDirectory == null )
        {
            cliRequest.workingDirectory = System.getProperty( "user.dir" );
        }

        if ( cliRequest.multiModuleProjectDirectory == null )
        {
            File basedir = new File( "" );
            try
            {
                cliRequest.multiModuleProjectDirectory = basedir.getCanonicalFile();
            }
            catch ( IOException e )
            {
                cliRequest.multiModuleProjectDirectory = basedir.getAbsoluteFile();
            }
        }

        //
        // Make sure the Maven home directory is an absolute path to save us from confusion with say drive-relative
        // Windows paths.
        //
        String mavenHome = System.getProperty( "maven.home" );

        if ( mavenHome != null )
        {
            System.setProperty( "maven.home", new File( mavenHome ).getAbsolutePath() );
            String mavenConf = System.getProperty("maven.conf");
            if (mavenConf == null) {
                System.setProperty("maven.conf", new File(System.getProperty("maven.home", System.getProperty("user.dir", "")), "conf").getCanonicalPath());

            }

        }
    }

    private void cli( CommonCliRequest cliRequest )
        throws Exception
    {
        //
        // Parsing errors can happen during the processing of the arguments and we prefer not having to check if the logger is null
        // and construct this so we can use an SLF4J logger everywhere.
        //
        // slf4jLogger = new Slf4jStdoutLogger();

        CLIManager cliManager = new CLIManager();

        try
        {
            cliRequest.commandLine = cliManager.parse( cliRequest.args );
        }
        catch ( ParseException e )
        {
            System.err.println( "Unable to parse command line options: " + e.getMessage() );
            cliManager.displayHelp( System.out );
            throw e;
        }

        if ( cliRequest.commandLine.hasOption( CLIManager.HELP ) )
        {
            cliManager.displayHelp( System.out );
            throw new DefaultMavenExecutionRequestBuilder.ExitException( 0 );
        }

        if ( cliRequest.commandLine.hasOption( CLIManager.VERSION ) )
        {
            System.out.println( CLIReportingUtils.showVersion() );
            throw new DefaultMavenExecutionRequestBuilder.ExitException( 0 );
        }
    }

    private void properties( CommonCliRequest cliRequest )
    {
        populateProperties( cliRequest.commandLine, cliRequest.systemProperties, cliRequest.userProperties );
    }

    // ----------------------------------------------------------------------
    // System properties handling
    // ----------------------------------------------------------------------

    static void populateProperties(CommandLine commandLine, Properties systemProperties, Properties userProperties )
    {
        EnvironmentUtils.addEnvVars( systemProperties );

        // ----------------------------------------------------------------------
        // Options that are set on the command line become system properties
        // and therefore are set in the session properties. System properties
        // are most dominant.
        // ----------------------------------------------------------------------

        if ( commandLine.hasOption( CLIManager.SET_SYSTEM_PROPERTY ) )
        {
            String[] defStrs = commandLine.getOptionValues( CLIManager.SET_SYSTEM_PROPERTY );

            if ( defStrs != null )
            {
                for ( String defStr : defStrs )
                {
                    setCliProperty( defStr, userProperties );
                }
            }
        }

        systemProperties.putAll( System.getProperties() );

        // ----------------------------------------------------------------------
        // Properties containing info about the currently running version of Maven
        // These override any corresponding properties set on the command line
        // ----------------------------------------------------------------------

        Properties buildProperties = CLIReportingUtils.getBuildProperties();

        String mavenVersion = buildProperties.getProperty( CLIReportingUtils.BUILD_VERSION_PROPERTY );
        systemProperties.setProperty( "maven.version", mavenVersion );

        String mavenBuildVersion = CLIReportingUtils.createMavenVersionString( buildProperties );
        systemProperties.setProperty( "maven.build.version", mavenBuildVersion );
    }

    private static void setCliProperty( String property, Properties properties )
    {
        String name;

        String value;

        int i = property.indexOf( "=" );

        if ( i <= 0 )
        {
            name = property.trim();

            value = "true";
        }
        else
        {
            name = property.substring( 0, i ).trim();

            value = property.substring( i + 1 );
        }

        properties.setProperty( name, value );

        // ----------------------------------------------------------------------
        // I'm leaving the setting of system properties here as not to break
        // the SystemPropertyProfileActivator. This won't harm embedding. jvz.
        // ----------------------------------------------------------------------

        System.setProperty( name, value );
    }

    /**
     * configure logging
     */
    @SuppressFBWarnings({"DM_DEFAULT_ENCODING","URF_UNREAD_FIELD","PATH_TRAVERSAL_IN"})
    private void logging( CommonCliRequest cliRequest )
    {
        cliRequest.debug = cliRequest.commandLine.hasOption( CLIManager.DEBUG );
        cliRequest.quiet = !cliRequest.debug && cliRequest.commandLine.hasOption( CLIManager.QUIET );
        cliRequest.showErrors = cliRequest.debug || cliRequest.commandLine.hasOption( CLIManager.ERRORS );

        slf4jLoggerFactory = LoggerFactory.getILoggerFactory();
        Slf4jConfiguration slf4jConfiguration = Slf4jConfigurationFactory.getConfiguration( slf4jLoggerFactory );

        if ( cliRequest.debug )
        {
            cliRequest.request.setLoggingLevel( MavenExecutionRequest.LOGGING_LEVEL_DEBUG );
            slf4jConfiguration.setRootLoggerLevel( Slf4jConfiguration.Level.DEBUG );
        }
        else if ( cliRequest.quiet )
        {
            cliRequest.request.setLoggingLevel( MavenExecutionRequest.LOGGING_LEVEL_ERROR );
            slf4jConfiguration.setRootLoggerLevel( Slf4jConfiguration.Level.ERROR );
        }
        else
        {
            cliRequest.request.setLoggingLevel( MavenExecutionRequest.LOGGING_LEVEL_INFO );
            slf4jConfiguration.setRootLoggerLevel( Slf4jConfiguration.Level.INFO );
        }

        if ( cliRequest.commandLine.hasOption( CLIManager.LOG_FILE ) )
        {
            File logFile = new File( cliRequest.commandLine.getOptionValue( CLIManager.LOG_FILE ) );
            logFile = resolveFile( logFile, cliRequest.workingDirectory );

            // redirect stdout and stderr to file
            try
            {
                PrintStream ps = new PrintStream( new FileOutputStream( logFile ) );
                System.setOut( ps );
                System.setErr( ps );
            }
            catch ( FileNotFoundException e )
            {
                //
                // Ignore
                //
            }
        }

        slf4jConfiguration.activate();

        plexusLoggerManager = new Slf4jLoggerManager();
        // slf4jLogger = slf4jLoggerFactory.getLogger( this.getClass().getName() );
    }

    @SuppressFBWarnings({"PATH_TRAVERSAL_IN"})
    static File resolveFile( File file, String workingDirectory )
    {
        if ( file == null )
        {
            return null;
        }
        else if ( file.isAbsolute() )
        {
            return file;
        }
        else if ( file.getPath().startsWith( File.separator ) )
        {
            // drive-relative Windows path
            return file.getAbsoluteFile();
        }
        else
        {
            return new File( workingDirectory, file.getPath() ).getAbsoluteFile();
        }
    }

    private void version( CommonCliRequest cliRequest )
    {
        if ( cliRequest.debug || cliRequest.commandLine.hasOption( CLIManager.SHOW_VERSION ) )
        {
            System.out.println( CLIReportingUtils.showVersion() );
        }
    }
}
