package io.fabric8.maven;

import io.fabric8.deployer.dto.DependencyDTO;
import io.fabric8.deployer.dto.ProjectRequirements;
import io.fabric8.utils.Strings;
import io.fabric8.deployer.dto.DeployResults;
import io.fabric8.deployer.ProjectDeployer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.management.ObjectName;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.deployer.ArtifactDeployer;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.SettingsWriter;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.jolokia.client.J4pClient;
import org.jolokia.client.exception.J4pException;
import org.jolokia.client.request.J4pExecRequest;
import org.jolokia.client.request.J4pResponse;
import org.jolokia.client.request.J4pWriteRequest;
import org.jolokia.client.request.J4pWriteResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import io.fabric8.deployer.dto.DtoHelper;
import io.fabric8.utils.Files;
import io.fabric8.utils.Base64Encoder;

/**
 * Plugin to trigger deployment of profile on fabric
 *
 **/
/**
 * Generates the dependency configuration for the current project so we can HTTP
 * POST the JSON into the fabric8 profile
 */
// SPT : please check what phase choose to do here
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.INSTALL, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute(phase = LifecyclePhase.INSTALL)
public class DeployToProfileMojo extends AbstractMojo {

    @Component
    MavenProject project;

    @Component
    Settings mavenSettings;

    @Parameter(defaultValue = "${user.home}/.m2/settings.xml")
    private File mavenSettingsFile;

    @Component
    SettingsWriter mavenSettingsWriter;

    @Component
    ArtifactCollector artifactCollector;

    @Component
    ArtifactResolver resolver;

    @Component
    ArtifactFactory artifactFactory;

    @Component
    DependencyTreeBuilder dependencyTreeBuilder;

    @Component
    ArtifactDeployer deployer;

    /**
     * The scope to filter by when resolving the dependency tree
     */
    @Parameter(property = "fabric8.scope", defaultValue = "compile")
    private String scope;

    /**
     * The server ID in ~/.m2/settings/xml used for the username and password to
     * login to both the fabric8 maven repository and the jolokia REST API
     */
    @Parameter(property = "fabric8.serverId", defaultValue = "fabric8.upload.repo")
    private String serverId;

    /**
     * The URL for accessing jolokia on the fabric.
     */
    @Parameter(property = "jolokiaUrl", defaultValue = "http://localhost:8181/jolokia", required = true)
    private String jolokiaUrl;
    
    /** Specify username by parameter rather than from settings.xml */
    @Parameter(property = "jolokiaUsername")
    private String jolokiaUsername;
    
    /** Specify password by parameter rather than from settings.xml */
    @Parameter(property = "jolokiaPassword")
    private String jolokiaPassword;
    

    

    /**
     * The space separated list of bundle URLs (in addition to the project
     * artifact) which should be added to the profile
     */
    @Parameter(property = "fabric8.bundles")
    private String bundles;

    /**
     * The space separated list of features to be added to the profile
     */
    @Parameter(property = "fabric8.features")
    private String features;

    /**
     * The space separated list of feature repository URLs to be added to the
     * profile
     */
    @Parameter(property = "fabric8.featureRepos")
    private String featureRepos;

    /**
     * Whether or not we should upload the deployment unit to the fabric maven
     * repository.
     */
    @Parameter(property = "fabric8.upload", defaultValue = "false")
    private boolean upload;



    @Component
    ArtifactMetadataSource metadataSource;

    @Parameter(property = "localRepository", readonly = true, required = true)
    ArtifactRepository localRepository;

    @Parameter(property = "project.remoteArtifactRepositories")
    List<?> remoteRepositories;

    /**
     * Parameter used to control how many times a failed deployment will be
     * retried before giving up and failing. If a value outside the range 1-10
     * is specified it will be pulled to the nearest value within the range
     * 1-10.
     */
    @Parameter(property = "retryFailedDeploymentCount", defaultValue = "1")
    private int retryFailedDeploymentCount;



    /**
     * The folder used
     */
    @Parameter(property = "profileConfigDir", defaultValue = "${basedir}/src/main/fabric8")
    private File profileConfigDir;

    private Server fabricServer;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
         

            fabricServer = mavenSettings.getServer(serverId);
            
            // jolokia url overrides username/password configured in maven settings
            if (jolokiaUsername != null) {
                if (fabricServer == null) {
                    fabricServer = new Server();
                }
                
                getLog().info("Using username: " + jolokiaUsername + " and password from plugin parameters");
                
                fabricServer.setUsername(jolokiaUsername);
                fabricServer.setPassword(jolokiaPassword);
            }
            
            
            if (fabricServer == null) {
                if (mavenSettings.isInteractiveMode()
                        && mavenSettingsWriter != null) {
                    System.out.println("Maven settings file: "
                            + mavenSettingsFile.getAbsolutePath());
                    System.out.println();
                    System.out.println();
                    System.out
                            .println("There is no <server> section in your ~/.m2/settings.xml file for the server id: "
                                    + serverId);
                    System.out.println();


                }
            }

            if (fabricServer == null) {
                String message = "No <server> element can be found in ~/.m2/settings.xml for the server <id>"
                        + serverId
                        + "</id> so we cannot connect to fabric8!\n\n"
                        + "Please add the following to your ~/.m2/settings.xml file (using the correct user/password values):\n\n"
                        + "<servers>\n"
                        + "  <server>\n"
                        + "    <id>"
                        + serverId
                        + "</id>\n"
                        + "    <username>admin</username>\n"
                        + "    <password>admin</password>\n"
                        + "  </server>\n"
                        + "</servers>\n";
                getLog().error(message);
                throw new MojoExecutionException(message);
            }

