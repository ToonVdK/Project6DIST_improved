# deploy.ps1
$sshKey = "C:\Users\almaf\.ssh\netlab"
$vmUser = "s0233689@143.129.43.69"

echo "1. Make sure you ran Maven package in IntelliJ or with: mvn clean package -DskipTests"

echo "2. Creating deployment folders on the VM..."
ssh -i $sshKey $vmUser "mkdir -p ~/deploy/naming-server ~/deploy/node ~/deploy/gui"

echo "3. Copying JARs and Dockerfiles to the VM..."

scp -i $sshKey .\naming-server\target\naming-server-0.0.1-SNAPSHOT.jar ${vmUser}:~/deploy/naming-server/app.jar
scp -i $sshKey .\naming-server\Dockerfile ${vmUser}:~/deploy/naming-server/

scp -i $sshKey .\node\target\node-0.0.1-SNAPSHOT.jar ${vmUser}:~/deploy/node/app.jar
scp -i $sshKey .\node\Dockerfile ${vmUser}:~/deploy/node/

scp -i $sshKey .\gui\target\gui-0.0.1-SNAPSHOT.jar ${vmUser}:~/deploy/gui/app.jar
scp -i $sshKey .\gui\Dockerfile ${vmUser}:~/deploy/gui/

echo "4. Rebuilding Docker Images on the VM..."
ssh -i $sshKey $vmUser "cd ~/deploy && sudo docker build -t naming-server-img ./naming-server && sudo docker build -t node-img ./node && sudo docker build -t gui-img ./gui"

echo "Deployment Complete! You can now run your containers."