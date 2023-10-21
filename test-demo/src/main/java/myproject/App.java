package myproject;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.pulumi.aws.ec2.inputs.AmiEbsBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.aws.ec2.outputs.SecurityGroupIngress;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.core.Output;


public class App {

	public static void main(String[] args) {

		Pulumi.run(App::stack);
	}

	public static void stack(Context ctx) {

		var config2 = ctx.config();

		Yaml yaml = new Yaml();
		String yamlFile = "Pulumi.dev.yaml";
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
			String ami_idStr = (String) config.get("amivalid");
			String seckeyidValStr = (String) config.get("seckeyidVal");
			String instanceArgsNameStr = (String) config.get("tag:instanceArgsName");
			String instanceNameStr = (String) config.get("tag:instanceName");
			String volumeTypeStr = (String) config.get("volumeType");
			String securityGroupIdStr = (String) config.get("securityGroupId");
			String securityGroupNameStr = (String) config.get("securityGroupName");
			
			


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

			
		
			 Output<String> vpcId = mainVPC.getId();
			
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
			Output<String> selectedSubnetId = null;  // Change the variable type to String
			
			



			
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
	                .vpcId(vpcId) // Replace with your VPC ID
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
			

			
			Output<String> securityGroudId =  mySecurityGroup.id();
			
			selectedSubnetId.applyValue(value -> {
				securityGroudId.applyValue(groupIdValue -> {
					vpcId.applyValue(vpcIdValue -> {
					InstanceArgs instanceArgs = InstanceArgs.builder()
						    .instanceType("t2.micro")  // Set the instance type as per your requirements
						    .ami(ami_idStr)   // Replace with the actual AMI ID you want to use
						    .subnetId(value)  // Use the selected subnet ID as a string
						    .tags(Map.of("Name", instanceArgsNameStr))  // Customize tags as needed
						    .keyName(seckeyidValStr)  // Specify the SSH key nam
						    .vpcSecurityGroupIds(groupIdValue)
						    .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
						                .volumeSize(25) // Replace with the desired volume size
						                .volumeType(volumeTypeStr) // Replace with the desired volume type (e.g., gp2, io1, etc.)
						                .deleteOnTermination(true) // Set to true to delete the volume on instance termination
						                .build()
						        )
						    .build();

						// Create the EC2 instance
						Instance ec2Instance = new Instance(instanceNameStr, instanceArgs);
						return null;
					});
					return null;
				});
					
					return null;
			});
				
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

	}

	private static String incrementCIDR(String baseCidr, int subnetMask, int i) {
		String[] parts = baseCidr.split("\\.");
		int thirdOctet = Integer.parseInt(parts[2]);
		int newThirdOctet = thirdOctet + i;
		return parts[0] + "." + parts[1] + "." + newThirdOctet + ".0/" + subnetMask;
	}

}
