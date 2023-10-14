# iac-pulumi

User Creation:1) Open root account 2) Create an organization.3) Add 2 user accounts in the created organization dev, demo. 4) Open respective accounts and create 2 IAM users. One with AdministratorAccess and the other with ReadOnlyAccess

Deployment:1) Checkout the forked repository to your local 2) Clone it. 3) Open the project in IDE.4) Create a Pulumi project pulumi new projectname5) Create a Stack inside Pulumi and configure the AWS profile.pulumi stack init devaws configure
Add all 4 parameters6) To continue with stackpulumi stack select dev7) Import the downloaded code files8) Run the pulumipulumi up9)Go to the respective was account and check the resources.10) After checking all resources on aws console, destroy the pulumi
pulumi destroy
