# Lab 7 GUI Patch - System Y Network Monitor

This patch adds a new Spring Boot + Thymeleaf GUI module to the existing project.

## What is included

```text
NEW:
  gui/
    pom.xml
    Dockerfile
    src/main/java/be/uantwerpen/fti/gui/GuiApplication.java
    src/main/java/be/uantwerpen/fti/gui/GuiController.java
    src/main/java/be/uantwerpen/fti/gui/GuiService.java
    src/main/resources/application.properties
    src/main/resources/templates/dashboard.html
    src/main/resources/static/css/dashboard.css

REPLACE:
  pom.xml
  docker-compose.yml
  node/src/main/java/be/uantwerpen/fti/node/NodeController.java
```

## Why NodeController is replaced

The GUI needs to show physical local files and physical replicated files separately. The existing node API already had `/api/node/info` and `/api/node/files/list`, but it did not have a clean endpoint for physical folders.

This patch adds:

```text
GET /api/node/files/physical
```

Example output:

```json
{
  "local": ["obama.txt"],
  "replicated": ["betafile.txt"]
}
```

## Run method A: recommended during development, GUI runs on VM host

1. Copy the patch files into the project root.
2. Build everything:

```bash
mvn clean package -DskipTests
sudo docker build -t naming-server-img ./naming-server
sudo docker build -t node-img ./node
```

3. Start naming server and nodes like before:

```bash
sudo docker network create system-y-net

sudo docker run -it --rm --name naming-server \
  --network system-y-net \
  -p 8080:8080 \
  naming-server-img
```

In other terminals:

```bash
sudo docker run -it --rm --name node-alpha \
  --network system-y-net \
  -e NODE_NAME=Alpha \
  -v ~/deploy/node/local_files_alpha:/local_files \
  -v ~/deploy/node/replicated_files_alpha:/replicated_files \
  node-img
```

```bash
sudo docker run -it --rm --name node-beta \
  --network system-y-net \
  -e NODE_NAME=Beta \
  -v ~/deploy/node/local_files_beta:/local_files \
  -v ~/deploy/node/replicated_files_beta:/replicated_files \
  node-img
```

```bash
sudo docker run -it --rm --name node-delta \
  --network system-y-net \
  -e NODE_NAME=Delta \
  -v ~/deploy/node/local_files_delta:/local_files \
  -v ~/deploy/node/replicated_files_delta:/replicated_files \
  node-img
```

4. Start the GUI directly on the VM host:

```bash
java -jar gui/target/gui-0.0.1-SNAPSHOT.jar \
  --gui.docker-network=system-y-net
```

5. Open:

```text
http://localhost:8090
```

If you use SSH port forwarding from your laptop:

```bash
ssh -i ./.ssh/netlab -L 8090:localhost:8090 sXXXXXXX@143.129.43.65
```

then open:

```text
http://localhost:8090
```

## Run method B: docker-compose

This patch also includes an updated docker-compose file with a GUI service. Build first:

```bash
mvn clean package -DskipTests
sudo docker compose up --build
```

Open:

```text
http://localhost:8090
```

The compose GUI service mounts `/var/run/docker.sock` so the Add/Remove node buttons can call Docker. If this is not allowed on your VM, use run method A.

## What the GUI shows

- Naming server status
- Number of active nodes
- List of all nodes
- Selected node configuration: ID, IP, previousID, nextID
- Local files and replicated files in two separate groups
- Global synchronized file list with owner and lock status

## Add/remove node buttons

The GUI's Add node button runs a Docker command similar to:

```bash
docker run -d --rm --name node-gamma \
  --network system-y-net \
  -e NODE_NAME=Gamma \
  -v ./gui-data/node-gamma/local_files:/local_files \
  -v ./gui-data/node-gamma/replicated_files:/replicated_files \
  node-img
```

Remove node runs:

```bash
docker stop node-gamma
```

This triggers your existing graceful shutdown logic.
