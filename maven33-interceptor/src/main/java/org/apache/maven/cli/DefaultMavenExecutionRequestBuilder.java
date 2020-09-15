package org.apache.maven.cli;

/*
 * Copyright Olivier Lamy
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.maven.InternalErrorException;
import org.apache.maven.building.FileSource;
import org.apache.maven.building.Source;
import org.apache.maven.cli.event.DefaultEventSpyContext;
import org.apache.maven.cli.event.ExecutionEventLogger;
import org.apache.maven.cli.logging.Slf4jConfiguration;
import org.apache.maven.cli.logging.Slf4jConfigurationFactory;
import org.apache.maven.cli.logging.Slf4jLoggerManager;
import org.apache.maven.cli.logging.Slf4jStdoutLogger;
import org.apache.maven.cli.transfer.ConsoleMavenTransferListener;
import org.apache.maven.cli.transfer.QuietMavenTransferListener;
import org.apache.maven.cli.transfer.Slf4jMavenTransferListener;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.exception.ExceptionSummary;
import org.apache.maven.execution.*;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.apache.maven.settings.building.*;
import org.apache.maven.toolchain.building.*;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.LoggerManager;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.transfer.TransferListener;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecUtil;
import org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * Most of code is coming from asf svn repo waiting before having available
 * @author Olivier Lamy
 * @since 1.8
 */
