# deploy.ps1
$sshKey = "C:\Users\vande\.ssh\netlab"
$vmUser = "s0233366@143.129.43.65"

echo "1. Make sure you double-clicked 'package' in IntelliJ!"

echo "2. Copying JARs and Dockerfiles to the VM..."
# Create a deployment folder on the VM if it doesn't exist
ssh -i $sshKey $vmUser "mkdir -p ~/deploy/naming-server ~/deploy/node"

# Copy ONLY the necessities to the VM
scp -i $sshKey .\naming-server\target\naming-server-0.0.1-SNAPSHOT.jar ${vmUser}:~/deploy/naming-server/app.jar
scp -i $sshKey .\naming-server\Dockerfile ${vmUser}:~/deploy/naming-server/
scp -i $sshKey .\node\target\node-0.0.1-SNAPSHOT.jar ${vmUser}:~/deploy/node/app.jar
scp -i $sshKey .\node\Dockerfile ${vmUser}:~/deploy/node/

echo "3. Rebuilding Docker Images on the VM..."
ssh -i $sshKey $vmUser "cd ~/deploy && sudo docker build -t naming-server-img ./naming-server && sudo docker build -t node-img ./node"

echo "Deployment Complete! You can now run your containers."