package myproject;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.yaml.snakeyaml.Yaml;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.Instance;
import com.pulumi.aws.ec2.InstanceArgs;
import com.pulumi.aws.ec2.InternetGateway;
import com.pulumi.aws.ec2.InternetGatewayArgs;
import com.pulumi.aws.ec2.Route;
import com.pulumi.aws.ec2.RouteArgs;
import com.pulumi.aws.ec2.RouteTable;
import com.pulumi.aws.ec2.RouteTableArgs;
import com.pulumi.aws.ec2.RouteTableAssociation;
import com.pulumi.aws.ec2.RouteTableAssociationArgs;
import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.Subnet;
import com.pulumi.aws.ec2.SubnetArgs;
import com.pulumi.aws.ec2.Vpc;
import com.pulumi.aws.ec2.VpcArgs;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.aws.iam.InstanceProfile;
import com.pulumi.aws.iam.InstanceProfileArgs;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;
import com.pulumi.aws.iam.RolePolicyAttachment;
import com.pulumi.aws.iam.RolePolicyAttachmentArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.core.Output;


public class App {

	private static Output<String> selectedSubnetId;

	public static void main(String[] args) {

		Pulumi.run(App::stack);
	}

	public static void stack(Context ctx) {

		var config2 = ctx.config();

		Yaml yaml = new Yaml();
		String yamlFile = "Pulumi.demo.yaml";
		try {
			Map<String, Object> yamlData = yaml.load(new FileInputStream(yamlFile));

			// Access values from the YAML data
			String encryptionsalt = (String) yamlData.get("encryptionsalt");

			Map<String, Object> config = (Map<String, Object>) yamlData.get("config");
			String awsRegion = (String) config.get("aws:region");
			String networkingCidr = (String) config.get("networking:cidr");
			String vpcNameStr = (String) config.get("tag:vpcname");
			String pubSubnetNameStr = (String) config.get("tag:pubsubnetname");
			String priSubnetNameStr = (String) config.get("tag:prisubnetname");
			String pubRouteNameStr = (String) config.get("tag:pubroutingname");
			String priRouteNameStr = (String) config.get("tag:priroutingname");
			String gatewayNameStr = (String) config.get("tag:gatewayname");
			String publicRouteCidrStr = (String) config.get("publicRoute:cidr");
			Integer subnetCidrRange = (Integer) config.get("subnetcidr:range");
			String rdsFamilyNameStr = (String) config.get("rdsFamilyName");
			

			final CompletableFuture<GetAvailabilityZonesResult> available = AwsFunctions
					.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());

			List<GetAvailabilityZonesResult> availableZonesList = List.of(available.join());

			List<String> zoneNames = availableZonesList.stream().map(GetAvailabilityZonesResult::names)
					.flatMap(List::stream).collect(Collectors.toList());

			// Access the availability zones as a list
			int count = 0;
			HashSet<String> set = new HashSet<>();
			for (String zone : zoneNames) {
				if (zone.contains(awsRegion)) {
					if (count == 3)
						break;

					set.add(zone);
					count = count + 1;

				}
			}

			String[] zoneArray = set.toArray(new String[0]);

			var mainVPC = new Vpc("main",
					VpcArgs.builder().cidrBlock(networkingCidr).tags(Map.of("Name", vpcNameStr)).build());

			
		
			 Output<String> vpcId = mainVPC.id();
			
			// Create an Internet Gateway
			InternetGateway internetGateway = new InternetGateway("internetGateway",
					InternetGatewayArgs.builder().vpcId(mainVPC.id()) // Attach the Internet Gateway to the VPC
							.tags(Map.of("Name", gatewayNameStr)).build());

			// Create a single public route table
			RouteTable publicRouteTable = new RouteTable("publicRouteTable",
					RouteTableArgs.builder().vpcId(mainVPC.id()).tags(Map.of("Name", pubRouteNameStr)).build());

			// Create a single private route table
			RouteTable privateRouteTable = new RouteTable("privateRouteTable",
					RouteTableArgs.builder().vpcId(mainVPC.id()).tags(Map.of("Name", priRouteNameStr)).build());

			String[] cidrParts = networkingCidr.split("/");
			String baseCidr = cidrParts[0];
			int vpcCidrMask = Integer.parseInt(cidrParts[1]);
			int subnetMask = subnetCidrRange;
			
			List<Output<String>> privateSubnetIds = new ArrayList<>();



			
			for (int i = 0; i < zoneArray.length; i++) {

				String newPublicSubnetCidr = incrementCIDR(networkingCidr, subnetMask, i);
				String newPrivateSubnetCidr = incrementCIDR(networkingCidr, subnetMask, i + 100);
				

				// Public Subnet
				Subnet publicSubnet = new Subnet("publicSubnet" + i,
						SubnetArgs.builder().vpcId(mainVPC.id()).availabilityZone(zoneArray[i])
								.cidrBlock(newPublicSubnetCidr).mapPublicIpOnLaunch(true)
								.tags(Map.of("Name", pubSubnetNameStr + i)).build());
				
				selectedSubnetId = publicSubnet.id();
				
				

				// Private Subnet
				Subnet privateSubnet = new Subnet("privateSubnet" + i,
						SubnetArgs.builder().vpcId(mainVPC.id()).availabilityZone(zoneArray[i])
								.cidrBlock(newPrivateSubnetCidr).tags(Map.of("Name", priSubnetNameStr + i)).build());
				privateSubnetIds.add(privateSubnet.id());

				// Create a route to the Internet Gateway
				Route publicRoute = new Route("publicRoute" + i, RouteArgs.builder().routeTableId(publicRouteTable.id())
						.destinationCidrBlock(publicRouteCidrStr).gatewayId(internetGateway.id()).build());

				// Associate the public route table with the public subnet
				RouteTableAssociation publicSubnetRTAssoc = new RouteTableAssociation("publicSubnetRTAssoc" + i,
						RouteTableAssociationArgs.builder().subnetId(publicSubnet.id())
								.routeTableId(publicRouteTable.id()).build());

				// Associate the private route table with the private subnet
				RouteTableAssociation privateSubnetRTAssoc = new RouteTableAssociation("privateSubnetRTAssoc" + i,
						RouteTableAssociationArgs.builder().subnetId(privateSubnet.id())
								.routeTableId(privateRouteTable.id()).build());
				
			


			}
			String securityGroupIdStr = (String) config.get("securityGroupId");
			String securityGroupNameStr = (String) config.get("securityGroupName");
			
			List<SecurityGroupIngressArgs> ingressRules = new ArrayList<>();
    		ingressRules.add(SecurityGroupIngressArgs.builder()
    			    .fromPort(22) // SSH port
    			    .toPort(22)
    			    .protocol("tcp")
    			    .cidrBlocks("0.0.0.0/0") // Replace with your allowed IP ranges
    			    .build());

    			ingressRules.add(SecurityGroupIngressArgs.builder()
    			    .fromPort(80) // HTTP port
    			    .toPort(80)
    			    .protocol("tcp")
    			    .cidrBlocks("0.0.0.0/0") // Replace with your allowed IP ranges
    			    .build());

    			ingressRules.add(SecurityGroupIngressArgs.builder()
    			    .fromPort(3000) // Port 3000
    			    .toPort(3000)
    			    .protocol("tcp")
    			    .cidrBlocks("0.0.0.0/0") // Replace with your allowed IP ranges
    			    .build());
    			
    			ingressRules.add(SecurityGroupIngressArgs.builder()
    				    .fromPort(443) // HTTPS port
    				    .toPort(443)
    				    .protocol("tcp")
    				    .cidrBlocks("0.0.0.0/0") // Replace with your allowed IP ranges
    				    .build());
    		
    	
    		SecurityGroup mySecurityGroup = new SecurityGroup(securityGroupIdStr, new SecurityGroupArgs.Builder()
                    .vpcId(vpcId)
                    .description(securityGroupNameStr)
                    .ingress(ingressRules)
                    // Add more ingress rules as needed
                    .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0)
                        .toPort(0)
                        .protocol("-1") // Allow all outbound traffic
                        .cidrBlocks("0.0.0.0/0")
                        .build())
                    .build());
			
			

            //DB Security Group
            SecurityGroup rdsSecurityGroup = new SecurityGroup("myRDSecurityGroup",
                    new SecurityGroupArgs.Builder()
                            .vpcId(vpcId)
                            .description("My RDS Security Group")
                            .ingress(SecurityGroupIngressArgs.builder()
                                    .fromPort(3306)
                                    .toPort(3306)
                                    .protocol("tcp")
                                    .securityGroups(mySecurityGroup.id().applyValue(List::of))
                                    .build())
                            .build());
            
            
            // DB parameter group for MySQL
            ParameterGroup dbParameterGroup = new ParameterGroup("mydbparam",
                    new ParameterGroupArgs.Builder()
                            .family(rdsFamilyNameStr)  
                            .build());
            
            
            
           final List<String> privateSubnetIdStrings = new ArrayList<>();
           for(Output<String> item : privateSubnetIds) {
        	   item.applyValue(value -> {
        		   privateSubnetIdStrings.add(value);
        		   if (privateSubnetIdStrings.size() == privateSubnetIds.size()) {         
        	            createDNS(dbParameterGroup, rdsSecurityGroup, config, privateSubnetIdStrings, vpcId, selectedSubnetId, mySecurityGroup);
        		   }
        		   return null;
        	   });
        	   
           }
				
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

	}
	
	private static void createDNS(
			ParameterGroup dbParameterGroup, 
			SecurityGroup rdsSecurityGroup, 
			Map<String, Object> config, 
			List<String> privateSubnetIds,
			Output<String> vpcId,
			Output<String> selectedSubnetId,SecurityGroup mySecurityGroup
			) {
        SubnetGroup rdsSubnetGroup = new SubnetGroup("my-rds-subnet-group", SubnetGroupArgs.builder()
        	    .subnetIds(privateSubnetIds)  // Use the list of public subnet IDs or privateSubnetIds for your RDS instance
        	    .description("My RDS Subnet Group Description")
        	    .name("my-rds-subnet-group-name")
        	    .build());
		// Add RDS-related resources
        String dbNameStr = (String) config.get("dbName");
        String dbUsername = (String) config.get("dbuserName");
        String dbPassword = (String) config.get("dbPassword");

		String ami_idStr = (String) config.get("amivalid");
		String instanceArgsNameStr = (String) config.get("tag:instanceArgsName");
		String instanceNameStr = (String) config.get("tag:instanceName");
		String volumeTypeStr = (String) config.get("volumeType");
		String seckeyidValStr = (String) config.get("seckeyidVal");
		String engineNameStr = (String) config.get("engineName");
		String engineVersionStr = (String) config.get("engineVersion");
		String instanceClassNameStr = (String) config.get("instanceClassName");
		String instanceTypeStr = (String) config.get("instanceType");
		String hostedNameStr = (String) config.get("hostedName");
		String zoneIDStr = (String) config.get("zoneID");

		
		 
        // Create RDS instance
        com.pulumi.aws.rds.Instance rdsInstance = new com.pulumi.aws.rds.Instance("csye6225", com.pulumi.aws.rds.InstanceArgs.builder()        
                .allocatedStorage(20)
                .dbName(dbNameStr)
                .engine(engineNameStr)
                .engineVersion(engineVersionStr)
                .instanceClass(instanceClassNameStr)
                .parameterGroupName(dbParameterGroup.name())
                .password(dbPassword)
                .username(dbUsername)
                .dbSubnetGroupName(rdsSubnetGroup.name())
                .vpcSecurityGroupIds(rdsSecurityGroup.id().applyValue(List::of))
                .publiclyAccessible(false)
                .skipFinalSnapshot(true)
                .multiAz(false)
                .build());
        
     
        
        Output<String> rdsEndpoint = rdsInstance.endpoint();
        rdsEndpoint.applyValue(endPointvalue -> {
        	
        	String userDataScript = "#!/bin/bash\n" +
        		    "DB_HOST=" + endPointvalue + "\n" +
        		    "DB_USERNAME=" + dbUsername + "\n" +
        		    "DB_PASSWORD=" + dbPassword + "\n" +
        		    "DB_NAME=" + dbNameStr + "\n" +
        		    "PORT=3000\n" +
        		    "sudo apt update -y\n" +
        		    "{\n" +
        		    "  echo \"AWS_RDS_DB_ENDPOINT=$DB_HOST\"\n" +
        		    "  echo \"AWS_RDS_DB_PORT=3306\"\n" +
        		    "  echo \"AWS_RDS_DB_MASTER_USERNAME=$DB_USERNAME\"\n" +
        		    "  echo \"AWS_RDS_DB_MASTER_PASSWORD=$DB_PASSWORD\"\n" +
        		    "  echo \"AWS_RDS_DB_NAME=$DB_NAME\"\n" +
        		    "  echo \"PORT=$PORT\"\n" +
        		    "} >> /opt/webapps/application.properties\n"+
        		    "chmod 755 /opt/webapps/application.properties\n" +
        		    "sudo chown -R devappuser:appgroup /opt/webapps\n"+
        		    "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/webapps/cloudwatch-config.json  -s\n";
        	
        	
        	// Create an IAM role
        	Role cloudWatchAgentRole = new Role("CloudWatchAgentRole", RoleArgs.builder()
        		    .assumeRolePolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Action\":\"sts:AssumeRole\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"}}]}")
        		    .description("IAM role for CloudWatch Agent")
        		    .name("CloudWatchAgentRole")
        		    .tags(Map.of("Name", "CloudWatchAgentRole"))
        		    .build());

            // Attach the CloudWatchAgentServerPolicy to the IAM role
        	new RolePolicyAttachment("CloudWatchAgentServerPolicyAttachment", RolePolicyAttachmentArgs.builder()
        		    .policyArn("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
        		    .role(cloudWatchAgentRole.name())
        		    .build());
        	
        	// Create an IAM instance profile
        	InstanceProfile cloudWatchAgentProfile = new InstanceProfile("CloudWatchAgentProfile", InstanceProfileArgs.builder()
        	    .name("CloudWatchAgentProfile")
        	    .role(cloudWatchAgentRole.name())
        	    .build());

        	
        	
    		

    		
    		Output<String> securityGroudId =  mySecurityGroup.id();
    		
    		selectedSubnetId.applyValue(value -> {
    			securityGroudId.applyValue(groupIdValue -> {
    				vpcId.applyValue(vpcIdValue -> {
    				InstanceArgs instanceArgs = InstanceArgs.builder()
    					    .instanceType(instanceTypeStr)  
    					    .ami(ami_idStr)   
    					    .subnetId(value)  
    					    .tags(Map.of("Name", instanceArgsNameStr))  
    					    .keyName(seckeyidValStr) 
    					    .iamInstanceProfile(cloudWatchAgentProfile.name())
    					    .vpcSecurityGroupIds(groupIdValue)
    					    .userData(userDataScript)
    					    .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
    					                .volumeSize(25) 
    					                .volumeType(volumeTypeStr) // desired volume type (e.g., gp2, io1, etc.)
    					                .deleteOnTermination(true) // Set to true to delete the volume on instance termination
    					                .build()
    					        )
    				
    					    
    					    .build();

    					// Create the EC2 instance
    					Instance ec2Instance = new Instance(instanceNameStr, instanceArgs);
    					
    					Output<String> ec2IpAddress = ec2Instance.publicIp();
    							 //"dev.csye6225hemangi.com";
    					createRoute53DevRecord(ec2IpAddress,hostedNameStr, zoneIDStr);
    					//createRoute53DemoRecord(ec2IpAddress,demoHostedNameStr, demoZoneIDStr);
    					
    					return null;
    				});
    				return null;
    			});
    				
    				return null;
    		});
    
        	return null;

        });
	}

	private static String incrementCIDR(String baseCidr, int subnetMask, int i) {
		String[] parts = baseCidr.split("\\.");
		int thirdOctet = Integer.parseInt(parts[2]);
		int newThirdOctet = thirdOctet + i;
		return parts[0] + "." + parts[1] + "." + newThirdOctet + ".0/" + subnetMask;
	}
	private static void createRoute53DevRecord(Output<String> ec2IpAddress, String devHostname, String zoneId) {
		 Record route53ARecordDev = new Record("my-route53-a-dev-record", new RecordArgs.Builder()
			        .name(devHostname) 
			        .type("A")
			        .zoneId(zoneId) 
			        .records(ec2IpAddress.applyValue(List::of))
			        .ttl(300) 
			        .build());
	}
	
	private static void createRoute53DemoRecord(Output<String> ec2IpAddress, String demoHostname, String zoneId) {
		 Record route53ARecordDemo = new Record("my-route53-a-demo-record", new RecordArgs.Builder()
			        .name(demoHostname) 
			        .type("A")
			        .zoneId(zoneId) 
			        .records(ec2IpAddress.applyValue(List::of))
			        .ttl(300) 
			        .build());
	}

}
