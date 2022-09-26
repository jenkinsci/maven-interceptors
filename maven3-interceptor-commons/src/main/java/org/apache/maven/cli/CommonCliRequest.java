package org.apache.maven.cli;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.cli.CommandLine;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.codehaus.plexus.classworlds.ClassWorld;

import java.io.File;
import java.util.Properties;

/**
 * Copied from {@code org.apache.maven.cli.CliRequest} from Maven {@code 3.5.4}. Older versions might not fill in
 * all the fields.
 */
public class CommonCliRequest
{
    @SuppressFBWarnings({ "EI_EXPOSE_REP", "EI_EXPOSE_REP2" })
    String[] args;

    CommandLine commandLine;

    ClassWorld classWorld;

    String workingDirectory;

    File multiModuleProjectDirectory;

    boolean debug;

    boolean quiet;

    boolean showErrors = true;

    Properties userProperties = new Properties();

    Properties systemProperties = new Properties();

    MavenExecutionRequest request;

    public CommonCliRequest( String[] args, ClassWorld classWorld )
    {
        this.args = args;
        this.classWorld = classWorld;
        this.request = new DefaultMavenExecutionRequest();
    }

    public String[] getArgs()
    {
        return args;
    }

    public CommandLine getCommandLine()
    {
        return commandLine;
    }

    public ClassWorld getClassWorld()
    {
        return classWorld;
    }

    public String getWorkingDirectory()
    {
        return workingDirectory;
    }

    public File getMultiModuleProjectDirectory()
    {
        return multiModuleProjectDirectory;
    }

    public boolean isDebug()
    {
        return debug;
    }

    public boolean isQuiet()
    {
        return quiet;
    }

    public boolean isShowErrors()
    {
        return showErrors;
    }

    public Properties getUserProperties()
    {
        return userProperties;
    }

    public Properties getSystemProperties()
    {
        return systemProperties;
    }

    public MavenExecutionRequest getRequest()
    {
        return request;
    }

    public void setUserProperties( Properties properties )
    {
        this.userProperties.putAll( properties );
    }
}