@Component( role = MavenExecutionRequestBuilder.class)
public class DefaultMavenExecutionRequestBuilder
    implements MavenExecutionRequestBuilder, Initializable
{

    @Requirement
    private SettingsBuilder settingsBuilder;

    @Requirement
    private MavenExecutionRequestPopulator executionRequestPopulator;

    @Requirement
    private Logger plexusLogger;

    @Requirement
    private ModelProcessor modelProcessor;

    @Requirement
    private PlexusContainer plexusContainer;

    private DefaultSecDispatcher dispatcher;

    private LoggerManager plexusLoggerManager;

    private Logger slf4jLogger;

    private ILoggerFactory slf4jLoggerFactory;

    private EventSpyDispatcher eventSpyDispatcher;

    private ToolchainsBuilder toolchainsBuilder;

    private static final String EXT_CLASS_PATH = "maven.ext.class.path";
    
    static final String DEFAULT_BUILD_TIMESTAMP_FORMAT = "yyyyMMdd-HHmm";

    public void initialize()
        throws InitializationException
    {
        try
        {
            dispatcher = (DefaultSecDispatcher) plexusContainer.lookup( SecDispatcher.class, "maven" );
            eventSpyDispatcher = plexusContainer.lookup( EventSpyDispatcher.class );
            modelProcessor = plexusContainer.lookup( ModelProcessor.class );
            executionRequestPopulator = plexusContainer.lookup( MavenExecutionRequestPopulator.class );
            settingsBuilder = plexusContainer.lookup( SettingsBuilder.class );
            toolchainsBuilder = plexusContainer.lookup( ToolchainsBuilder.class );

        }
        catch ( ComponentLookupException e )
        {
            throw new InitializationException( e.getMessage(), e );
        }
    }

    /**
     * @throws MavenExecutionRequestPopulationException 
     */
    public MavenExecutionRequest getMavenExecutionRequest( String[] args, PrintStream printStream)
        throws MavenExecutionRequestPopulationException, SettingsBuildingException,
        MavenExecutionRequestsBuilderException
    {
        try
        {
            CliRequest cliRequest = new CliRequest( args, null );
            initialize( cliRequest );
            cli( cliRequest );
            logging( cliRequest );
            version( cliRequest );
            properties( cliRequest );
            // we are in a container so no need
            //localContainer = container( cliRequest );
            commands( cliRequest );
            settings( cliRequest );
            populateRequest( cliRequest );
            toolchains( cliRequest );
            encryption( cliRequest );
            repository( cliRequest );

            MavenExecutionRequest request = executionRequestPopulator.populateDefaults( cliRequest.request );

            DefaultEventSpyContext eventSpyContext = new DefaultEventSpyContext();
            Map<String, Object> data = eventSpyContext.getData();
            data.put( "plexus", plexusContainer );
            data.put( "workingDirectory",  cliRequest.workingDirectory );
            data.put( "systemProperties", cliRequest.systemProperties );
            data.put( "userProperties", cliRequest.userProperties );
            data.put( "versionProperties", CLIReportingUtils.getBuildProperties() );
            eventSpyDispatcher.init( eventSpyContext );


            return request;
        }
        catch ( Exception e )
        {
            throw new MavenExecutionRequestsBuilderException( e.getMessage(), e );
        }

    }


    private void initialize( CliRequest cliRequest )
    {
        if ( cliRequest.workingDirectory == null )
        {
            cliRequest.workingDirectory = System.getProperty( "user.dir" );
        }

        //
        // Make sure the Maven home directory is an absolute path to save us from confusion with say drive-relative
        // Windows paths.
        //
        String mavenHome = System.getProperty( "maven.home" );

        if ( mavenHome != null )
        {
            System.setProperty( "maven.home", new File( mavenHome ).getAbsolutePath() );
        }
    }

    private void cli( CliRequest cliRequest )
        throws Exception
    {
        //
        // Parsing errors can happen during the processing of the arguments and we prefer not having to check if the logger is null
        // and construct this so we can use an SLF4J logger everywhere.
        //
        slf4jLogger = new Slf4jStdoutLogger();

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
            throw new ExitException( 0 );
        }

        if ( cliRequest.commandLine.hasOption( CLIManager.VERSION ) )
        {
            System.out.println( CLIReportingUtils.showVersion() );
            throw new ExitException( 0 );
        }
    }

    /**
     * configure logging
     */
    @SuppressFBWarnings({"DM_DEFAULT_ENCODING","URF_UNREAD_FIELD","PATH_TRAVERSAL_IN"})
    private void logging( CliRequest cliRequest )
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
        slf4jLogger = slf4jLoggerFactory.getLogger( this.getClass().getName() );
    }

    private void version( CliRequest cliRequest )
    {
        if ( cliRequest.debug || cliRequest.commandLine.hasOption( CLIManager.SHOW_VERSION ) )
        {
            System.out.println( CLIReportingUtils.showVersion() );
        }
    }

    private void commands( CliRequest cliRequest )
    {
        if ( cliRequest.showErrors )
        {
            slf4jLogger.info( "Error stacktraces are turned on." );
        }

        if ( MavenExecutionRequest.CHECKSUM_POLICY_WARN.equals( cliRequest.request.getGlobalChecksumPolicy() ) )
        {
            slf4jLogger.info( "Disabling strict checksum verification on all artifact downloads." );
        }
        else if ( MavenExecutionRequest.CHECKSUM_POLICY_FAIL.equals( cliRequest.request.getGlobalChecksumPolicy() ) )
        {
            slf4jLogger.info( "Enabling strict checksum verification on all artifact downloads." );
        }
    }

    private void properties( CliRequest cliRequest )
    {
        populateProperties( cliRequest.commandLine, cliRequest.systemProperties, cliRequest.userProperties );
    }
    /**
    private PlexusContainer container( CliRequest cliRequest )
        throws Exception
    {
        if ( cliRequest.classWorld == null )
        {
            cliRequest.classWorld = new ClassWorld( "plexus.core", Thread.currentThread().getContextClassLoader() );
        }

        DefaultPlexusContainer container = null;

        ContainerConfiguration cc = new DefaultContainerConfiguration()
            .setClassWorld( cliRequest.classWorld )
            .setRealm( setupContainerRealm( cliRequest ) )
            .setClassPathScanning( PlexusConstants.SCANNING_INDEX )
            .setAutoWiring( true )
            .setName( "maven" );

        container = new DefaultPlexusContainer( cc, new AbstractModule()
        {
            protected void configure()
            {
                bind( ILoggerFactory.class ).toInstance( slf4jLoggerFactory );
            }
        } );

        // NOTE: To avoid inconsistencies, we'll use the TCCL exclusively for lookups
        container.setLookupRealm( null );

        container.setLoggerManager( plexusLoggerManager );

        customizeContainer( container );

        container.getLoggerManager().setThresholds( cliRequest.request.getLoggingLevel() );

        Thread.currentThread().setContextClassLoader( container.getContainerRealm() );

        eventSpyDispatcher = container.lookup( EventSpyDispatcher.class );

        DefaultEventSpyContext eventSpyContext = new DefaultEventSpyContext();
        Map<String, Object> data = eventSpyContext.getData();
        data.put( "plexus", container );
        data.put( "workingDirectory", cliRequest.workingDirectory );
        data.put( "systemProperties", cliRequest.systemProperties );
        data.put( "userProperties", cliRequest.userProperties );
        data.put( "versionProperties", CLIReportingUtils.getBuildProperties() );
        eventSpyDispatcher.init( eventSpyContext );

        // refresh logger in case container got customized by spy
        slf4jLogger = slf4jLoggerFactory.getLogger( this.getClass().getName() );

        maven = container.lookup( Maven.class );

        executionRequestPopulator = container.lookup( MavenExecutionRequestPopulator.class );

        modelProcessor = createModelProcessor( container );

        settingsBuilder = container.lookup( SettingsBuilder.class );

        dispatcher = (DefaultSecDispatcher) container.lookup( SecDispatcher.class, "maven" );

        return container;
    }
    **/

    // FIXME this must be done!!!
    @SuppressFBWarnings({"PATH_TRAVERSAL_IN"})
    private ClassRealm setupContainerRealm( CliRequest cliRequest )
        throws Exception
    {
        ClassRealm containerRealm = null;

        String extClassPath = cliRequest.userProperties.getProperty( EXT_CLASS_PATH );
        if ( extClassPath == null )
        {
            extClassPath = cliRequest.systemProperties.getProperty( EXT_CLASS_PATH );
        }

        if ( StringUtils.isNotEmpty( extClassPath ) )
        {
            String[] jars = StringUtils.split( extClassPath, File.pathSeparator );

            if ( jars.length > 0 )
            {
                ClassRealm coreRealm = cliRequest.classWorld.getClassRealm( "plexus.core" );
                if ( coreRealm == null )
                {
                    coreRealm = (ClassRealm) cliRequest.classWorld.getRealms().iterator().next();
                }

                ClassRealm extRealm = cliRequest.classWorld.newRealm( "maven.ext", null );

                slf4jLogger.debug( "Populating class realm " + extRealm.getId() );

                for ( String jar : jars )
                {
                    File file = resolveFile( new File( jar ), cliRequest.workingDirectory );

                    slf4jLogger.debug( "  Included " + file );

                    extRealm.addURL( file.toURI().toURL() );
                }

                extRealm.setParentRealm( coreRealm );

                containerRealm = extRealm;
            }
        }

        return containerRealm;
    }

    //
    // This should probably be a separate tool and not be baked into Maven.
    //
    private void encryption( CliRequest cliRequest )
        throws Exception
    {
        if ( cliRequest.commandLine.hasOption( CLIManager.ENCRYPT_MASTER_PASSWORD ) )
        {
            String passwd = cliRequest.commandLine.getOptionValue( CLIManager.ENCRYPT_MASTER_PASSWORD );

            DefaultPlexusCipher cipher = new DefaultPlexusCipher();

            System.out.println( cipher.encryptAndDecorate( passwd, DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION ) );

            throw new ExitException( 0 );
        }
        else if ( cliRequest.commandLine.hasOption( CLIManager.ENCRYPT_PASSWORD ) )
        {
            String passwd = cliRequest.commandLine.getOptionValue( CLIManager.ENCRYPT_PASSWORD );

            String configurationFile = dispatcher.getConfigurationFile();

            if ( configurationFile.startsWith( "~" ) )
            {
                configurationFile = System.getProperty( "user.home" ) + configurationFile.substring( 1 );
            }

            String file = System.getProperty( DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION, configurationFile );

            String master = null;

            SettingsSecurity sec = SecUtil.read( file, true );
            if ( sec != null )
            {
                master = sec.getMaster();
            }

            if ( master == null )
            {
                throw new IllegalStateException( "Master password is not set in the setting security file: " + file );
            }

            DefaultPlexusCipher cipher = new DefaultPlexusCipher();
            String masterPasswd = cipher.decryptDecorated( master, DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION );
            System.out.println( cipher.encryptAndDecorate( passwd, masterPasswd ) );

            throw new ExitException( 0 );
        }
    }

    private void repository( CliRequest cliRequest )
        throws Exception
    {
        if ( cliRequest.commandLine.hasOption( CLIManager.LEGACY_LOCAL_REPOSITORY ) || Boolean.getBoolean( "maven.legacyLocalRepo" ) )
        {
            cliRequest.request.setUseLegacyLocalRepository( true );
        }
    }

    /*
    private int execute( CliRequest cliRequest )
    {
        eventSpyDispatcher.onEvent( cliRequest.request );

        MavenExecutionResult result = maven.execute( cliRequest.request );

        eventSpyDispatcher.onEvent( result );

        eventSpyDispatcher.close();

        if ( result.hasExceptions() )
        {
            ExceptionHandler handler = new DefaultExceptionHandler();

            Map<String, String> references = new LinkedHashMap<String, String>();

            MavenProject project = null;

            for ( Throwable exception : result.getExceptions() )
            {
                ExceptionSummary summary = handler.handleException( exception );

                logSummary( summary, references, "", cliRequest.showErrors );

                if ( project == null && exception instanceof LifecycleExecutionException )
                {
                    project = ( (LifecycleExecutionException) exception ).getProject();
                }
            }

            slf4jLogger.error( "" );

            if ( !cliRequest.showErrors )
            {
                slf4jLogger.error( "To see the full stack trace of the errors, re-run Maven with the -e switch." );
            }
            if ( !slf4jLogger.isDebugEnabled() )
            {
                slf4jLogger.error( "Re-run Maven using the -X switch to enable full debug logging." );
            }

            if ( !references.isEmpty() )
            {
                slf4jLogger.error( "" );
                slf4jLogger.error( "For more information about the errors and possible solutions"
                                       + ", please read the following articles:" );

                for ( Map.Entry<String, String> entry : references.entrySet() )
                {
                    slf4jLogger.error( entry.getValue() + " " + entry.getKey() );
                }
            }

            if ( project != null && !project.equals( result.getTopologicallySortedProjects().get( 0 ) ) )
            {
                slf4jLogger.error( "" );
                slf4jLogger.error( "After correcting the problems, you can resume the build with the command" );
                slf4jLogger.error( "  mvn <goals> -rf :" + project.getArtifactId() );
            }

            if ( MavenExecutionRequest.REACTOR_FAIL_NEVER.equals( cliRequest.request.getReactorFailureBehavior() ) )
            {
                slf4jLogger.info( "Build failures were ignored." );

                return 0;
            }
            else
            {
                return 1;
            }
        }
        else
        {
            return 0;
        }
    }
    */
    private void logSummary( ExceptionSummary summary, Map<String, String> references, String indent,
                             boolean showErrors )
    {
        String referenceKey = "";

        if ( StringUtils.isNotEmpty( summary.getReference() ) )
        {
            referenceKey = references.get( summary.getReference() );
            if ( referenceKey == null )
            {
                referenceKey = "[Help " + ( references.size() + 1 ) + "]";
                references.put( summary.getReference(), referenceKey );
            }
        }

        String msg = summary.getMessage();

        if ( StringUtils.isNotEmpty( referenceKey ) )
        {
            if ( msg.indexOf( '\n' ) < 0 )
            {
                msg += " -> " + referenceKey;
            }
            else
            {
                msg += "\n-> " + referenceKey;
            }
        }

        String[] lines = msg.split( "(\r\n)|(\r)|(\n)" );

        for ( int i = 0; i < lines.length; i++ )
        {
            String line = indent + lines[i].trim();

            if ( i == lines.length - 1 && ( showErrors || ( summary.getException() instanceof InternalErrorException ) ) )
            {
                slf4jLogger.error( line, summary.getException() );
            }
            else
            {
                slf4jLogger.error( line );
            }
        }

        indent += "  ";

        for ( ExceptionSummary child : summary.getChildren() )
        {
            logSummary( child, references, indent, showErrors );
        }
    }


    private void toolchains(CliRequest cliRequest)
       throws Exception{
        DefaultToolchainsBuildingRequest toolchainsBuildingRequest = new DefaultToolchainsBuildingRequest();
        if (cliRequest.request.getUserToolchainsFile().isFile()) {
            toolchainsBuildingRequest.setUserToolchainsSource(new FileSource(cliRequest.request.getUserToolchainsFile()));
        }
        if (MavenCli.DEFAULT_GLOBAL_TOOLCHAINS_FILE.isFile()) {
            toolchainsBuildingRequest.setGlobalToolchainsSource(new FileSource(MavenCli.DEFAULT_GLOBAL_TOOLCHAINS_FILE));
        }
        slf4jLogger.debug( "Reading global toolchains from "
                + getToolchainsLocation( toolchainsBuildingRequest.getGlobalToolchainsSource(), cliRequest.request.getGlobalToolchainsFile() ) );
        slf4jLogger.debug( "Reading user toolchains from "
                + getToolchainsLocation( toolchainsBuildingRequest.getUserToolchainsSource(), cliRequest.request.getUserToolchainsFile() ) );
        try {
            ToolchainsBuildingResult toolchainsBuildingResult = toolchainsBuilder.build(toolchainsBuildingRequest);
            eventSpyDispatcher.onEvent( toolchainsBuildingRequest );

            executionRequestPopulator.populateFromToolchains( cliRequest.request,
                    toolchainsBuildingResult.getEffectiveToolchains() );
        } catch (ToolchainsBuildingException e) {
            throw new MavenExecutionRequestsBuilderException( e.getMessage(), e );
        }
    }

    @SuppressFBWarnings({"PATH_TRAVERSAL_IN"})
    private void settings( CliRequest cliRequest )
        throws Exception
    {
        File userSettingsFile;

        if ( cliRequest.commandLine.hasOption( CLIManager.ALTERNATE_USER_SETTINGS ) )
        {
            userSettingsFile = new File( cliRequest.commandLine.getOptionValue( CLIManager.ALTERNATE_USER_SETTINGS ) );
            userSettingsFile = resolveFile( userSettingsFile, cliRequest.workingDirectory );

            if ( !userSettingsFile.isFile() )
            {
                throw new FileNotFoundException( "The specified user settings file does not exist: "
                                                     + userSettingsFile );
            }
        }
        else
        {
            userSettingsFile = MavenCli.DEFAULT_USER_SETTINGS_FILE;
        }

        File globalSettingsFile;

        if ( cliRequest.commandLine.hasOption( CLIManager.ALTERNATE_GLOBAL_SETTINGS ) )
        {
            globalSettingsFile =
                new File( cliRequest.commandLine.getOptionValue( CLIManager.ALTERNATE_GLOBAL_SETTINGS ) );
            globalSettingsFile = resolveFile( globalSettingsFile, cliRequest.workingDirectory );

            if ( !globalSettingsFile.isFile() )
            {
                throw new FileNotFoundException( "The specified global settings file does not exist: "
                                                     + globalSettingsFile );
            }
        }
        else
        {
            globalSettingsFile = MavenCli.DEFAULT_GLOBAL_SETTINGS_FILE;
        }

        cliRequest.request.setGlobalSettingsFile( globalSettingsFile );
        cliRequest.request.setUserSettingsFile( userSettingsFile );

        SettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
        settingsRequest.setGlobalSettingsFile( globalSettingsFile );
        settingsRequest.setUserSettingsFile( userSettingsFile );
        settingsRequest.setSystemProperties( cliRequest.systemProperties );
        settingsRequest.setUserProperties( cliRequest.userProperties );

        eventSpyDispatcher.onEvent( settingsRequest );

        slf4jLogger.debug( "Reading global settings from "
                               + getSettingsLocation( settingsRequest.getGlobalSettingsSource(), settingsRequest.getGlobalSettingsFile() ) );
        slf4jLogger.debug( "Reading user settings from "
                               + getSettingsLocation( settingsRequest.getUserSettingsSource(), settingsRequest.getUserSettingsFile() ) );

        SettingsBuildingResult settingsResult = settingsBuilder.build( settingsRequest );

        eventSpyDispatcher.onEvent( settingsResult );

        executionRequestPopulator.populateFromSettings( cliRequest.request, settingsResult.getEffectiveSettings() );

        if ( !settingsResult.getProblems().isEmpty() && slf4jLogger.isWarnEnabled() )
        {
            slf4jLogger.warn( "" );
            slf4jLogger.warn( "Some problems were encountered while building the effective settings" );

            for ( SettingsProblem problem : settingsResult.getProblems() )
            {
                slf4jLogger.warn( problem.getMessage() + " @ " + problem.getLocation() );
            }

            slf4jLogger.warn( "" );
        }
    }

    private Object getSettingsLocation( SettingsSource source, File file )
    {
        if ( source != null )
        {
            return source.getLocation();
        }
        return file;
    }
    private Object getToolchainsLocation(Source source, File file )
    {
        if ( source != null )
        {
            return source.getLocation();
        }
        return file;
    }

    @SuppressFBWarnings({"PATH_TRAVERSAL_IN"})
    private MavenExecutionRequest populateRequest( CliRequest cliRequest )
    {
        MavenExecutionRequest request = cliRequest.request;
        CommandLine commandLine = cliRequest.commandLine;
        String workingDirectory = cliRequest.workingDirectory;
        boolean quiet = cliRequest.quiet;
        boolean showErrors = cliRequest.showErrors;

        String[] deprecatedOptions = { "up", "npu", "cpu", "npr" };
        for ( String deprecatedOption : deprecatedOptions )
        {
            if ( commandLine.hasOption( deprecatedOption ) )
            {
                slf4jLogger.warn( "Command line option -" + deprecatedOption
                                      + " is deprecated and will be removed in future Maven versions." );
            }
        }

        // ----------------------------------------------------------------------
        // Now that we have everything that we need we will fire up plexus and
        // bring the maven component to life for use.
        // ----------------------------------------------------------------------

        if ( commandLine.hasOption( CLIManager.BATCH_MODE ) )
        {
            request.setInteractiveMode( false );
        }

        boolean noSnapshotUpdates = false;
        if ( commandLine.hasOption( CLIManager.SUPRESS_SNAPSHOT_UPDATES ) )
        {
            noSnapshotUpdates = true;
        }

        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        @SuppressWarnings( "unchecked" )
        List<String> goals = commandLine.getArgList();

        boolean recursive = true;

        // this is the default behavior.
        String reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_FAST;

        if ( commandLine.hasOption( CLIManager.NON_RECURSIVE ) )
        {
            recursive = false;
        }

        if ( commandLine.hasOption( CLIManager.FAIL_FAST ) )
        {
            reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_FAST;
        }
        else if ( commandLine.hasOption( CLIManager.FAIL_AT_END ) )
        {
            reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_AT_END;
        }
        else if ( commandLine.hasOption( CLIManager.FAIL_NEVER ) )
        {
            reactorFailureBehaviour = MavenExecutionRequest.REACTOR_FAIL_NEVER;
        }

        if ( commandLine.hasOption( CLIManager.OFFLINE ) )
        {
            request.setOffline( true );
        }

        boolean updateSnapshots = false;

        if ( commandLine.hasOption( CLIManager.UPDATE_SNAPSHOTS ) )
        {
            updateSnapshots = true;
        }

        String globalChecksumPolicy = null;

        if ( commandLine.hasOption( CLIManager.CHECKSUM_FAILURE_POLICY ) )
        {
            globalChecksumPolicy = MavenExecutionRequest.CHECKSUM_POLICY_FAIL;
        }
        else if ( commandLine.hasOption( CLIManager.CHECKSUM_WARNING_POLICY ) )
        {
            globalChecksumPolicy = MavenExecutionRequest.CHECKSUM_POLICY_WARN;
        }

        File baseDirectory = new File( workingDirectory, "" ).getAbsoluteFile();

        // ----------------------------------------------------------------------
        // Profile Activation
        // ----------------------------------------------------------------------

        List<String> activeProfiles = new ArrayList<String>();

        List<String> inactiveProfiles = new ArrayList<String>();

        if ( commandLine.hasOption( CLIManager.ACTIVATE_PROFILES ) )
        {
            String[] profileOptionValues = commandLine.getOptionValues( CLIManager.ACTIVATE_PROFILES );
            if ( profileOptionValues != null )
            {
                for ( String profileOptionValue : profileOptionValues )
                {
                    StringTokenizer profileTokens = new StringTokenizer( profileOptionValue, "," );

                    while ( profileTokens.hasMoreTokens() )
                    {
                        String profileAction = profileTokens.nextToken().trim();

                        if ( profileAction.startsWith( "-" ) || profileAction.startsWith( "!" ) )
                        {
                            inactiveProfiles.add( profileAction.substring( 1 ) );
                        }
                        else if ( profileAction.startsWith( "+" ) )
                        {
                            activeProfiles.add( profileAction.substring( 1 ) );
                        }
                        else
                        {
                            activeProfiles.add( profileAction );
                        }
                    }
                }
            }
        }

        TransferListener transferListener;

        if ( quiet )
        {
            transferListener = new QuietMavenTransferListener();
        }
        else if ( request.isInteractiveMode() && !cliRequest.commandLine.hasOption( CLIManager.LOG_FILE ) )
        {
            //
            // If we're logging to a file then we don't want the console transfer listener as it will spew
            // download progress all over the place
            //
            transferListener = getConsoleTransferListener();
        }
        else
        {
            transferListener = getBatchTransferListener();
        }

        ExecutionListener executionListener = new ExecutionEventLogger();
        executionListener = eventSpyDispatcher.chainListener( executionListener );

        String alternatePomFile = null;
        if ( commandLine.hasOption( CLIManager.ALTERNATE_POM_FILE ) )
        {
            alternatePomFile = commandLine.getOptionValue( CLIManager.ALTERNATE_POM_FILE );
        }

        File userToolchainsFile;
        if ( commandLine.hasOption( CLIManager.ALTERNATE_USER_TOOLCHAINS ) )
        {
            userToolchainsFile = new File( commandLine.getOptionValue( CLIManager.ALTERNATE_USER_TOOLCHAINS ) );
            userToolchainsFile = resolveFile( userToolchainsFile, workingDirectory );
        }
        else
        {
            userToolchainsFile = MavenCli.DEFAULT_USER_TOOLCHAINS_FILE;
        }

        request.setBaseDirectory( baseDirectory ).setGoals( goals )
            .setSystemProperties( cliRequest.systemProperties )
            .setUserProperties( cliRequest.userProperties )
            .setReactorFailureBehavior( reactorFailureBehaviour ) // default: fail fast
            .setRecursive( recursive ) // default: true
            .setShowErrors( showErrors ) // default: false
            .addActiveProfiles( activeProfiles ) // optional
            .addInactiveProfiles( inactiveProfiles ) // optional
            .setExecutionListener( executionListener )
            .setTransferListener( transferListener ) // default: batch mode which goes along with interactive
            .setUpdateSnapshots( updateSnapshots ) // default: false
            .setNoSnapshotUpdates( noSnapshotUpdates ) // default: false
            .setGlobalChecksumPolicy( globalChecksumPolicy ) // default: warn
            .setUserToolchainsFile( userToolchainsFile );

        if ( alternatePomFile != null )
        {
            File pom = resolveFile( new File( alternatePomFile ), workingDirectory );
            if ( pom.isDirectory() )
            {
                pom = new File( pom, "pom.xml" );
            }

            request.setPom( pom );
        }
        else
        {
            File pom = modelProcessor.locatePom( baseDirectory );

            if ( pom.isFile() )
            {
                request.setPom( pom );
            }
        }

        if ( ( request.getPom() != null ) && ( request.getPom().getParentFile() != null ) )
        {
            request.setBaseDirectory( request.getPom().getParentFile() );
        }

        if ( commandLine.hasOption( CLIManager.RESUME_FROM ) )
        {
            request.setResumeFrom( commandLine.getOptionValue( CLIManager.RESUME_FROM ) );
        }

        if ( commandLine.hasOption( CLIManager.PROJECT_LIST ) )
        {
            String[] values = commandLine.getOptionValues( CLIManager.PROJECT_LIST );
            List<String> projects = new ArrayList<String>();
            List<String> excludedProjects = new ArrayList<String>();
            for ( String value : values )
            {
                String[] tmp = StringUtils.split( value, "," );
                for(String project: tmp){
                    if(project.startsWith("!")){
                        excludedProjects.add(project.substring(1));
                    } else {
                        projects.add(project);
                    }
                }
            }
            request.setSelectedProjects( projects );
            request.setExcludedProjects(excludedProjects);
        }

        if ( commandLine.hasOption( CLIManager.ALSO_MAKE )
            && !commandLine.hasOption( CLIManager.ALSO_MAKE_DEPENDENTS ) )
        {
            request.setMakeBehavior( MavenExecutionRequest.REACTOR_MAKE_UPSTREAM );
        }
        else if ( !commandLine.hasOption( CLIManager.ALSO_MAKE )
            && commandLine.hasOption( CLIManager.ALSO_MAKE_DEPENDENTS ) )
        {
            request.setMakeBehavior( MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM );
        }
        else if ( commandLine.hasOption( CLIManager.ALSO_MAKE )
            && commandLine.hasOption( CLIManager.ALSO_MAKE_DEPENDENTS ) )
        {
            request.setMakeBehavior( MavenExecutionRequest.REACTOR_MAKE_BOTH );
        }

        String localRepoProperty = request.getUserProperties().getProperty( MavenCli.LOCAL_REPO_PROPERTY );

        if ( localRepoProperty == null )
        {
            localRepoProperty = request.getSystemProperties().getProperty( MavenCli.LOCAL_REPO_PROPERTY );
        }

        if ( localRepoProperty != null )
        {
            request.setLocalRepositoryPath( localRepoProperty );
        }

        final String threadConfiguration = commandLine.hasOption( CLIManager.THREADS )
            ? commandLine.getOptionValue( CLIManager.THREADS )
            : request.getSystemProperties().getProperty(
                MavenCli.THREADS_DEPRECATED ); // TODO: Remove this setting. Note that the int-tests use it

        if ( threadConfiguration != null )
        {
        	int threadCount = 1;
        	try {
        		threadCount = Integer.parseInt(threadConfiguration.replace( "C", "" ).replace( "W", "" ).replace( "auto", "" ));
        	} catch (NumberFormatException e) {
        		throw new IllegalArgumentException("Must provide a thread count for -T");
        	}
        	if (threadConfiguration.contains("C")) {
        		threadCount *= Runtime.getRuntime().availableProcessors();
        	}
        	if (threadCount > 1) {
        		request.setDegreeOfConcurrency(threadCount);
        		request.setBuilderId("multithreaded");
        	}
        }

        request.setCacheNotFound( true );
        request.setCacheTransferError( false );

        return request;
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

    // ----------------------------------------------------------------------
    // System properties handling
    // ----------------------------------------------------------------------

    static void populateProperties( CommandLine commandLine, Properties systemProperties, Properties userProperties )
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

    static class CliRequest
    {
        String[] args;
        CommandLine commandLine;
        ClassWorld classWorld;
        String workingDirectory;
        boolean debug;
        boolean quiet;
        boolean showErrors = true;
        Properties userProperties = new Properties();
        Properties systemProperties = new Properties();
        MavenExecutionRequest request;

        CliRequest( String[] args, ClassWorld classWorld )
        {
            this.args = args;
            this.classWorld = classWorld;
            this.request = new DefaultMavenExecutionRequest();
        }
    }

    static class ExitException
        extends Exception
    {

        public int exitCode;

        public ExitException( int exitCode )
        {
            this.exitCode = exitCode;
        }

    }

    //
    // Customizations available via the CLI
    //

    protected TransferListener getConsoleTransferListener()
    {
        return new ConsoleMavenTransferListener( System.out );
    }

    protected TransferListener getBatchTransferListener()
    {
        return new Slf4jMavenTransferListener();
    }

    protected void customizeContainer( PlexusContainer container )
    {
    }

    protected ModelProcessor createModelProcessor( PlexusContainer container )
        throws ComponentLookupException
    {
        return container.lookup( ModelProcessor.class );
    }
    
}
