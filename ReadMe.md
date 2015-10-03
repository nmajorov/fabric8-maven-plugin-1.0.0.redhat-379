Maven plugin to apply profile to container.
It is enable redeploy of application on fuse.

*Attention*
For standalone container activate jolokia feature first with command:

        features:install jolokia

To enable jolokia feature permanently you have to edit file:

        vi ./etc/org.apache.karaf.features.cfg
        
find selection:

             featuresBoot
 
  and add 
            
                jolokia
            
 feature.       

Example of the plugin usage:


        mvn com.nagravision.it:fabric8-maven-plugin:1.2.0-SNAPSHOT:deploy -DjolokiaUrl=http://localhost:8282/jolokia \
        -Dfabric8.featureRepos="mmvn:io.fabric8.quickstarts/nm-camel-rest/1.0-SNAPSHOT/xml/features" \
        -Dfabric8.features=nm-camel-rest-client


At the moment only one feature and one featureRepo can be deployed to a standalone fuse container.
I tested it with fuse 6.2.0 version.