            // now lets invoke the mbean
            J4pClient client = createJolokiaClient();


            uploadRequirements(client);
            //if (results != null) {
              //  uploadProfileConfigurations(client, results);
            //}


            getLog().info("done");

            
            
            
            

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing", e);
        }
    }


    protected String readInput(String prompt) {
        while (true) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    System.in));
            System.out.print(prompt);
            try {
                String line = reader.readLine();
                if (line != null && Strings.isNotBlank(line)) {
                    return line;
                }
            } catch (IOException e) {
                getLog().warn("Failed to read input: " + e, e);
            }
        }
    }


    protected static boolean isFile(File file) {
        return file != null && file.exists() && file.isFile();
    }


    protected void uploadRequirements(J4pClient client) throws Exception {

        String mbeanName = "org.apache.karaf:type=features,name=root";



        try {

            if (featureRepos != null) {

                //TODO check if featureRepo is available and remove it first
                //TODO add serveral repos as comma-separated values
                getLog().info("About to invoke mbean " + mbeanName + " on jolokia URL: "
                        + jolokiaUrl + " with user: " + fabricServer.getUsername()
                        + " featureRepos: " + featureRepos);

                J4pExecRequest request = new J4pExecRequest(mbeanName,"addRepository(java.lang.String)", featureRepos);
                J4pResponse<J4pExecRequest> response = client.execute(request, "POST");

                JSONObject json = (JSONObject) response.asJSONObject();
                getLog().info("got response: " + json.toJSONString());
            }else{
                getLog().info("no features url was found to deploy");
            }

            if (features !=null){

                //TODO check for several features as comma-separated values
                //for now only one feature can be activated at the time.
                getLog().info("About to invoke mbean " + mbeanName + " on jolokia URL: "
                        + jolokiaUrl + " with user: " + fabricServer.getUsername()
                        + " features: " + features);
                J4pExecRequest request = new J4pExecRequest(mbeanName,"installFeature(java.lang.String)", features);
                J4pResponse<J4pExecRequest> response = client.execute(request, "POST");

                //json = (JSONObject) response.getValue();
                getLog().info("got response: " + response.asJSONObject().toJSONString());

               //TODO check if features is installed and uninstall it

            }else{
                getLog().info("no features was found to deploy");
            }

        } catch (J4pException e) {
            throw e;

        }

    }


    protected void uploadProfileConfigurations(J4pClient client, DeployResults results) throws Exception {
      
          getLog().info("No profile configuration file directory " + profileConfigDir + " is defined in this project; so not importing any other configuration files into the profile.");
      
    }


    


    protected J4pClient createJolokiaClient() throws MojoExecutionException {
        String user = fabricServer.getUsername();
        String password = fabricServer.getPassword();
        if (Strings.isNullOrBlank(user)) {
            throw new MojoExecutionException(
                    "No <username> value defined for the server "
                            + serverId
                            + " in your ~/.m2/settings.xml. Please add a value!");
        }
        if (Strings.isNullOrBlank(password)) {
            throw new MojoExecutionException(
                    "No <password> value defined for the server "
                            + serverId
                            + " in your ~/.m2/settings.xml. Please add a value!");
        }
        getLog().info("create jolokia client with parameters url: " + jolokiaUrl +
            " user: " + user);
        return J4pClient.url(jolokiaUrl).user(user).password(password).build();
    }



  



    private DependencyDTO buildFrom(DependencyNode node) {
        Artifact artifact = node.getArtifact();
        if (artifact != null) {
            DependencyDTO answer = new DependencyDTO();
            answer.setGroupId(artifact.getGroupId());
            answer.setArtifactId(artifact.getArtifactId());
            answer.setVersion(artifact.getVersion());
            answer.setClassifier(artifact.getClassifier());
            answer.setScope(artifact.getScope());
            answer.setType(artifact.getType());
            answer.setOptional(artifact.isOptional());

            List<?> children = node.getChildren();
            for (Object child : children) {
                if (child instanceof DependencyNode) {
                    DependencyNode childNode = (DependencyNode) child;
                    DependencyDTO childDTO = buildFrom(childNode);
                    answer.addChild(childDTO);
                }
            }
            return answer;
        }
        return null;
    }

    protected void walkTree(DependencyNode node, int level) {
        if (node == null) {
            getLog().warn("Null node!");
            return;
        }
        getLog().info(indent(level) + node.getArtifact());
        List<?> children = node.getChildren();
        for (Object child : children) {
            if (child instanceof DependencyNode) {
                walkTree((DependencyNode) child, level + 1);
            } else {
                getLog().warn("Unknown class " + child.getClass());
            }
        }
    }

    protected String indent(int level) {
        StringBuilder builder = new StringBuilder();
        while (level-- > 0) {
            builder.append("    ");
        }
        return builder.toString();
    }

    /**
     * Gets the artifact filter to use when resolving the dependency tree.
     *
     * @return the artifact filter
     */
    private ArtifactFilter createResolvingArtifactFilter() {
        ArtifactFilter filter;
        if (scope != null) {
            getLog().debug(
                    "+ Resolving dependency tree for scope '" + scope + "'");
            filter = new ScopeArtifactFilter(scope);
        } else {
            filter = null;
        }
        return filter;
    }


    


 

}
