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
import org.apache.maven.InternalErrorException;
import org.apache.maven.building.FileSource;
import org.apache.maven.building.Source;
import org.apache.maven.cli.configuration.ConfigurationProcessor;
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor;
import org.apache.maven.cli.event.DefaultEventSpyContext;
import org.apache.maven.cli.event.ExecutionEventLogger;
import org.apache.maven.cli.transfer.ConsoleMavenTransferListener;
import org.apache.maven.cli.transfer.QuietMavenTransferListener;
import org.apache.maven.cli.transfer.Slf4jMavenTransferListener;
import org.apache.maven.eventspy.internal.EventSpyDispatcher;
import org.apache.maven.exception.ExceptionSummary;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.building.SettingsSource;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.maven.toolchain.building.DefaultToolchainsBuildingRequest;
import org.apache.maven.toolchain.building.ToolchainsBuilder;
import org.apache.maven.toolchain.building.ToolchainsBuildingException;
import org.apache.maven.toolchain.building.ToolchainsBuildingResult;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.transfer.TransferListener;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecUtil;
import org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * Most of code is coming from asf svn repo waiting before having available
 * @author Olivier Lamy
 * @since 1.9
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
    private ModelProcessor modelProcessor;

    @Requirement
    private PlexusContainer plexusContainer;

    private DefaultSecDispatcher dispatcher;

    private Map<String, ConfigurationProcessor> configurationProcessors;

    private Logger slf4jLogger;

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
            configurationProcessors = plexusContainer.lookupMap( ConfigurationProcessor.class );
            slf4jLogger = plexusContainer.lookup( ILoggerFactory.class ).getLogger( this.getClass().getName() );
        }
        catch ( ComponentLookupException e )
        {
            throw new InitializationException( e.getMessage(), e );
        }
    }

    @Override
    public MavenExecutionRequest getMavenExecutionRequest( CommonCliRequest commonCliRequest )
        throws MavenExecutionRequestPopulationException, SettingsBuildingException, MavenExecutionRequestsBuilderException {

        try
        {
            CliRequest cliRequest = toCliRequest( commonCliRequest );

            // we are in a container so no need
            //localContainer = container( cliRequest );

            DefaultEventSpyContext eventSpyContext = new DefaultEventSpyContext();
            Map<String, Object> data = eventSpyContext.getData();
            data.put( "plexus", plexusContainer );
            data.put( "workingDirectory",  cliRequest.workingDirectory );
            data.put( "systemProperties", cliRequest.systemProperties );
            data.put( "userProperties", cliRequest.userProperties );
            data.put( "versionProperties", CLIReportingUtils.getBuildProperties() );
            eventSpyDispatcher.init( eventSpyContext );

            commands( cliRequest );
//            settings( cliRequest );
            configure( cliRequest );
            populateRequest( cliRequest );
            toolchains( cliRequest );
            encryption( cliRequest );
            repository( cliRequest );

            return executionRequestPopulator.populateDefaults( cliRequest.request );
        }
        catch ( Exception e )
        {
            throw new MavenExecutionRequestsBuilderException( e.getMessage(), e );
        }
    }

    private CliRequest toCliRequest( CommonCliRequest commonCliRequest ) {
        CliRequest cliRequest = new CliRequest( commonCliRequest.args, null );

        cliRequest.commandLine = commonCliRequest.commandLine;
        cliRequest.classWorld = commonCliRequest.classWorld;
        cliRequest.workingDirectory = commonCliRequest.workingDirectory;
        cliRequest.multiModuleProjectDirectory = commonCliRequest.multiModuleProjectDirectory;
        cliRequest.debug = commonCliRequest.debug;
        cliRequest.quiet = commonCliRequest.quiet;
        cliRequest.showErrors = commonCliRequest.showErrors;
        cliRequest.userProperties = commonCliRequest.userProperties;
        cliRequest.systemProperties = commonCliRequest.systemProperties;
        cliRequest.request = commonCliRequest.request;

        return cliRequest;
    }

    /**
     * @throws MavenExecutionRequestPopulationException
     */
    public MavenExecutionRequest getMavenExecutionRequest( String[] args, PrintStream printStream)
        throws MavenExecutionRequestPopulationException, SettingsBuildingException, MavenExecutionRequestsBuilderException
    {
        throw new UnsupportedOperationException( "This method should not be called" );
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
            toolchainsBuildingRequest.setUserToolchainsSource( new FileSource( cliRequest.request.getUserToolchainsFile() ) );
        }
        if (MavenCli.DEFAULT_GLOBAL_TOOLCHAINS_FILE.isFile()) {
            toolchainsBuildingRequest.setGlobalToolchainsSource( new FileSource( MavenCli.DEFAULT_GLOBAL_TOOLCHAINS_FILE ) );
        }

        eventSpyDispatcher.onEvent( toolchainsBuildingRequest );

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
            userSettingsFile = SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE;
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
            globalSettingsFile = SettingsXmlConfigurationProcessor.DEFAULT_GLOBAL_SETTINGS_FILE;
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

    @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE")
    private void configure(CliRequest cliRequest )
        throws Exception
    {
        //
        // This is not ideal but there are events specifically for configuration from the CLI which I don't
        // believe are really valid but there are ITs which assert the right events are published so this
        // needs to be supported so the EventSpyDispatcher needs to be put in the CliRequest so that
        // it can be accessed by configuration processors.
        //
        cliRequest.request.setEventSpyDispatcher( eventSpyDispatcher );

        //
        // We expect at most 2 implementations to be available. The SettingsXmlConfigurationProcessor implementation
        // is always available in the core and likely always will be, but we may have another ConfigurationProcessor
        // present supplied by the user. The rule is that we only allow the execution of one ConfigurationProcessor.
        // If there is more than one then we execute the one supplied by the user, otherwise we execute the
        // the default SettingsXmlConfigurationProcessor.
        //
        int userSuppliedConfigurationProcessorCount = configurationProcessors.size() - 1;

        if ( userSuppliedConfigurationProcessorCount == 0 )
        {
            //
            // Our settings.xml source is historically how we have configured Maven from the CLI so we are going to
            // have to honour its existence forever. So let's run it.
            //
            configurationProcessors.get( SettingsXmlConfigurationProcessor.HINT ).process( cliRequest );
        }
        else if ( userSuppliedConfigurationProcessorCount == 1 )
        {
            //
            // Run the user supplied ConfigurationProcessor
            //
            for ( Map.Entry<String, ConfigurationProcessor> entry : configurationProcessors.entrySet() )
            {
                String hint = entry.getKey();
                if ( !hint.equals( SettingsXmlConfigurationProcessor.HINT ) )
                {
                    ConfigurationProcessor configurationProcessor = entry.getValue();
                    configurationProcessor.process( cliRequest );
                }
            }
        }
        else if ( userSuppliedConfigurationProcessorCount > 1 )
        {
            //
            // There are too many ConfigurationProcessors so we don't know which one to run so report the error.
            //
            StringBuilder sb = new StringBuilder(
                String.format( "\nThere can only be one user supplied ConfigurationProcessor, there are %s:\n\n",
                    userSuppliedConfigurationProcessorCount ) );
            for ( Map.Entry<String, ConfigurationProcessor> entry : configurationProcessors.entrySet() )
            {
                String hint = entry.getKey();
                if ( !hint.equals( SettingsXmlConfigurationProcessor.HINT ) )
                {
                    ConfigurationProcessor configurationProcessor = entry.getValue();
                    sb.append( String.format( "%s\n", configurationProcessor.getClass().getName() ) );
                }
            }
            sb.append( String.format( "\n" ) );
            throw new Exception( sb.toString() );
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
            MessageUtils.setColorEnabled(false);
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

        List<String> activeProfiles = new ArrayList<>();

        List<String> inactiveProfiles = new ArrayList<>();

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
            .setUserToolchainsFile( userToolchainsFile )
            .setMultiModuleProjectDirectory( cliRequest.multiModuleProjectDirectory );

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

        if ( commandLine.hasOption( CLIManager.THREADS ) )
        {
            final String threadConfiguration = commandLine.getOptionValue( CLIManager.THREADS );
        	int threadCount = 1;
        	try {
        		threadCount = Integer.parseInt(threadConfiguration
                                                   .replace( "C", "" )
                                                   .replace( "W", "" )
                                                   .replace( "auto", "" ));
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
        return new ConsoleMavenTransferListener( System.out ,false);
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
